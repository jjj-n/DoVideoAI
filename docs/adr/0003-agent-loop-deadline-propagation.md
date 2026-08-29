# AgentLoop Deadline 传播机制

## Context

当前 `checkBudget()` 在每个 LLM 调用**之后**检查 budget（tokens、cost、duration），导致用户必须等当前 LLM 调用跑完才知道超时——一次 LLM 调用可能耗时数十秒，用户等了接近 deadline 才被告知失败，体验差且浪费 token 与 server 端资源。

需要一个机制让每个 LLM 调用**之前**就知道剩余时间，能快速失败。

## Decision

AgentLoop 有一个全局 deadline，以"线程级全局约束"的形式传播：

- **传播方式**：通过 `AgentExecutionBudget` 的 ThreadLocal scope 在 `AgentLoopService.run()` 入口设置 deadline，并在 try-with-resources 退出时恢复或清理；AgentLoop 内的 LLM 调用可以读取剩余时间。
- **两层超时**：外层 Future 用 `min(模型默认超时, Agent 剩余预算)` 动态等待，`OpenAiChatModel` 自身的固定 timeout 作为底层兜底（具体值见配置）。
- **模型客户端**：`OpenAiChatModel` 关闭 SDK 自动重试，由 `DeepSeekUtils.chat()` 统一完成失败分类与有限重试，避免两层重试叠加。
- **线程池**：LLM 调用走自定义 Spring `ThreadPoolTaskExecutor`（`modelCallExecutor` bean），与其他线程池（MinIO、Qdrant、SSE 推送、MQ 消费等）隔离。
- **失败语义**：超时后抛 `BudgetExceededException`，Agent 终止，不保存部分结果，状态进入 `BUDGET_EXHAUSTED`。

## Trade-offs

**收益**：

- 用户在 deadline 到达时立即看到失败，不等当前 LLM 调用跑完
- Token 与 server 端资源（虽然底层 HTTP 仍在跑）不浪费在已经没用的 AgentLoop 后续阶段
- 与现有 `checkBudget()` 异常格式一致，`VideoAnalysisConsumer` 无需改动

**代价**：

- **取消不等于远端终止**：Agent 层超时后会对工作 Future 调用 `cancel(true)`，但模型 SDK 或底层网络调用不一定响应 interrupt；用户已经看到失败时，连接仍可能占用到模型 timeout。
- **连接池占用**：孤儿 HTTP 调用占用连接池。通过 LLM 专用线程池提供线程级隔离，避免影响其他服务；连接池由模型 SDK 的底层实现管理，当前不额外干预。

**考虑过的替代方案**：

1. **动态超时的实现方式**
   - **每次创建新 `ChatModel` 实例**，timeout 动态设置——精确控制，但每次创建实例成本高，且 LangChain4j builder 不支持复用底层 HTTP client。
   - **用支持 `Call.cancel()` 的 HTTP client 真正中断调用**——可彻底解决"假超时"，但需要绕过 LangChain4j 自行管理 HTTP client，侵入性大。未来如果并发高到连接池不够用，可作为后续优化方向。

2. **线程池选型**
   - **`ThreadPoolTaskExecutor`（选）**：可配置 corePoolSize / maxPoolSize / queueCapacity / 拒绝策略；生命周期由 Spring 管理。
   - **`ForkJoinPool.commonPool()`（不选）**：全局共享，LLM 长任务会阻塞其他 ForkJoin 任务（如 stream parallel）。
   - **`Executors.newFixedThreadPool()`（不选）**：不可配置队列策略和拒绝策略。

3. **拒绝策略**
   - **`AbortPolicy`（选）**：队列满 + 线程达 max 时抛 `RejectedExecutionException`，失败链路清晰，由 `DeepSeekUtils.chat()` 重试逻辑和 `VideoAnalysisConsumer` 的 transient failure 处理兜底，不丢任务。
   - **`CallerRunsPolicy`（不选）**：会让主线程（AgentLoop 线程）自己执行 LLM 调用，破坏外层动态超时控制。

## 边界与共存

**覆盖范围**：只有 AgentLoop 内的 LLM 调用受 deadline 控制（Planner、Executor、Critic 及其修复路径）。

**不覆盖**：AgentLoop 外的 LLM 调用（Mode 分类、VideoChunk 摘要、检索意图提取）不读取 deadline，用默认 HTTP timeout。理由是这些是预处理阶段，有自己的超时控制，不应受 AgentLoop deadline 约束。

**与 `checkBudget()` 共存**：

- `checkBudget()` 在 LLM 调用**之后**检查 tokens 和 cost，负责资源预算
- deadline 机制在 LLM 调用**之前**检查剩余时间，负责快速失败
- 两者职责不重叠：deadline 解决"体验问题"，`checkBudget()` 解决"token 成本失控"

**资源隔离现状**：

- 线程级隔离通过 `modelCallExecutor` bean 实现（其他服务不共享该线程池）
- 模型 SDK 的连接池由底层实现管理；当前阶段只显式保证线程池隔离

## 相关代码

- `service/AgentExecutionBudget` — ThreadLocal deadline scope 与剩余预算计算
- `service/AgentLoopService.run()` — 入口设置/清理 deadline
- `utils/DeepSeekUtils.chat()` — 读取剩余 deadline 做动态超时
- `config/ThreadPoolConfig` — `modelCallExecutor` bean 定义
- `consumer/VideoAnalysisConsumer` — 捕获 `BudgetExceededException`，状态进入 `BUDGET_EXHAUSTED`

## 相关配置

`application.properties` 的相关 key（具体值在配置文件中，不在 ADR 中固化）：

- `agent.budget.max-duration-ms` — AgentLoop 全局 deadline
- `agent.budget.max-rounds` — AgentLoop 最大轮次（与 deadline 共同控制 Agent 终止）
- `ai.deepseek.timeout-seconds` — 底层 HTTP client 兜底 timeout
- `ai.deepseek.input-price-per-million` / `ai.deepseek.output-price-per-million` — token 成本估算（供 `checkBudget()` 使用）
