# Codex Debug Recipes

针对 DoVideoAI 的高 ROI debug prompt 模板。每条都配了：触发场景、为什么这条有效、验证 Codex 结论真假的方式。

## 通用使用守则

1. **小范围定向**：一次只让 Codex 看一个 service 或一条状态机路径。范围越大，召回越高但精确度越低，幻觉率上升。
2. **先复现，再修复**：拒绝"只改代码不写测试"的修复。让它优先在现有 `server/src/test/` 下建立最小复现。
3. **要根因，不要症状**：要求它解释"为什么是 bug"。说不清的大概率是误报。
4. **保留降级**：Qdrant/ASR/OCR 故障路径是核心特性，不要被简化掉。
5. **盯术语**：`CONTEXT.md` 里 EvidenceHit ≠ CitedEvidence、VideoSegment ≠ VideoChunk、Recovery ≠ Replay。Codex 如果混淆要立即喊停。
6. **三连问验证**：每个 bug 让它回答——(a) 最小复现步骤 (b) 影响哪些其他路径 (c) 不修会怎样（P0/P1/P2）。

---

## P0 级 Recipes（后果最严重）

### R1. Checkpoint 双写一致性扫描

**Prompt**

```
阅读 server/src/main/java/com/example/server/service/AgentCheckpointService.java
以及 VideoAnalysisConsumer 中所有调用它的位置。

找出所有可能导致 "MySQL 写入成功但 Redis 缓存未更新" 或
"Redis 缓存被更新但 MySQL 真源未持久化" 的代码路径，
特别关注：
- 异常分支（Redis 写失败、MySQL 写失败、网络抖动）
- 并发写入同一个 mediaId 的 Checkpoint
- TTL 过期但 MySQL 真源未同步的窗口
- 重启后 Recovery 读到半写入状态

对每个发现给出：根因 / 触发条件 / 修复方案 / 影响面。
不要直接改代码，先输出报告。
```

**为什么这条有效**：Checkpoint 是 Recovery 的真源，一旦双写不一致，重启后可能跳过已完成阶段或重复执行，造成 AI 成本浪费或结果错乱。

**验证方式**：让它给出"如何用最小代码片段复现这个不一致"——比如手动 throw 一次 RedisException，看 Checkpoint 的最终状态。

---

### R2. TaskStage 状态机非法跳转

**Prompt**

```
列出 `dto/TaskStage.java` 中 TaskStage 枚举的所有阶段（当前应有 28 个），
画出合法转移图（用 mermaid stateDiagram）。

然后扫描整个 server 模块，找出所有 stage 转移的代码点。
重点检查：
1. 是否存在合法转移图之外的跳转
2. FAILED / DEAD_LETTERED 路径是否释放了：线程池、FFmpeg 子进程、
   MinIO 临时分片、Redis 锁、SSE 连接
3. 从某个 stage 恢复时，前置资源（VideoContext / VideoChunks）
   是否可能已丢失但代码假设它还在
4. CONSUMING -> VIDEO_CONTEXT -> CONTEXT_COMPLETED 这条主线，
   如果 FFmpeg 进程崩了，stage 会停在哪里？

输出：状态转移图 + 所有可疑转移点清单（带文件:行号）。
```

**为什么这条有效**：28 个状态 × 多个失败分支 = 上百条转移路径，人工 review 极易漏；状态机工具能机械验证。

**验证方式**：让它把"可疑转移点"按严重程度排序，并挑 TOP 3 给出最小复现。

---

### R3. Deduplication + Quota race condition

**Prompt**

```
审计 server/src/main/java/com/example/server/service/AnalysisDispatchService.java
中的 activeKey SETNX 流程，以及 RRateLimiter 的使用。

找出在以下场景下，去重或限流可能失效的边界情况：
1. Replay（FailedAnalysisTaskService 手动重新投递）时，
   原来的 activeKey 是否还在？会不会被新的提交命中？
2. 同一用户在 6 小时 activeKey TTL 内连点同一个目标，
   会不会绕过 dedup 直接消耗 quota？
3. RRateLimiter 的令牌桶在 Redis 重启后会怎样？
   是清零还是恢复？
4. 客户端取消请求时，已经扣掉的令牌会还吗？

对每个发现给出：触发序列（用编号步骤）+ 修复建议。
```

**为什么这条有效**：dedup 和 quota 是直接挡 AI 成本的，失效一次就是真金白银。

**验证方式**：让它用并发单元测试或集成测试给出复现序列。

---

### R4. AgentLoop 两轮循环边界

**Prompt**

```
阅读 AgentLoopService 和 EvidenceVerificationService。

验证以下不变量是否真的成立：
1. AgentLoop 最多运行两轮（第 1 轮初始 + 第 2 轮定向重试）
2. 第 2 轮 Critic 仍然不通过时，是否会进入正确的 fallback
   （降级输出 / 标记为低置信度 / 直接 FAILED）
3. 定向检索（基于 Critic.missingRequirements）返回空时，
   代码是否会短路，还是继续走 Executor 浪费 token
4. Round 计数器在并发 / 异常 / 重试场景下是否会被错误地重置

找出违反不变量的代码路径，按严重程度排序。
```

**为什么这条有效**：成本护栏失效一次，单次分析可能跑 5 轮，token 成本爆炸。

**验证方式**：让它构造一个 UserGoal，使得 Critic 永远返回 retry——看代码会不会被绕过。

---

## P1 级 Recipes（功能性问题）

### R5. ASR/OCR 并行分支异常吞没

**Prompt**

```
阅读 VideoTranscriptionService、SegmentedTranscriptionService、
AudioExportService。

找出以下场景的处理代码：
1. ASR 分支抛异常时，OCR 分支的结果是否还能正确合并
2. OCR 分支失败时，是否真的能"单路失败保留另一条"
3. 有界线程池（看 config/ 里的 Bean 配置）的拒绝策略是什么？
   队列满了会怎样？
4. FFmpeg 子进程在 JVM 崩溃时是否会变成孤儿进程？
   process.destroyForcibly() 的调用时机对吗？
5. pHash 去重的边界：连续两个几乎相同的关键帧会不会都保留？

输出：异常路径清单 + 资源泄漏风险点。
```

---

### R6. 混合检索降级路径

**Prompt**

```
阅读 VideoEvidenceRetrievalService.rank() 和 QdrantVectorStore。

验证：
1. Qdrant 不可用时，代码真的能退到"本地关键词 + 已有向量排序"吗？
   退化路径有没有在日志里区分出来？
2. embedding 服务（BGE-M3）超时时，是阻塞还是返回空？
3. TOP_K=3（内部 chunks）和 MAX_USER_HITS=8（用户面）的转换逻辑，
   会不会出现 8 个 hit 但底层只有 3 个 chunk 的不一致？
4. 降级路径的"低置信度"标记是否传到了前端？

特别检查：被简化掉的降级路径——之前 PR 是否有人为了"修 bug"
把 fallback 删了？
```

---

### R7. 分片上传合并的时序

**Prompt**

```
审计 ChunkUploadService 和 MediaIngestService。

找出：
1. Redis Set 记录已上传分片，TTL 1 天。如果用户上传到一半停 25 小时
   再继续，会发生什么？
2. 410 片上限（5MB * 410 ≈ 2GB）的校验在哪里？是否可绕过？
3. 合并后的 content hash 计算与 dedup 的 SETNX 时序——
   会不会出现 hash 算完但 dedup 还没设置，另一个并发请求同时算 hash？
4. 客户端调用 uploadedChunks() 拿缺失索引时，Redis Set 的读取
   是否原子？

对每个发现给出复现序列。
```

---

### R8. Recovery vs Replay 语义错位

**Prompt**

```
阅读 FailedAnalysisTaskService 和相关 Replay 入口。

Recovery 的定义：从 Checkpoint 恢复同一逻辑任务的当前执行，
跳过已完成阶段。
Replay 的定义：在相同 `mediaId + goal + mode` 逻辑身份下启动一次全新执行；
清除旧的 plan / Critic / result payload，保留可复用的 VideoContext / VideoChunk，
不创建新的 taskId 或 runId。

找出代码里可能违反这两个语义的地方：
1. Replay 是否清除了旧 Agent payload，同时保留了内容级上下文和分块？
2. Replay 重新投递的 mediaId、goal、mode 与原失败记录是否一致？
3. canonical stage 是否从旧终态进入 MANUAL_REPLAY，而不是伪造新的任务身份？
4. Replay 后原 failed_task 表的记录状态如何变化？

违反语义会导致：旧结果被错误恢复、可复用上下文被重复构建，或失败任务无法重新执行。
```

---

## P2 级 Recipes（用户体验 / 边缘）

### R9. SSE 长连接生命周期

**Prompt**

```
阅读 AnalysisStatusService、TaskEventService。

检查：
1. 客户端断开重连后，错过的事件如何对齐？
   是从最新阶段开始推送，还是有 event log 重放？
2. SSE 连接的超时配置在哪？长时间没有事件会主动断开吗？
3. 多个浏览器标签同时订阅同一个 `mediaId + goal + mode` 逻辑任务，会不会互相影响？
4. 任务完成后 SSE 是否真的关闭，还是等客户端超时？
```

---

### R10. AI 客户端超时与重试

**Prompt**

```
阅读 AiService 和 config/ 里的 AI 客户端 Bean。

验证：
1. LLM_TIMEOUT_SECONDS=300 在哪些调用上生效？是统一的还是按场景配置？
2. 指数退避覆盖了哪些失败码？哪些码不应该重试
   （例如 400 BadRequest、内容审核拒绝）？
3. DeepSeek 返回 Model disabled 时，代码退到哪里？
   会无限重试吗？
4. LangChain4j 的重试和 Spring Retry 是否冲突？
```

---

## 通用排查模板（自己拼）

当遇到具体 bug 时，按这个模板填空：

```
【上下文】
- 报错现象：[粘贴 stack trace / 用户描述]
- 复现步骤：[1. 2. 3.]
- 预期行为：[...]
- 实际行为：[...]

【任务】
1. 先不修代码，定位根因。可能的话给出最小复现测试。
2. 列出所有候选根因，按可能性排序，说明推理过程。
3. 标注每个候选根因的"为什么是 / 为什么不是"。
4. 选出 TOP 1 根因，给出修复方案：
   - 改动文件 + 行号
   - 为什么这样改
   - 是否会影响降级路径
   - 是否需要数据迁移 / 配置变更
5. 修复后给出验证步骤（测试用例或手动复现）。

【约束】
- 不要简化降级路径
- 不要合并 CONTEXT.md 里的术语
- 不要重命名公共 API
```

---

## 什么时候停止信任 Codex

出现以下信号，立即停下手核对：

- 它说"这条 bug 很明显"但给不出复现步骤
- 它建议把 Qdrant / ASR / OCR 的 fallback 删掉
- 它把 EvidenceHit 和 CitedEvidence 当同义词
- 它说"加一个全局锁就能解决"——这通常是降级方案
- 它改了 6 个以上的文件——通常意味着它没找到根因，在症状层打补丁

---

## 维护

发现新的高 ROI prompt 时，加到对应 P 等级下面。每条 recipe 至少包含：**Prompt 文本 / 为什么有效 / 验证方式**。
