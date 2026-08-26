# 评测工作区

本目录保存 DoVideoAI 的可复现评测脚本、公开标注和派生结果。视频、完整 ASR/OCR、VideoContext、VideoChunk 与 Embedding 放在 `benchmark/local/`，该目录已被 Git 忽略。

## 当前状态

GitHub 历史数据包含 24 道检索问题、逐题排名、阶段 trace、证据校验结果和稳定性结果，但缺少当时实际使用的原始视频、`chunks_<mediaId>.json` 与 `context_<mediaId>.json`。因此，历史数字只能作为审计记录，不能作为当前生产公式的可复现基线。

2026-08-25 已从公开视频重新构建一份 60 分钟语料，并在同一批 Chunk、标签和 Embedding 上比较历史公式与当前链路。该 24 题结果现在只作为**种子基线**。扩大到以下门槛并完成人工审核前，不发布新的汇总测试报告：

2026-08-26 已完成四条新增长视频的下载、分 P 顺序合并、媒体校验、ASR / OCR、VideoSegment、VideoChunk 和 Embedding，共新增 55 个五分钟 Chunk。每条视频已生成 24 道候选题，共 96 道（精确术语 36、模糊查询 36、视觉信息 24），当前状态为 `review-pending`；人工审核结束前仍不计入 Recall。

- 至少 5 条 45–90 分钟视频，每条不少于 10 个五分钟 Chunk；
- 至少 120 道问题，每条视频至少 20 道；
- 精确术语、模糊语义、视觉/OCR 问题分别约占 40%、40%、20%；
- 调参与最终测试分离，最终测试题不得用于改权重或 Prompt；
- 允许一个问题标注多个相关 Chunk，主指标为 `Recall@3`，同时报告 `Hit@1`、`Hit@3`、`MRR@3` 和分组指标；
- 所有问题和相关时间点经过人工审核，自动生成的问题不能直接计入正式结果。

短视频可以继续用于端到端耗时和稳定性测试，但不纳入主检索指标：5 分钟视频只有一个 Chunk，Top-3 必然命中；18 分钟视频通常只有四个 Chunk，随机 Top-3 已有 75% 命中概率，会明显抬高召回率。

## 文件说明

```text
benchmark/
├── README.md
├── data/
│   ├── retrieval_suite.json              # 多视频评测套件与发布门槛
│   ├── questions_60.json                 # 当前 24 题种子标注
│   ├── retrieval_formula_ab_results.json # 当前同语料 A/B 种子结果
│   ├── retrieval_suite_baseline.json     # 多视频聚合器的当前输出
│   └── *_results.json / trace_*.json     # 历史派生结果，仅供审计
├── local/                                # 视频、Chunk、Embedding，不入 Git
└── scripts/
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
