# 量化测试与评估

本目录是 DoVideoAI 的全链路量化测试：完整报告、可复现脚本与原始结果数据。测试结论（60 分钟视频端到端解析约 11 分钟、断点恢复节省 89% 耗时等）见 [DoVideoAI量化测试报告.md](./DoVideoAI量化测试报告.md)。

## 目录结构

```text
benchmark
├── DoVideoAI量化测试报告.md   # 完整报告：5 项测试的方法、原始数据与结论
├── scripts/                   # 测试驱动与评测脚本（可复现）
│   ├── test_driver.mjs        # 登录/上传/提交分析/轮询/取 trace（全部测试的驱动）
│   ├── retrieval_eval.mjs     # 检索质量三方式对比（复刻生产打分公式）
│   ├── evidence_eval.mjs      # 证据两层程序化校验统计
│   ├── stability_test.mjs     # 连续 10 任务稳定性
│   ├── dump_checkpoint.sh     # 从 Redis 导出 chunks/context/result
│   └── gen_videos.sh          # 合成测试视频生成器（Windows/PowerShell TTS）
└── data/
    ├── questions_60.json      # 检索评测集（24 题人工标注）
    ├── knowledge_30.json      # 合成视频语料（30 个知识点槽位）
    ├── trace_*.json           # 各档视频的 agent-trace 阶段耗时（测试 1 原始数据）
    ├── retrieval_results.json # 测试 2 原始数据
    ├── evidence_results.json  # 测试 3 原始数据
    ├── stability_results.json # 测试 5 原始数据
    └── result_*.json          # 各任务最终结论样例
```

## 复现步骤

前置：按仓库根 README 启动中间件与后端（默认 `http://localhost:9090`，可用 `DOV_BASE` 覆盖），并准备：

```bash
export SILICONFLOW_API_KEY=sk-xxx   # 检索评测需要实时计算查询向量
export REDIS_PASSWORD=xxx           # dump_checkpoint.sh 需要
```

```bash
# 1. 上传并分析一个视频（mediaId 与 trace 见输出）
node scripts/test_driver.mjs upload <video.mp4>
node scripts/test_driver.mjs analyze <mediaId> "<分析目标>"

# 2. 导出 checkpoint（供离线评测）
scripts/dump_checkpoint.sh <mediaId> data

# 3. 检索质量对比（混合 / 纯向量 / 纯关键词）
node scripts/retrieval_eval.mjs data/questions_60.json data/chunks_<mediaId>.json

# 4. 证据两层程序化校验（输入为导出的 context）
node scripts/evidence_eval.mjs data/evidence_input.json

# 5. 连续 10 任务稳定性（需要已解析的媒体，任务清单在脚本内）
node scripts/stability_test.mjs
```

## 说明

- **测试视频不入库**（体积与版权原因）：可用 `scripts/gen_videos.sh` 生成内容可控的合成视频，或自行准备公开课/纪录片片段。`gen_videos.sh` 依赖 Windows PowerShell TTS 与 `ffmpeg`（可用 `FFMPEG` 环境变量指定路径）。
- 测试基线为上游 `423277e` + 3 处修复（见报告附录 A）；模型网关 SiliconFlow（Qwen2.5-32B / bge-m3 / TeleSpeechASR），换成其他网关时绝对耗时会不同，但相对结论（并行加速比、检索对比、恢复节省比例）仍可参考。
- 断点恢复测试（报告测试 4）需在解析中途 `taskkill` 强杀后端进程后重启复测。
