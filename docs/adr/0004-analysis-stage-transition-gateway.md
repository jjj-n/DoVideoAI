# 分析阶段统一通过 AnalysisStageService 迁移

## Context

分析任务的可观察阶段原本由多个调用方分别写入：`AnalysisDispatchService`、
`VideoAnalysisConsumer`、`AiService`、`AgentLoopService` 和
`FailedAnalysisTaskService` 会各自调用 `AgentCheckpointService.saveStage()` 与
`TaskEventService.publishAnalysis()`。这会产生三个问题：

- checkpoint 与 SSE 的写入顺序由调用方自行决定，容易漏写或顺序不一致
- `TaskStage` 只是枚举，没有地方校验非法迁移
- revision、Replay、复用与 Critic 重试等非主路径的迁移规则散落在编排代码中

ASR、OCR 与转录进度属于视频处理子流程，不应与 AnalysisTask 生命周期共用同一套迁移规则。

## Decision

新增 `AnalysisStageService.transition(mediaId, goal, mode, status, nextStage)` 作为分析任务阶段的
唯一应用层入口，并由 `AnalysisStagePolicy` 校验合法迁移。

迁移操作按以下顺序执行：

1. 绕过 Redis 热缓存，从 MySQL 真源读取当前分析阶段
2. 通过 `AnalysisStagePolicy` 校验 `current -> next`
3. 当前阶段与目标阶段不同时，用 `WHERE stage = current` 的单语句 compare-and-set 持久化
   `nextStage`；初始阶段用 `INSERT IGNORE` 保证只有一个创建者
4. 在同一 MySQL 事务中向 `analysis_task_event_outbox` 插入对应的 `TaskEvent`
5. 事务提交后立即认领并发布 Outbox 事件；若 Redis 发布或发布确认失败，由定时 Relay 重试

`AnalysisStageTransaction` 是事务边界，负责 MySQL stage CAS 与 Outbox 插入；
`AnalysisTaskEventOutboxRelay` 只负责认领、发布、失败退避和已发布数据清理。调用方仍只依赖
`AnalysisStageService.transition(...)`，不会看到 Outbox 的内部接口。

AgentPlan、Executor 草稿、CriticState 与最终 AgentState 的 payload checkpoint 只保存 payload 自身的阶段元数据，
不再写 canonical stage，也不会用 payload 的 stage 覆盖 Redis 热缓存中的 canonical stage。对应的可观察阶段必须
随后通过 `transition()` 完成 CAS 和 Outbox 写入。结果复用不再由 `saveResult` 预先推进到
`ANALYSIS_COMPLETED`，因此策略显式允许 `QUEUED -> COMPLETED_REUSED`。若入队阶段的 MySQL 降级直发发生后
数据库已恢复，复用结果也允许从空持久阶段直接进入 `COMPLETED_REUSED`。

非法迁移和 CAS 冲突立即失败，并且不发布事件。CAS 冲突会驱逐可能陈旧的 Redis stage，下一次读取重新
回到 MySQL 真源。MySQL 事务基础设施不可用时保留既有降级语义：事务回滚后记录告警并直接发布非持久事件，
避免状态存储故障阻断已经接收的分析任务。该降级事件不具备 Outbox 的恢复保证。

Relay 使用带过期时间的 claim token，多个实例最多只有一个实例在正常路径上处理同一行。交付语义为
at-least-once：若 Redis 已收到事件、但进程在 `published_at` 确认前退出，租约过期后会再次发布。客户端必须容忍
重复阶段事件。Redis 不可用时 `TaskEventService` 仍先向本机 SSE 订阅者降级发布，但把异常返回 Relay，Outbox 行
继续保留并按 2 秒起步、最长 5 分钟的指数退避重试。已发布行默认保留 7 天后清理；Outbox 单独保存
`media_id`，删除媒体时与 Agent checkpoint 在同一 MySQL 事务中立即清理，避免分析结果在删除后继续保留。

`AnalysisStagePolicy` 只接受可观察的分析生命周期阶段。`ASR`、`TRANSCRIPTION`、
`CONTEXT_COMPLETED`、`CHUNKS_COMPLETED`、`RETRIEVAL`、`REVISION_PENDING` 与
`REVISION_APPLIED` 保持在各自子流程或 telemetry 中，不通过该入口迁移。

## Trade-offs

**收益**：

- 一个接口封装迁移校验、checkpoint 写入和 SSE 发布，调用方不再维护双写细节
- 非法回退（例如 `COMPLETED -> EXECUTOR_STARTED`）可以在发生处立即暴露
- happy path、Critic 定向重试、revision、Replay、复用与毒消息终止路径可以独立测试
- MySQL 条件更新保证两个并发迁移最多只有一个成功，失败方不会发布陈旧 SSE
- 进程在 stage 提交后、SSE 发布前退出时，事件仍能从 Outbox 恢复
- payload 保存不再绕过状态机推进 canonical stage

**代价**：

- Outbox 是 at-least-once 而不是 exactly-once，发布确认窗口可能产生重复 SSE
- 新增一张表、一个轮询任务和保留期清理，需要监控 pending 数量、最大积压时间与重试次数
- MySQL 完全不可用时的直发降级仍可能造成事件与持久阶段暂时不一致
- `saveFailure` 仍保留执行失败现场的 checkpoint 语义；最终是否进入 `RETRYING`、`BUDGET_EXHAUSTED` 或
  `DEAD_LETTERED` 由消费编排通过统一迁移入口决定

## 边界与共存

**覆盖范围**：AnalysisTask 从入队、消费、AgentLoop、完成、失败、Replay 到死信的可观察阶段。

**不覆盖**：

- ASR/OCR/转录子流程进度
- AgentPlan、Executor 草稿、CriticState 与最终 AgentState 等 payload 内容本身
- Redis Pub/Sub 到浏览器之间的 exactly-once 投递；SSE 断线后的状态查询仍是最终对齐手段

**恢复与修订语义**：

- MQ 重试通过 `FAILED -> RETRYING -> CONSUMING` 恢复旧 AnalysisTask
- MySQL 最终结果命中时允许 `CONSUMING -> COMPLETED`，不依赖 Redis `completedKey` 仍然存在
- 人工 Replay 通过 `DEAD_LETTERED/FAILED -> MANUAL_REPLAY -> CONSUMING` 启动新一次执行
- revision 应用后只保存修订后的 AgentPlan payload，canonical stage 可从空状态直接进入 `CONSUMING`
- 结果复用先保存 AgentState payload，再通过 `QUEUED -> COMPLETED_REUSED` 原子写阶段与 Outbox

## 相关代码

- `service/AnalysisStageService` - 分析阶段迁移入口
- `service/AnalysisStageTransaction` - stage CAS 与 Outbox 插入的事务边界
- `service/AnalysisTaskEventOutboxRelay` - 事件认领、发布、退避重试与清理
- `service/AnalysisStagePolicy` - 合法迁移规则
- `consumer/VideoAnalysisConsumer` - 消费、重试、死信与完成路径
- `service/AgentLoopService` - Planner、Executor、Critic 与定向重试路径
- `service/AgentCheckpointService` - payload 与阶段 checkpoint 持久化
- `service/TaskEventService` - SSE 事件发布
- `repository/AnalysisTaskEventOutboxRepository` - Outbox 序列化与持久化协议
