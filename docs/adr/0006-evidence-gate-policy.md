# 证据门控语义（Evidence Gate Policy）

Status: Accepted

Date: 2026-09-01

## Context

AgentLoop 的 Critic 由 LLM 扮演，负责校验 `AnalysisResult` 的目标覆盖、结构完整性和证据绑定。Critic 产出 `CriticResult`，包含 5 个字段：`passed`、`feedback`、`missingRequirements`、`unsupportedClaims`、`requiredTimestamps`。

原始实现中，`enforceEvidenceBounds` 采用"passed 即附带意见则强制翻车"的严格策略：只要 `feedback`、`missingRequirements`、`unsupportedClaims`、`requiredTimestamps` 任一非空，即使 `passed=true` 也强制置为 `false`。这导致 Critic 通过率长期为 0%。

法证分析显示：
1. LLM Critic 常在 `passed=true` 时仍填写 `feedback`（建议性意见），触发强制翻车
2. `unsupportedClaims` 中混入大量"换述类批评"（实际有证据支持，只是措辞不完全一致），这些已被 `CitationAlignment` 修正但仍被 Critic 报告
3. `requiredTimestamps` 中混入"已在 prompt context 内的时间戳"（Executor 已能看到，无需补检）和"超出全量时间轴的幻觉值"

系统需要更精细的门控语义：区分阻断性问题（真缺口）与建议性反馈，过滤无效声明，同时在确定性层确认无问题时覆盖 LLM 的 fail 判断。

## Decision

提取 `EvidenceGatePolicy` 组件，实现证据门控策略：

### 1. 过滤 `unsupportedClaims`

逐条检查 LLM 声明的 unsupported claim：
- 用 `CitationAlignmentService.bindClaim()` 绑定到对应 conclusion
- 若绑定成功，用 `EvidenceVerificationService.supportsClaim()` 复核
- 若复核通过（实际有支持）：降级为 `feedback`，前缀"（未核验的批评）"，不阻断
- 若绑定失败或复核不通过：保留为 blocking，加入 `blockingUnsupported`

### 2. 过滤 `requiredTimestamps`

两阶段过滤：
1. 丢弃超出全量 context 时间轴的幻觉值
2. 丢弃已在 prompt context 内的值（Executor 已能看到，补检无意义）

降级逻辑：若所有 claims 都已被确定性层确认支持（`blockingUnsupported` 为空），则 `requiredTimestamps` 整体降级为 `feedback`，前缀"（未核验的批评,现有证据已足够）"。

### 3. 覆盖 LLM 的 fail 判断

在 `AgentLoopService.enforceEvidenceBounds` 中：
- 先调用 `EvidenceGatePolicy.filterDeclaredProblems()` 过滤
- 再用确定性层复核：检查每条 evidence 是否 `supported`，每个 conclusion 是否有支持的 evidence
- 若确定性层确认无问题（`invalidEvidence` 和 `unsupportedClaims` 均为空）且无阻断性问题（`missingRequirements`、`unsupportedClaims`、`requiredTimestamps` 均为空），则覆盖 LLM 的 `passed=false` 为 `passed=true`

### 4. 职责分离

`EvidenceGatePolicy` 作为 Spring `@Component` 注入到 `AgentLoopService`：
- `EvidenceGatePolicy`：负责过滤 LLM 声明，区分真缺口与建议性反馈
- `AgentLoopService.enforceEvidenceBounds`：负责调用 policy、确定性复核、passed 覆盖
- `EvidenceVerificationService`：负责逐字校验，不参与门控决策

## Consequences / Trade-offs

**收益：**

- Critic 通过率从 0% 提升至 51.16%，硬门通过率从 39.53% 提升至 65.12%
- 消除"passed=true 但附带意见则强制翻车"的过度严格策略
- 过滤换述类批评和幻觉时间戳，减少无谓的定向补检
- 门控逻辑独立为 policy 组件，更易测试和维护
- 确定性层复核保证：即使 LLM 误判，只要有证据支持就能通过

**代价：**

- 增加约 1-2ms 延迟（过滤 + 复核）
- 逻辑复杂度提高，需要充分测试覆盖
- 覆盖 LLM fail 判断可能掩盖真实问题（缓解：仅在确定性层确认无问题时覆盖）

**风险：**

- 过滤逻辑过于宽松可能放过真缺口（缓解：确定性层复核作为最后防线）
- 覆盖 LLM fail 判断可能导致 Critic 通过但实际有问题的结果（缓解：EVS 逐字校验仍生效，伪造引用仍会被拦）
- policy 与 AgentLoopService 的职责边界需要清晰维护

## Validation

- `EvidenceGatePolicyTest`：2 个测试用例
  - `downgradesRequiredTimestampsWhenAllClaimsSupported`：验证降级逻辑
  - `keepsRequiredTimestampsBlockingWhenEvidenceGenuinelyMissing`：验证证据缺失时保持 blocking
- `AgentLoopServiceTest#downgradesRequiredTimestampsToFeedbackWhenAllClaimsSupported`：集成测试验证端到端行为
- 2026-09-01 基准测试结果：Critic 通过率 51.16%，硬门通过率 65.12%

## Related code

- `service/EvidenceGatePolicy` - 证据门控策略核心逻辑
- `service/AgentLoopService#enforceEvidenceBounds` - 调用 policy + 确定性复核 + passed 覆盖
- `service/EvidenceVerificationService` - 逐字校验
- `service/CitationAlignmentService#bindClaim` - claim 绑定到 conclusion
