# AGENTS.md

给 Codex（以及任何 AI coding agent）的项目入口。读完这一份就能开始干活。

## 这个项目是什么

DoVideoAI：把长视频转化为可检索、可追溯、可继续追问的结构化知识的 Video Agent。

- 后端：Java 21、Spring Boot 3.5.9、Undertow、MyBatis-Plus
- 异步与缓存：RocketMQ 4.9、Redis 7、Redisson（锁 / 限流 / 去重）
- 存储：MySQL 8、MinIO（视频对象）、Qdrant（向量）
- AI：LangChain4j、DeepSeek、TeleSpeechASR、BGE-M3、FFmpeg、Tesseract
- 前端：Vue 3 + Vite + SSE

## 必读上下文（按顺序）

1. `CONTEXT.md` — 领域语言（EvidenceHit / CitedEvidence / VideoSegment / VideoChunk / AgentLoop / Checkpoint …）。**所有命名以这里为准**，代码里有重载，不要被误导。
2. `README.md` — 系统流程 mermaid、本地运行步骤。
3. `docs/adr/0001-agent-loop-two-round-cap.md` — AgentLoop 两轮上限的决策。
4. `docs/agents/domain.md`、`docs/agents/issue-tracker.md`、`docs/agents/triage-labels.md` — 工作流约定。
5. `docs/design/module-audit.md` — 已有模块审计。

## 项目布局

```
server/src/main/java/com/example/server/
├── consumer/      # RocketMQ 消费入口（VideoAnalysisConsumer）
├── controller/    # REST + SSE
├── service/       # 25+ 个核心服务（见下）
├── service/mode/  # AnalysisMode profile
├── entity/        # JPA/MyBatis 实体
├── mapper/        # MyBatis
├── repository/    # Spring Data
├── config/        # Bean 配置（线程池、Redis、MQ、AI 客户端）
├── common/        # 公共枚举/常量
├── exception/     # 业务异常
└── utils/
```

## 高风险区域（debug 时优先看这里）

按"出问题的概率 × 后果严重程度"排序：

| 优先级 | 区域                        | 关键文件                                                                           | 容易踩的坑                                                                          |
| ------ | --------------------------- | ---------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| P0     | **Checkpoint 双写一致**     | `AgentCheckpointService`                                                           | MySQL 真源 vs Redis 热缓存不一致；恢复时读到半写入状态                              |
| P0     | **TaskStage 状态机**        | `dto/TaskStage.java`、`VideoAnalysisConsumer`                                      | 26 个阶段之间的非法跳转；FAILED/DEAD_LETTERED 路径资源未释放                        |
| P0     | **去重 + 限流**             | `AnalysisDispatchService`                                                          | `SETNX` + TTL 的 race；Redisson `RRateLimiter` 配置错误；Replay 时 dedup key 误命中 |
| P0     | **AgentLoop 两轮循环**      | `AgentLoopService`、`EvidenceVerificationService`                                  | Critic 反馈循环超两轮；定向检索返回空时未短路                                       |
| P1     | **ASR/OCR 并行分支**        | `VideoTranscriptionService`、`SegmentedTranscriptionService`、`AudioExportService` | 有界线程池异常被吞；FFmpeg 子进程未 destroy；pHash 去重边界                         |
| P1     | **混合检索降级**            | `VideoEvidenceRetrievalService`、`QdrantVectorStore`                               | Qdrant 不可用时降级路径；embedding 服务超时；TOP_K=3 vs MAX_USER_HITS=8 混用        |
| P1     | **分片上传 + 合并**         | `ChunkUploadService`、`MediaIngestService`                                         | Redis Set TTL 1 天的边界；411 片上限；合并后 hash 计算与 dedup 的时序               |
| P1     | **Recovery vs Replay 语义** | `FailedAnalysisTaskService`                                                        | Replay 应启动新任务而非恢复旧任务——容易写反                                         |
| P2     | **SSE 推送**                | `AnalysisStatusService`、`TaskEventService`                                        | 长连接断开后阶段丢失；客户端重连后状态对齐                                          |
| P2     | **AI 客户端超时/重试**      | `AiService`                                                                        | `LLM_TIMEOUT_SECONDS=300` 默认值的副作用；指数退避未覆盖的失败码                    |

## 构建 / 运行

```bash
# 后端
cd server
./mvnw compile           # 仅编译
./mvnw spring-boot:run   # 跑起来（需要先 docker compose up -d）

# 前端
cd client
npm install && npm run dev
```

**注意：测试目录已在 `server/src/test/java/com/example/server/service/`**，目前覆盖 `AgentLoopService`（边界与 recovery round budget）和 `DeadlineContext`（ThreadLocal set/get/clear/隔离）。让 Codex 验证修复时：

- 优先让它**写一个最小复现测试**而不是只改代码
- 复现不出来时，让它静态分析 + 解释为什么这是 bug
- 任何"我修好了"都要附上：根因 / 改动点 / 为什么这样改 / 影响面

## 重要约定

- **领域术语**用 `CONTEXT.md` 里的 canonical 名（EvidenceHit vs CitedEvidence 不能混用）。
- **面试展示项目**——代码会被反复解读，可读性 > 简洁。新增公共抽象前先想清楚是不是必要的。
- **不要随意增加测试框架**。如果要补测试，先和用户确认 Spring Boot Test + JUnit 5 还是其他。
- **外部依赖降级路径**是核心特性，不是 nice-to-have。修 Qdrant/ASR/OCR 相关问题时必须保留降级。

## Issue 工作流

见 `docs/agents/issue-tracker.md`。标签：`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`。

## Codex 调试建议用法

1. **小范围定向**：一次只让它看一个 service 或一个状态机路径，范围太大质量会下降。
2. **让它先复现，再修复**：拒绝只改代码不写测试的"修复"。
3. **要根因，不要症状**：要求它解释"为什么是 bug"，说不清的大概率是误报。
4. **保留降级**：Qdrant/ASR/OCR 故障路径是这个项目的核心特性，不要被"简化"掉。
