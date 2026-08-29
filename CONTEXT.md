# DoVideoAI Canonical Domain Language

DoVideoAI 把长视频转化为可检索、可追溯、可继续追问的结构化知识。下面的领域模型为代码、文档和评审提供 canonical 命名；代码历史中存在若干重载词，讨论系统行为时以这里的定义为准。

## Evidence and retrieval

### EvidenceHit

混合检索返回的命中结果。它是一个有时间边界（`startMs` / `endMs`）的片段，附带 `source`（transcript 或 OCR）、命中的 `snippet`、完整 `transcript` 文本以及 `ocrTexts`。这是引用前的产物，回答的是“找到了什么”，还不是“最终引用了什么”。

避免只使用：证据、hit、match、retrieval result。

### CitedEvidence

最终 `AnalysisResult` 中的一条记录，把一条结论绑定到可核验的时间戳：`timestampMs`、`source`、`content`、`claim`。`claim` 表示“这条结论由这个时间戳的内容支撑”。这是引用后的产物，回答的是“答案依赖什么”。

避免只使用：证据、proof、citation、evidence frame。

### EvidenceVerification

Critic 检查每条 `CitedEvidence` 是否能追溯到所声明时间戳上的真实 `EvidenceHit`。没有可核验 `CitedEvidence` 的结论会被拒绝。

避免只使用：证据校验、grounding check。

## Time-aligned video objects

### TranscriptSegment

ASR 的原始文本输出：`startMs`、`endMs`、`text`。它是 `VideoSegment` 之前的音频分支产物。

避免只使用：字幕、transcript、utterance。

### VideoContext

视频的多模态产物：`source`、`segments`（`VideoSegment` 列表）、`userGoal`。其中 `source` 和 `segments` 是视频固有的、可跨用户复用的；`userGoal` 是分析运行时注入的，持久化时清空，加载后由 `AnalysisTask` 重新注入。Checkpoint 按 `mediaId` 存储，使不同 goal 的 `AnalysisTask` 能复用同一视频的多模态产物。

避免只使用：视频上下文、context。

### VideoSegment

统一的 60 秒多模态切片：`startMs`、`endMs`、`transcript`、`ocrTexts`、`evidenceFrames`。它把 ASR、OCR 和关键帧合并为一个时间对齐单元，是 `VideoContext` 内部的 canonical domain object。

避免只使用：片段、segment、clip。

### VideoChunk

5 分钟的检索索引单元：`segmentSummary`、`keywords`、`embedding`、`rawSegments`（一组 `VideoSegment`）。这是 Qdrant 实际索引的对象，一个 `VideoChunk` 通常包含约 5 个 `VideoSegment`。

避免只使用：块、chunk、block。

## Tasks and messages

### UserGoal

用户输入的自然语言分析意图，例如“总结这堂课的核心概念”。它作为 `TaskMessage` 上的 `userGoal` 传递。同一个视频可以用不同 `UserGoal` 反复分析。

避免只使用：分析目标、prompt、query、request。

### AnalysisTask

一次分析的执行运行。它由 `TaskMessage` 触发，拥有 `TaskStage` 生命周期，通过 `Checkpoint` 持久化状态，最终产出 `AnalysisResult`。Revision（`REVISE_ANALYSIS`）会启动新的 `AnalysisTask`，并复用已有 `VideoContext`。

避免只使用：任务、job、work item。

### TaskMessage

触发 `AnalysisTask` 的 MQ payload：`mediaId`、`action`（`START_ANALYSIS` 或 `REVISE_ANALYSIS`）、`contentHash`、`userGoal`、`mode`。它是 RocketMQ 上的传输格式。

避免只使用：任务消息、MQ message、event。

### TaskStage

共享的进度标签枚举，共有 28 个值，但不是所有值都属于同一套状态机。

`AnalysisStagePolicy` 管理其中 21 个 canonical `AnalysisTask` 生命周期阶段。主成功路径是 `QUEUED` -> `CONSUMING` -> `VIDEO_CONTEXT` -> `AGENT_LOOP` -> `PLAN_COMPLETED` -> `EXECUTOR_STARTED` -> `EXECUTOR_COMPLETED` -> `CRITIC_STARTED` -> `CRITIC_PASSED` -> `ANALYSIS_COMPLETED` -> `COMPLETED`。Critic 未通过时可进入 `CRITIC_RETRY_REQUIRED`，经 `EVIDENCE_REFRESHED` 或重新规划后再执行；第二轮仍未通过时进入 `ANALYSIS_COMPLETED_WITH_WARNINGS`。其他 lifecycle 状态包括 `RETRYING`、`FAILED`、`BUDGET_EXHAUSTED`、`DEAD_LETTERED`、`MANUAL_REPLAY`、`COMPLETED_REUSED` 和 `DISPATCH_FAILED`。

其余 7 个值不通过 `AnalysisStageService.transition(...)` 迁移：`CONTEXT_COMPLETED` 和 `CHUNKS_COMPLETED` 是 VideoContext/VideoChunk checkpoint 的 payload 阶段元数据；`REVISION_PENDING` 和 `REVISION_APPLIED` 属于 revision checkpoint；`RETRIEVAL`、`TRANSCRIPTION` 和 `ASR` 是独立子流程或 telemetry 的进度标记。

避免把 `TaskStage` 的全部枚举值画成一条 AnalysisTask 生命周期，也不要把 checkpoint payload 阶段当成 canonical observable stage。

## Agent roles

### VideoAgent

整个产品，即把视频转化为结构化知识的系统。指代推理组件时使用 `AgentLoop`。

### AgentLoop

推理组件：Planner -> Executor -> Critic，最多 2 轮。它接收 `UserGoal` 和检索到的 `EvidenceHit`，产出带 `CitedEvidence` 的 `AnalysisResult`。

### Planner

把 `UserGoal` 拆成可执行任务的角色，产出 `AgentPlan`（`understoodGoal` 和 `tasks`）。

### Executor

按 `AgentPlan` 产出带 `CitedEvidence` 的 `AnalysisResult`，输出结构化结论、证据、建议以及模式特定的 Sections。

### Critic

校验目标覆盖、结构完整性和时间戳证据的角色，产出 `CriticResult`（`passed`、`feedback`、`missingRequirements`、`unsupportedClaims`、`requiredTimestamps`）。校验失败时，它指导下一轮定向检索缺失证据。

### Round

一次 Planner -> Executor -> Critic 的完整流程。`AgentLoop` 最多运行 2 轮：第 1 轮初始执行，第 2 轮根据 Critic 反馈定向重试。

### AgentState

`AgentLoop` 的状态：`goal`、`plan`、`result`、`critique`、`round`。它通过 `Checkpoint` 持久化，使 `AgentLoop` 能在崩溃后恢复。

## Recovery

### Checkpoint

`AnalysisTask` 的持久化可恢复状态。MySQL 是真源，Redis 是热缓存。它存储 `VideoContext`、`VideoChunk`、`AgentPlan`、Critic 状态和最终 `AnalysisResult`，使 Recovery 能跳过已完成阶段。

### Recovery

服务重启时从 `Checkpoint` 恢复原 `AnalysisTask`，并跳过已经完成的阶段。例如，已有 `VideoContext` 时不重新执行 `VIDEO_CONTEXT`。

### Replay

通过管理 API 手动重新投递 `FailedAnalysisTask`。Replay 启动新的 `AnalysisTask`，不恢复旧任务；失败任务进入 `DEAD_LETTERED` 并写入 `failed_task` 表。

## Modes and outputs

### AnalysisMode

决定 `AgentLoop` 如何运行的 profile。四个值是 `GENERAL`、`LEARNING`、`REVIEW`、`CREATION`。它可以由用户指定，也可以由 `ModeClassification` 从 `UserGoal` 推断，并决定 Executor 应产出哪些 Section keys。

### ModeClassification

用户未指定 `AnalysisMode` 时，由 LLM 从 `UserGoal` 推断模式的动作。它产出 `ModeClassification`，`ModeRouter` 据此选择 `ModeProfile`。

### ModeProfile

某个 `AnalysisMode` 的配置：prompts、期望的 Section keys 和模式特定的 Executor 行为，在 `ModeRegistry` 中注册。

### AnalysisResult

`AnalysisTask` 的结构化输出：`title`、`conclusions`、`evidence`（`CitedEvidence` 列表）、`suggestions`、`sections`。Executor 产出它，Critic 校验它。

## Runtime mechanisms

### Deduplication

`AnalysisDispatchService` 中的 `activeKey` 机制：在 `contentHash + goalDigest` 上执行 Redis `SETNX`，TTL 为 6 小时。在 `AnalysisTask` 仍 active 时，它阻止相同 media、goal、mode 组合的重复提交，命中时返回 `SubmissionResult.DUPLICATE`。Deduplication 拦截相同请求，Quota 限制总速率。

### Quota

基于 Redisson `RRateLimiter` 的令牌桶限流：每用户 5 请求/分钟，全局 30 请求/分钟。桶空时返回 `SubmissionResult.RATE_LIMITED`。Quota 不关心请求是否相同，只关心它是否消耗吞吐配额。

### Hybrid retrieval

`VideoEvidenceRetrievalService.rank()` 联合语义向量召回（BGE-M3 embeddings over `VideoChunk`）、关键词匹配和 OCR 通道。主分析返回 `TOP_K=3` 个 Chunks 并展开为 `VideoSegment`；用户面搜索上限为 `MAX_USER_HITS=8`。Qdrant 或 embedding 服务不可用时，降级到本地关键词和已有向量排序，不阻断主分析链路。

### Chunked upload

按 5 MB 分片上传。Redis Set 跟踪每个 `uploadId` 已上传的 chunk 索引，TTL 为 1 天，最多 410 片；MinIO 存储合并后的视频。`uploadedChunks()` 返回已上传索引以支持断点续传，合并后视频的 content hash 进入 Deduplication。

### AgentExecutionBudget

`AgentLoop` 的执行预算包含四个维度：时间（`max-duration-ms`）、轮次（`max-rounds`，最多 2 轮）、token（`max-estimated-tokens`）和成本（`max-estimated-cost`）。轮次耗尽时返回带 warnings 的部分结果（`ANALYSIS_COMPLETED_WITH_WARNINGS`）；时间、token 或成本耗尽时抛出 `BudgetExceededException`，进入 `BUDGET_EXHAUSTED`，不保存部分结果。它约束已经进入执行阶段的 `AnalysisTask`，不同于提交阶段的 Quota 和 Deduplication。

### LLMCallIsolation

LLM 调用提交到专用线程池，与共享线程池隔离，避免长时间模型调用阻塞 SSE 推送、MQ 消费、MinIO 读写等任务。它是 `AgentExecutionBudget` deadline 传播的配套设计：deadline 超时后，SDK 底层网络调用未必立即结束，遗留调用的资源占用被限制在专用线程池中，不扩散到其他服务。底层连接由模型 SDK 管理，当前不额外干预。
