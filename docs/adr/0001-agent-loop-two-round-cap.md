# AgentLoop 限制为最多 2 轮

Status: Accepted

Date: 2026-08-25

## Context

AgentLoop 通过 Planner、Executor 和 Critic 生成并校验长视频分析结果。单轮执行无法根据 Critic 发现的目标覆盖或证据缺口自我修正；无上限重试则会让 Token 成本和长视频处理延迟持续增长，而且无法给调用方一个确定的终止时间。

系统需要在结果质量、执行成本和可预测延迟之间设置明确边界，并定义达到边界后仍未通过 Critic 校验时的返回语义。

## Decision

每个 AnalysisTask 最多执行两轮 AgentLoop：

1. 第一轮执行初始的 Planner -> Executor -> Critic。
2. 第一轮未通过时，根据 Critic 的 `requiredTimestamps` 和 `missingRequirements` 定向补充 EvidenceHit，并按 Critic 反馈修订计划。
3. 第二轮执行修订后的 Executor -> Critic；无论是否通过，都不再启动第三轮。

`AgentLoopService` 将可配置的 `agent.budget.max-rounds` 限制在 1 到 2，默认值为 2。两轮上限是实现约束，不允许仅通过配置继续放大。

第二轮仍未通过 Critic 时，系统保留最新一轮的 `AnalysisResult` 和 `CriticResult`，保存结果 checkpoint，并发布 `ANALYSIS_COMPLETED_WITH_WARNINGS`。消费端随后通过 `TaskStatus.completed(AgentState)` 返回完成状态，在结果正文和消息中加入“部分结论未通过 Critic 校验”的 warning，提醒用户结合时间戳证据人工核验。这是带警告的部分结果，不是 Critic 已通过的完整结果。

若 AgentLoop 在完成两轮前耗尽时间、Token 或成本预算，则按预算失败处理，不返回部分结果；deadline 和预算语义见 ADR 0003。

## Consequences / Trade-offs

**收益：**

- 第一轮失败后仍有一次基于 Critic 反馈的定向修正机会
- 最坏执行轮数固定，Token 成本与整体延迟更可预测
- 第二轮仍未通过时保留可用结果，并显式暴露质量警告，而不是静默伪装成 Critic 已通过
- round、Critic 和结果 checkpoint 可以据此判断是否为可直接恢复的终态

**代价：**

- 两轮后仍可能存在证据缺口或目标覆盖不足，需要用户人工核验
- 对复杂任务而言，两轮未必足以收敛，但继续自动重试的边际收益无法抵消成本和延迟风险
- 下游状态机、SSE、checkpoint 与结果渲染都依赖“两轮即终止”和 warning 语义，修改上限时必须同步审查这些路径

## Validation

- `AgentLoopServiceTest#rejectsRoundBudgetAboveTheTwoRoundAdrLimit` 验证配置不能突破两轮上限。
- `AnalysisStagePolicyTest#acceptsRevisionReuseAndFinalWarningPaths` 验证 Critic 重试路径可以进入 `ANALYSIS_COMPLETED_WITH_WARNINGS`。
- 当前测试尚未直接执行“两轮 Critic 都失败”并断言 `TaskStatus.completed(AgentState)` 的 warning 内容；修改终止或结果渲染逻辑时应补齐这一行为测试。

## Related code

- `service/AgentLoopService` - 两轮循环、定向补充证据、最终 Critic 结果和 warning 阶段
- `dto/TaskStatus` - 将未通过的 Critic 结果渲染为带 warning 的完成响应
- `consumer/VideoAnalysisConsumer` - 加载最终 checkpoint 并发布完成状态
- `service/AgentCheckpointService` - 保存最新结果与 Critic checkpoint
- `service/AnalysisStagePolicy` - `ANALYSIS_COMPLETED_WITH_WARNINGS` 的合法迁移
- `service/AgentLoopServiceTest` - 两轮配置上限测试
- `service/AnalysisStagePolicyTest` - 最终 warning 阶段迁移测试
