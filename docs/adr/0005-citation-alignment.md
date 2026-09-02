# 确定性引用对齐（Citation Alignment）

Status: Accepted

Date: 2026-09-01

## Context

AgentLoop 的 Executor 由 LLM 生成 `AnalysisResult`，其中 `evidence.content` 应当是所引时间戳处 ASR/OCR 原文的精确子串，以便 `EvidenceVerificationService`（EVS）通过逐字包含关系校验。然而 LLM 天然会换述、概括、省略、修正 ASR 口误、或以 `ASR文本 (OCR: OCR文本)` 合并格式引用，导致 `content` 与原文不完全一致。

原始基线（2026-08-31）中，证据支持率仅 53.1%，Critic 通过率 0%。法证分析显示：101 条失败 evidence 中约 86 条是"合理引用但非逐字"（边界改写 20、内部小编辑 51、ASR+OCR 合并格式 15），真正幻觉罕见。

若放宽 EVS 的逐字校验规则，会让"伪造引用"也能通过，动摇"受控可追溯"的核心承诺。系统需要一种机制：在不放松质量门的前提下，把合理引用自动对齐回原文精确子串。

## Decision

在 Executor 输出后、Critic 校验前，插入确定性对齐层 `CitationAlignmentService`：

1. **搜索空间硬限定**：只搜索覆盖 `timestampMs` 的段及其时间轴 ±1 相邻段。绝不做全片搜索——换述内容若锚定到别处的相似文本属于错引，宁可失败也不修。

2. **归一化单一事实源**：`CitationText.normalize()` 与 EVS 的归一化规则逐字节同源（lowercase + 去 `[\p{P}\p{S}\s]`），并维护归一化字符到原文下标的映射，保证 snap 产物能还原为原文精确子串。

3. **容错匹配算法**：
   - 精确包含优先（归一化后 `indexOf`）
   - 若失败且归一化长度 ≥ 8：k-gram 块偏移投票（最多 16 块）→ 取前 3 候选 offset → 走廊对齐（双指针，源端/查询端各自容忍 ≤24 字符跳过）→ 覆盖率 ≥ 0.75 则接受
   - 省略号引用：源端跳过阈值放宽到 400 字符，且无条件优先源端跳过（模型已声明删减）

4. **ASR+OCR 合并格式拆分**：检测到 `ASR: X OCR: Y` 等标签时，按通道拆分为两条独立 evidence，分别对齐到 transcript 或 ocrTexts。

5. **claim 绑定**：`evidence.claim` 必须与某条 conclusion 归一化后相等。若不等，用 bigram Dice 系数（阈值 0.75）绑定到最佳 conclusion，并替换为该 conclusion 的原文。拼接型 claim（"A；B；C"）按分隔符拆分逐段绑定。

6. **质量门不放松**：对齐失败的 evidence 保持原样，继续被 EVS 判为 unsupported。Critic 仍会失败并触发定向补检。

`CitationAlignmentService` 作为 Spring `@Service` 注入到 `AgentLoopService`，在 `executeRound` 中调用：
```java
result = citationAlignmentService.align(fullContext, result);
```

## Consequences / Trade-offs

**收益：**

- 证据支持率从 53.1% 提升至 93.3%（+40.2pp），Critic 通过率从 0% 提升至 51.16%
- 消除约 86% 的"合理引用但非逐字"失败，保留真正的幻觉检测能力
- 对齐算法确定性、可测试、无 LLM 调用成本
- 搜索空间限定防止错引，覆盖率阈值防止误锚

**代价：**

- 增加约 1-2ms 延迟（全量 context 的归一化 + 匹配）
- 算法复杂度较高（k-gram 投票 + 走廊对齐），需要充分测试覆盖
- 阈值（0.75 覆盖率、0.75 Dice、8 字符最小长度）需要标定，换述风格差异可能影响效果
- ASR+OCR 合并格式拆分增加了 evidence 数量，可能影响下游聚合指标的分母

**风险：**

- 换述内容恰好覆盖某相似窗口时可能误锚（缓解：搜索空间限定 + 覆盖率阈值 + EVS 双重复查）
- 短串（< 8 字符）不做容错，可能漏掉合理的短引用修正（缓解：短串本身幻觉风险高，保守策略更安全）

## Validation

- `CitationAlignmentServiceTest`：15 个测试用例覆盖幂等性、标点差异、边界改写、内部小编辑、ASR+OCR 合并格式拆分、真换述保留、伪造内容保留、claim 绑定（精确/换述/拼接型/孤儿）、短串不做容错、相邻段重定位、source 通道改写
- `CitationAlignmentReplayTest`：离线回放 43 题基准，验证对齐前后的支持率变化
- 2026-09-01 基准测试结果：证据支持率 93.3%，Critic 通过率 51.16%，硬门通过率 65.12%

## Related code

- `service/CitationAlignmentService` - 确定性引用对齐核心逻辑
- `service/CitationText` - 归一化单一事实源，维护归一化到原文的映射
- `service/AgentLoopService#executeRound` - 在 Executor 输出后调用对齐
- `service/EvidenceVerificationService` - 对齐后的逐字校验
