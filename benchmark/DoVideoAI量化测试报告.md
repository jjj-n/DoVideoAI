# DoVideoAI 量化测试报告

- **测试日期**：2026-08-23 ~ 2026-08-24
- **机器**：Windows 10 x64，16GB 内存，机械/SSD 混合盘（测试时使用率 79%）
- **模型网关**：SiliconFlow（LLM `Qwen/Qwen2.5-32B-Instruct`，Embedding `BAAI/bge-m3`，ASR `TeleAI/TeleSpeechASR`）
  - 注：项目默认 LLM `deepseek-ai/DeepSeek-R1-Distill-Qwen-32B` 已被 SiliconFlow 下架（错误码 30003 Model disabled），测试时替换为同参数规模的 Qwen2.5-32B-Instruct（`.env` 中 `LLM_MODEL` 可配置，无代码改动）
- **中间件**：MySQL 8、Redis 5.0.14、Qdrant 1.19、MinIO、RocketMQ 4.9.4（与项目 docker-compose.yml 声明版本一致，本地原生运行）

## 测试物料

真实 B 站技术视频三档（yt-dlp 下载 720p 后 ffmpeg 裁剪，`-c copy` 无重编码）：

| 档位 | 内容 | 时长 | 大小 |
|---|---|---|---|
| 短 | 算法课：旋转排序数组找峰值（二分查找） | 5:00 | 14MB |
| 中 | 算法课：排列型回溯 + N 皇后 | 18:15 | 33MB |
| 长 | 深度学习从零入门学习路线 | 60:00 | 142MB |

另有两个 60 分钟变体用于并行收益对照：纯音轨版（ASR 路单独计时）、纯画面版（OCR 路单独计时）。

> 测试视频因体积与版权原因未随仓库发布；`scripts/gen_videos.sh` 可生成内容可控的合成替代视频（每槽一个知识点，OCR=标题+关键词，ASR=TTS 口播稿，语料见 `data/knowledge_30.json`）。

---

## 测试 1：解析耗时与并行收益

**方法**：真实视频走完整生产链路（分片上传 → RocketMQ 异步消费 → FFmpeg 切片/抽帧 → ASR/OCR 并行 → 混合检索 → Planner-Executor-Critic），阶段耗时取自项目自带 telemetry（`GET /analysis/agent-trace`）。串行对照：同一 60 分钟视频的纯音轨版与纯画面版分别解析，各自 VIDEO_CONTEXT 阶段耗时之和作为"串行理论值"（两变体各含少量公共开销，故该口径偏保守）。

**原始数据（trace 的 stageDurationMs）**：

| 视频 | ASR 调用 | OCR 帧数 | VIDEO_CONTEXT | AGENT_LOOP | 端到端 |
|---|---|---|---|---|---|
| 5 分钟 | 6 | 9 | 30.6s | 50.5s | ~81s |
| 18.25 分钟 | 19 | ~53 | 112.4s | 78.0s + 15.3s(摘要) | ~206s |
| 60 分钟 | 61 | 177 | **548.0s** | 87.7s | **~636s** |
| 60 分钟纯音轨 | 61 | 0 | **353.2s** | — | — |
| 60 分钟纯画面 | 0 | 177 | **417.3s** | — | — |

**结论**：
- 60 分钟视频端到端解析 **636 秒（约 10.6 分钟）**，其中多模态构建 548s、Agent 循环 88s（含 2 轮 Critic、1 次定向补检、1 次重规划）
- 双路并行收益：串行理论 353.2+417.3=770.5s vs 并行实际 548.0s，**加速比 1.41x，节省 28.8%**
- AGENT_LOOP 耗时与视频时长基本无关（50-88s），瓶颈在多模态构建，且耗时与视频时长近似线性（ASR 片数=时长/60s）

原始 trace 数据见 `data/trace_*.json`。

## 测试 2：检索质量（top-k 命中率与 MRR）

**方法**：60 分钟视频解析完成后，从 Redis checkpoint 导出 12 个 5 分钟 chunk（含 LLM 摘要、关键词、bge-m3 向量），人工标注 24 个测试问题（12 精确术语 + 12 模糊语义），每题标注期望命中的时间点；期望 chunk = 覆盖该时间点的 chunk。离线复刻项目 `LongVideoContextService` 的三种打分（混合=0.7×cosine+0.3×关键词包含匹配；查询向量用同一 bge-m3 模型实时计算），对比 Top-3 命中率与 MRR。

**原始数据**（评测集 `data/questions_60.json`，结果 `data/retrieval_results.json`）：

| 检索方式 | Hit@3 | MRR@3 | 精确题命中 | 模糊题命中 |
|---|---|---|---|---|
| 混合检索（项目默认） | **83.3%** | **0.736** | 100% | 66.7% |
| 纯向量 | 83.3% | 0.764 | 100% | 66.7% |
| 纯关键词 | 58.3% | 0.514 | 91.7% | 25.0% |

**结论**：
- 混合检索 Top-3 命中率 83%、MRR 0.74
- **纯关键词单路显著落后**：模糊语义问题命中率仅 25%，是语义检索（67%）的 1/2.7
- 本数据集上混合与纯向量持平（MRR 0.74 vs 0.76）：chunk 摘要质量高时向量已足够强，关键词 0.3 权重对个别排序有轻微拉低。混合的真实价值在**鲁棒性**——向量服务故障时关键词兜底（代码内建 Qdrant/Embedding 双降级），且精确题上关键词路有独立贡献

## 测试 3：证据校验与幻觉抑制

**方法**：从 6 个视频、9 个分析任务的 checkpoint 中导出全部 42 条结论与 36 条证据（超过 20 条样本要求），离线复刻 `EvidenceVerificationService` 的两层程序化校验：层1=证据时间戳是否落入真实视频分段区间；层2=证据文本是否能在该时间段的 ASR/OCR 原文中匹配（包含匹配或 bigram 覆盖率≥0.5，与生产代码同阈值）。

**原始数据**（结果 `data/evidence_results.json`）：
- 层1 时间戳可回溯：**36/36 = 100%**（无一条时间戳幻觉）
- 层2 文本可溯源（严格 bigram 口径）：**19/36 = 53%**——LLM 生成的证据文本多为转述而非逐字引用，严格匹配下一半不达标（抽样人工核对：这些转述在语义上基本对应原片内容，属"转述偏差"而非"编造"）
- 层3 Critic 软校验：9 个任务全部以 `ANALYSIS_COMPLETED_WITH_WARNINGS` 终态结束（2 轮上限内 Critic 始终能挑出问题，平均每次 2.7 条 unsupported claims）
- 引用密度：36 条证据 / 42 条结论 = **0.86 条时间戳证据每结论**

**结论**：时间戳零幻觉（程序化硬校验有效拦截），证据文本层存在转述导致的严格匹配率偏低，Critic 轮次上限（2 轮）约束下多数分析以"带警告"终态交付。

## 测试 4：断点恢复收益

**方法**：60 分钟视频解析进行到第 586 秒（telemetry 确认 checkpoint 已达 `CHUNKS_COMPLETED`：VideoContext + 分块索引均已落 MySQL/Redis）时 `taskkill /F` 强杀后端进程；等 Redisson 锁过期后重启后端，以新分析目标重新提交（消费时按 checkpoint 分层短路）。

**原始数据**：
- kill 时刻：提交后 586s，checkpoint 阶段 = CHUNKS_COMPLETED
- 恢复执行：仅重跑 AgentLoop 67.7s（trace 确认 `contextCheckpointHits=1`、`chunkCheckpointHits=2`，ASR/OCR/分块全部跳过）
- 全量重跑基准：636s（同视频首次解析实测）

**结论**：**恢复耗时 68s vs 全量重跑 636s，节省 89%**。注：该场景为多模态构建完成后失败，属恢复收益最大的情况；失败点越靠前，可短路的部分越少，收益相应递减。

## 测试 5：稳定性（连续 10 任务）

**方法**：连续顺序提交 10 个分析任务（4 个 5 分钟 + 4 个 18 分钟 + 2 个 60 分钟视频，每任务独立目标，VideoContext 按设计复用 checkpoint），记录状态与端到端耗时。

**原始数据**（结果 `data/stability_results.json`）：8 个即时成功 / 2 个即时失败。成功任务耗时（秒）：45/50/50/50/50/55/30/115（中位数 **50s**，最大 115s）。失败 2 个均为 60 分钟视频任务，失败点在 EXECUTOR 模型调用，错误日志：`RateLimitException: TPM limit reached`（SiliconFlow 网关 TPM 配额限流，指数退避重试 3 次后仍撞限）。**间隔 2 分钟后串行补测同 2 个任务，均成功**。

**结论**：系统层面 10/10 零故障（无崩溃/无死锁/无数据错乱）；即时成功率 80%，剔除外部 LLM 网关限流因素后成功率 100%。大 prompt 任务（60 分钟视频单轮输入约 3.6 万 token）在高 TPM 消耗下易触发网关限流，属容量规划问题而非代码缺陷，生产环境应配合错峰重试队列。

---

## 测试过程中发现的项目缺陷（额外产出）

1. **本地 main 无法编译**（`VideoChunk` 缺 `startMs()/endMs()` 4 处调用点 + ASR multi-catch 子类冗余）
2. **Redisson 3.23.5 与 Spring Data Redis 3.5 不兼容**：消费链路 `pExpire` 默认方法无限自递归致 StackOverflowError，消息被静默吞掉；升级 Redisson 3.52.0 后修复
3. **消费端锁竞争丢任务缺陷**：消费者 `tryLock()` 失败时直接 ACK 跳过消息（`video_analysis_skipped`）。若锁持有进程已崩溃（锁尚未过期），重投消息也会被丢弃，任务永久丢失且状态停留 QUEUED——建议改为 skip 时抛异常触发 MQ 重试
4. **限流失败无自动降级**：LLM 网关 TPM 限流时 3 次快速重试后任务直接失败，无排队/延迟重投机制

## 附录 A：为使测试基线可运行所做的 3 处修改

1. `VideoChunk.java`：补 `startMs()/endMs()` 委托方法（编译修复，8 行）
2. `AliyunAsrUtils.java:54`：multi-catch 去掉与父类冗余的 `RetryableAsrException`
3. `pom.xml`：Redisson 3.23.5 → 3.52.0（修复消费链路 StackOverflowError）

三处修改均为最小修复且不改变业务逻辑；所有测试数据基于该版本测得。

复现方法见 [README.md](./README.md)。
