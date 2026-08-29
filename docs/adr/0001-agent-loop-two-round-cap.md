# AgentLoop 限制为最多 2 轮

AgentLoop 每个 AnalysisTask 最多运行 Planner -> Executor -> Critic 2 轮。第 1 轮是初始执行；第 2 轮是定向重试，由 Critic 的 `requiredTimestamps` 和 `missingRequirements` 驱动一次定向的 EvidenceHit 再检索。限制为 2 轮（而不是 1 轮或无上限）是一次明确的成本/延迟 vs 质量的权衡：1 轮没有自我修正能力；无上限会让 token 成倍增长，并拉长长视频的延迟（每轮可能耗时数分钟）。2 轮既能覆盖最常见的失败（某个时间范围缺证据），又不会让 loop 无限打开。该上限在 `AgentLoopService` 中强制执行，并被下游代码（telemetry、Checkpoint 结构、SSE 阶段流转）所假设。
