# 评测工作区

本目录保存 DoVideoAI 的可复现评测脚本、公开标注和派生结果。视频、完整 ASR/OCR、VideoContext、VideoChunk 与 Embedding 放在 `benchmark/local/`，该目录已被 Git 忽略。

## 当前状态

GitHub 历史数据包含 24 道检索问题、逐题排名、阶段 trace、证据校验结果和稳定性结果，但缺少当时实际使用的原始视频、`chunks_<mediaId>.json` 与 `context_<mediaId>.json`。因此，旧历史数字只作为审计记录，不作为当前生产公式的基线。

2026-08-25 从公开视频重新构建了第一份 60 分钟种子语料。2026-08-26 又完成四条长视频的下载、分 P 顺序合并、媒体校验、ASR / OCR、VideoSegment、VideoChunk 和 Embedding，共新增 55 个五分钟 Chunk。2026-08-28 完成 96 道新增问题的人工审核和检索评测；与种子语料合并后，严格校验无错误、无警告，评测集状态为 `publishable`。

| 划分 | 问题数 | Hit@1 | Hit@3 / Recall@3 | MRR@3 |
| --- | ---: | ---: | ---: | ---: |
| dev | 48 | 54.17% | 83.33% | 0.6701 |
| test | 72 | 77.78% | 95.83% | 0.8634 |
| 合计 | 120 | 68.33% | 90.83% | 0.7861 |

当前完整链路按题型的 `Recall@3`：精确术语 100.00%（48 题）、模糊查询 81.25%（48 题）、视觉/OCR 91.67%（24 题）。相对历史混合公式，完整链路的 `Hit@1` 提升 15.00 个百分点，`Recall@3` 提升 4.16 个百分点，`MRR@3` 提升 0.0972。配对 bootstrap 的 Recall 提升 95% 区间为 `[-3.33, 11.67]` 个百分点，区间仍包含 0；因此该结果支持当前链路的排序质量更好，但不能据此宣称 Recall 提升已经具有统计显著性。

本轮发布门槛均已满足：

- 至少 5 条 45–90 分钟视频，每条不少于 10 个五分钟 Chunk；
- 至少 120 道问题，每条视频至少 20 道；
- 精确术语、模糊语义、视觉/OCR 问题分别约占 40%、40%、20%；
- 调参与最终测试分离，最终测试题不得用于改权重或 Prompt；
- 允许一个问题标注多个相关 Chunk，主指标为 `Recall@3`，同时报告 `Hit@1`、`Hit@3`、`MRR@3` 和分组指标；
- 所有问题和相关时间点经过人工审核，自动生成的问题不能直接计入正式结果。

指标单位是 300 秒 `VideoChunk`，不是最终答案正确率。本轮每道题标注一个主相关时间点，因此 `Hit@3` 与 `Recall@3` 数值相同；格式仍支持后续问题标注多个相关 Chunk。dev 用于开发检查，冻结公式和 Prompt 后才一次性运行 test。公开仓库保存问题、逐题排名和聚合结果，原始视频、完整转录、Chunk 与 Embedding 仍只保存在 `benchmark/local/`。

短视频可以继续用于端到端耗时和稳定性测试，但不纳入主检索指标：5 分钟视频只有一个 Chunk，Top-3 必然命中；18 分钟视频通常只有四个 Chunk，随机 Top-3 已有 75% 命中概率，会明显抬高召回率。

## 答案评测套件

`data/answer_suite_v1.json` 是从冻结版人工复审表整理出的公开评测规格，不是一次新的系统运行结果。48 道候选题中保留 43 道、排除 5 道；保留题包含 36 道可回答题和 7 道不可回答题，并固定为 18 道 dev、25 道 test。公开版移除了完整证据原文，只保留时间点、证据通道、证据摘要、金标答案、必需事实、禁止断言和引用要求。

套件将“怎么测”固化为以下口径：

1. 对每道题保存系统回答及其引用，再与题目的金标字段逐项比较。
2. 可回答题按答案正确性、事实完整性、证据忠实度、引用位置正确性和引用支持度五项评分，每项 2 分，总分 10 分；标记为 `hardGate` 的维度需要单独报告失败率。
3. 不可回答题按拒答能力评分，满分 2 分；虚构事实或伪造引用应触发硬门失败。
4. 逐题分数应按 dev/test、题型和可回答性分别聚合，同时保留系统原始回答与逐题评分，才能对外报告答案质量数字。

`data/answer_suite_v1.json` 本身只定义题目和评分口径，不能当作答案通过率。冻结 Excel 和审核报告保留在本地 `outputs/` 作为审计源，不进入 Git；JSON 中仅保留源文件名和 SHA-256 指纹。

### 2026-08-31 AgentLoop 回答基线

本次用生产 `AgentLoopService`、`VideoEvidenceRetrievalService` 和两轮上限执行全部 43 道保留题。初始检索优先复用冻结语料中完全匹配的 Query Rewrite 与 Embedding，否则实时生成；Critic 触发的定向补检始终实时调用模型。Qdrant 在离线评测中关闭，走生产代码已有的内存向量、关键词与 OCR 降级排序。执行预算从生产默认的 120 秒放宽到每题 300 秒、100000 估算 token，以观察完整的两轮行为。

| 指标 | 结果 | 口径 |
| --- | ---: | --- |
| 完成率 | 42/43（97.67%） | 1 道不可回答题在 300 秒预算下超时 |
| 归一化总分 | 61.50% | 失败题按 0 分计；仅已完成题为 61.83% |
| 硬门通过率 | 17/43（39.53%） | 可回答题的四项硬门、不可回答题的拒答维度均为满分；失败题计未通过 |
| 不可回答题硬门通过率 | 2/7（28.57%） | 含 1 道超时题 |
| Critic 最终通过率 | 0/43（0%） | 42 道完成题均执行两轮，均未通过最终 Critic |
| 定向补检恢复率 | 0/38（0%） | 38 道触发 EvidenceHit 定向补检，无样本恢复到 Critic 通过 |
| 严格 Claim–Evidence 支持率 | 62.16% → 55.15% | 自动检查绑定关系及 ASR/OCR 原文包含关系 |
| 无支持 Claim 率 | 37.84% → 44.85% | 从第一轮到最终轮增加 7.01 个百分点 |
| AgentLoop 耗时 | P50 115.8 秒；P95 272.9 秒；最大 296.3 秒 | 仅统计 42 道完成题，不含超时题 |

结果没有验证”第二轮定向补检能提升最终质量”，也不支持”AgentLoop 均在 90 秒内完成”的表述。归一化分数由与生成器同模型家族的 LLM judge 对照人工复审金标评分，尚未经过独立模型或人工逐题复核；它适合作为可追踪的工程基线，不应包装为独立的质量证明。公开结果见 `data/answer_suite_v1_results.json`，包含逐题回答、评分理由、证据摘要/哈希、阶段耗时和聚合结果；完整模型输出保留在被 Git 忽略的 `benchmark/local/answer-eval/`，公开文件记录其 SHA-256。

### 2026-09-01 AgentLoop 回答基线（Phase 1-4 改进后）

本次在 2026-08-31 基线基础上，实施四项核心改进：

1. **Phase 1 - 确定性引用对齐**（Citation Alignment）：在 Executor 输出后、Critic 校验前，插入 `CitationAlignmentService`，把 LLM 生成的换述、概括、省略或 ASR 口误纠正等非逐字引用，修正为所引时间戳处 ASR/OCR 原文的精确子串。搜索空间硬限定为覆盖 `timestampMs` 的段及其相邻段，防止错引。详见 ADR-0005。

2. **Phase 2 - 证据门控语义**（Evidence Gate Policy）：提取 `EvidenceGatePolicy` 组件，过滤 LLM Critic 的声明。区分真缺口（blocking）与换述类批评（降级为 feedback），丢弃幻觉时间戳和已在 prompt context 内的时间戳。当所有 claims 都已被确定性层确认支持时，`requiredTimestamps` 整体降级为 feedback。详见 ADR-0006。

3. **Phase 3 - 二轮定向修补**（Deterministic Merge）：提取 `AgentResultMerge` 组件，把一轮已核验的证据与结论合并到二轮 draft 中。构造性保证”二轮不劣化”：一轮通过 EVS 核验的证据与结论，只要其目标仍在最终产物中，就会以原文形式出现在合并结果里。

4. **Phase 4 - 拒答路径**（Refusal Path）：`AnalysisResult` 增加 `refusal` 字段，允许不可回答题输出合法拒答产物。当 `refusal=true` 时，允许 `evidence` 为空数组，`conclusions` 明确说明缺失的证据类型。

| 指标 | 2026-08-31 基线 | 2026-09-01 改进后 | 提升 |
| --- | ---: | ---: | ---: |
| 完成率 | 42/43（97.67%） | 41/43（95.35%） | -2.32pp |
| 归一化总分 | 61.50% | 78.07% | **+16.57pp** |
| 硬门通过率 | 17/43（39.53%） | 28/43（65.12%） | **+25.59pp** |
| Critic 最终通过率 | 0/43（0%） | 22/43（51.16%） | **+51.16pp** |
| 一轮通过数 | 1/43（2.33%） | 22/43（51.16%） | **+48.83pp** |
| 二轮任务数 | 42 | 19 | -23 |
| 严格 Claim–Evidence 支持率（一轮） | 62.16% | 51.30% | -10.86pp |
| 严格 Claim–Evidence 支持率（最终） | 55.15% | 70.45% | **+15.30pp** |
| 严格 Evidence 支持率（最终） | 53.07% | 93.29% | **+40.22pp** |
| AgentLoop 耗时 | P50 115.8 秒；P95 272.9 秒 | P50 65.1 秒；P95 204.0 秒 | **P50 -43.8%** |

**关键改进解读：**

- **Evidence 支持率从 53.07% 提升至 93.29%**：确定性引用对齐消除了约 86% 的”合理引用但非逐字”失败，保留真正的幻觉检测能力。
- **Critic 通过率从 0% 提升至 51.16%**：证据门控策略过滤了换述类批评和幻觉时间戳，确定性层复核覆盖 LLM 的 fail 判断。
- **一轮通过数从 1 提升至 22**：更多任务在首轮就能通过 Critic 校验，减少无谓的二轮重试。
- **P50 延迟从 115.8s 降至 65.1s**：一轮通过率提升 + 拒答早退，显著降低整体延迟。

**已知局限：**

- 二轮修复率仍为 0%：进入二轮的任务仍无法通过 Critic 校验，说明首轮失败的根本原因可能不是证据不足，而是其他问题（如结论结构错误、检索召回不足）。
- 不可回答题拒答触发率 0%：7 道不可回答题中，仅 1 道触发了拒答路径（`refusal=true`），其余 6 道仍尝试作答。说明 Executor 的拒答意识不足，需要进一步强化 prompt 指引。
- LLM judge 与生成器同模型家族：归一化分数和硬门通过率由同模型家族评分，可能存在自我偏好。应使用独立模型或人工复核验证。

本地复跑需要 JDK 21、Node.js、`.env` 中的模型配置，以及五条已冻结的 `benchmark/local/corpora/*/dataset.json`：

```powershell
$env:AGENT_MAX_DURATION_MS = '300000'
$env:AGENT_MAX_ESTIMATED_TOKENS = '100000'

node benchmark/scripts/answer_suite_eval.mjs run
node benchmark/scripts/answer_suite_eval.mjs score
node benchmark/scripts/answer_suite_eval.mjs validate
```

`run` 增量写入本地原始结果并支持断点续跑；`score` 缓存逐题 judge 响应并生成可提交的公开 JSON；`validate` 检查套件 ID、题目唯一性、样本数和聚合指标范围。

## 文件说明

```text
benchmark/
├── README.md
├── data/
│   ├── retrieval_suite.json              # 多视频评测套件与发布门槛
│   ├── questions_60.json                 # 当前 24 题种子标注
│   ├── questions_bv*.json                # 四条新增视频的人工审核问题
│   ├── retrieval_formula_ab_results.json # 当前同语料 A/B 种子结果
│   ├── retrieval_bv*_results.json        # 四条新增视频的逐题检索结果
│   ├── retrieval_suite_baseline.json     # 多视频聚合器的当前输出
│   ├── answer_suite_v1.json              # 冻结答案题集、金标与评分规则
│   ├── answer_suite_v1_results.json      # AgentLoop 逐题回答、评分与聚合基线
│   └── *_results.json / trace_*.json     # 历史派生结果，仅供审计
├── java/                                 # 调用生产 AgentLoop 的离线评测入口
├── local/                                # 视频、Chunk、Embedding，不入 Git
└── scripts/
    ├── answer_suite_eval.mjs             # 执行、评分并校验答案评测
    ├── retrieval_formula_ab.mjs          # 构建单视频语料并运行公式 A/B
    ├── retrieval_suite_eval.mjs          # 校验、聚合多视频结果
    ├── retrieval_eval.mjs                # 历史公式评测器
    └── test_driver.mjs                   # 在线链路测试驱动
```

## 本机基线

完整种子数据集保存在：

```text
benchmark/local/corpora/bv1xhovyzetu-60m/dataset.json
```

它包含 ASR/OCR、12 个 VideoChunk、Chunk Embedding、24 个查询意图及查询 Embedding。仓库内的派生结果不包含视频、完整转录、Embedding 或 API key。

离线重跑单视频 A/B：

```powershell
node benchmark/scripts/retrieval_formula_ab.mjs compare `
  benchmark/local/corpora/bv1xhovyzetu-60m/dataset.json `
  benchmark/data/retrieval_formula_ab_results.json
```

检查扩样进度并聚合所有已就绪样本：

```powershell
node benchmark/scripts/retrieval_suite_eval.mjs validate benchmark/data/retrieval_suite.json
node benchmark/scripts/retrieval_suite_eval.mjs aggregate `
  benchmark/data/retrieval_suite.json `
  benchmark/data/retrieval_suite_baseline.json
```

`validate` 默认只检查结构并报告距离发布门槛还差多少；增加 `--strict` 后，样本门槛未达到也会返回失败，适合最终报告前的 CI 检查。

## 添加新视频

选择 45–90 分钟、内容边界清晰且允许本地评测的视频。分 P 视频需要先按页面顺序合并，并保留每个分 P 的累计时间边界。准备阶段需要 FFmpeg、Tesseract `chi_sim+eng` 和模型网关。

先提取 ASR / OCR、构建 VideoSegment 和五分钟 VideoChunk。`VIDEO_SECONDS` 必须覆盖完整视频；例如 90 分钟上限可设置为 `5400`：

```powershell
$env:SILICONFLOW_API_KEY = '<rotated-key>'
$env:VIDEO_SECONDS = '5400'
$env:SOURCE_URL = '<source-url>'
$env:DATASET_ID = '<stable-dataset-id>'
$env:DOV_RETRIEVAL_WORK = 'benchmark/local/work/<dataset-id>'

node benchmark/scripts/retrieval_formula_ab.mjs extract `
  <video.mp4> `
  benchmark/local/corpora/<dataset-id>/dataset.json
```

基于原始 ASR / OCR 生成 24 道候选题。该命令固定生成 9 道精确术语题、9 道模糊查询题和 6 道视觉题，所有候选题均为 `reviewed: false`，不能直接计入正式指标：

```powershell
node benchmark/scripts/retrieval_formula_ab.mjs draft `
  benchmark/local/corpora/<dataset-id>/dataset.json `
  benchmark/local/candidates/<dataset-id>_questions.json
```

人工逐题核对问题、相关时间点和 ASR / OCR 证据。保留或修订的题目必须显式标记 `reviewed: true`；剔除的题目不进入最终问题文件。审核完成后生成查询意图和 Embedding，再离线运行公式 A/B：

```powershell
node benchmark/scripts/retrieval_formula_ab.mjs enrich `
  benchmark/local/corpora/<dataset-id>/dataset.json `
  benchmark/local/reviewed/<dataset-id>_questions.json

node benchmark/scripts/retrieval_formula_ab.mjs compare `
  benchmark/local/corpora/<dataset-id>/dataset.json `
  benchmark/local/results/<dataset-id>_results.json
```

`enrich` 会拒绝任何没有 `reviewed: true` 的问题。问题修改后，查询缓存按问题文本、题型和相关时间点的哈希重新计算，不会错误复用修改前的 Embedding。

问题文件兼容单正样本和多正样本：

```json
[
  {
    "id": "video-a-fuzzy-001",
    "q": "完全没基础应该从哪里开始？",
    "tag": "fuzzy",
    "relevantSecs": [150, 420],
    "reviewed": true
  }
]
```

旧格式的 `expectSec` 仍然兼容。时间点单位为秒，Chunk 和 VideoSegment 时间单位为毫秒。

完成单视频 `compare` 后，把人工审核问题、数据集元信息和结果文件加入 `retrieval_suite.json`，再运行聚合器。原始视频、完整语料、候选题审核中间件和 API key 不得提交到 Git；新增公开问题必须先确认不包含大段版权文本。

## 历史数据

以下文件来自旧测试，但缺少对应原始 Chunk，不能与当前结果直接做公式归因：

- `data/retrieval_results.json`
- `data/evidence_results.json`
- `data/stability_results.json`
- `data/trace_*.json`
- `data/result_*.json`

保留这些文件是为了审计测试来源，不代表 README 对其中结论背书。
