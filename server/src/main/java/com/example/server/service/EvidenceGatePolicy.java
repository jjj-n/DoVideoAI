package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 证据门控策略：过滤 LLM Critic 的声明，区分真缺口与换述类批评。
 *
 * <p>职责：
 * <ul>
 *   <li>过滤 unsupportedClaims：换述类批评降级为 feedback，不阻断</li>
 *   <li>过滤 requiredTimestamps：丢弃幻觉值和在 prompt context 内的值</li>
 *   <li>降级逻辑：当所有 claims 已支持时，requiredTimestamps 降级为 feedback</li>
 * </ul>
 *
 * <p>已知测试覆盖缺口：requiredTimestamps 在证据真实缺失时保持 blocking 的路径
 * 未有回归测试。后续补充。
 */
@Component
public class EvidenceGatePolicy {

    private final CitationAlignmentService citationAlignmentService;
    private final EvidenceVerificationService evidenceVerificationService;

    public EvidenceGatePolicy(CitationAlignmentService citationAlignmentService,
                              EvidenceVerificationService evidenceVerificationService) {
        this.citationAlignmentService = citationAlignmentService;
        this.evidenceVerificationService = evidenceVerificationService;
    }

    /**
     * 过滤 LLM Critic 声明的问题，并把"passed 即附带意见"的强制翻车收窄为阻断性问题才翻:
     * <ul>
     *   <li>纯 feedback 是建议性意见,不再推翻 passed;</li>
     *   <li>声明的 unsupportedClaims 逐条绑定 conclusion 并复核:对应结论实际有支持的是换述类
     *       批评,绑定不到结论的无法定位,两者都移入 feedback,不阻断;</li>
     *   <li>声明的 requiredTimestamps 丢弃超出全量时间轴的幻觉值,以及覆盖段已在 prompt context
     *       内的值(Executor 已看得见的片段补检无意义)。</li>
     *   <li>当所有 claims 都已被确定性层确认支持时,requiredTimestamps 降级为 feedback
     *       (LLM 想要更多证据,但现有证据已足够)</li>
     * </ul>
     */
    public AgentState.CriticResult filterDeclaredProblems(VideoContext fullContext,
                                                          VideoContext promptContext,
                                                          AnalysisResult result,
                                                          AgentState.CriticResult critique) {
        List<String> blockingUnsupported = new ArrayList<>();
        List<String> feedback = new ArrayList<>(critique.feedback());

        // 处理 unsupportedClaims
        for (String declared : critique.unsupportedClaims()) {
            String conclusion = result == null || result.conclusions() == null ? null
                    : citationAlignmentService.bindClaim(declared, result.conclusions());
            boolean genuinelyUnsupported = conclusion != null
                    && result.evidence().stream().noneMatch(evidence ->
                            evidenceVerificationService.supportsClaim(fullContext, conclusion, evidence));
            if (genuinelyUnsupported) {
                blockingUnsupported.add(declared);
            } else {
                feedback.add("（未核验的批评）" + declared);
            }
        }

        // 处理 requiredTimestamps
        List<Long> requiredTimestamps = critique.requiredTimestamps().stream()
                .filter(timestamp -> timestampCovered(fullContext, timestamp))
                .filter(timestamp -> !timestampCovered(promptContext, timestamp))
                .toList();

        // 降级逻辑：如果所有 claims 都已被确定性层确认支持,则 requiredTimestamps 降级为 feedback
        // 检查方式：result 中每个 conclusion 都有至少一个 evidence 被 EVS 确认支持
        boolean allClaimsSupported = result != null && result.conclusions() != null
                && result.conclusions().stream().allMatch(conclusion ->
                        result.evidence().stream().anyMatch(evidence ->
                                evidenceVerificationService.supportsClaim(fullContext, conclusion, evidence)));

        if (allClaimsSupported && blockingUnsupported.isEmpty() && !requiredTimestamps.isEmpty()) {
            feedback.add("（未核验的批评,现有证据已足够）Critic 请求补检时间戳: " + requiredTimestamps);
            requiredTimestamps = List.of();
        }

        boolean hasBlockingProblems = !critique.missingRequirements().isEmpty()
                || !blockingUnsupported.isEmpty()
                || !requiredTimestamps.isEmpty();
        boolean passed = critique.passed() && !hasBlockingProblems;

        if (passed && feedback.equals(critique.feedback())) {
            return new AgentState.CriticResult(
                    true, critique.feedback(), critique.missingRequirements(), blockingUnsupported, requiredTimestamps);
        }
        if (!passed
                && feedback.equals(critique.feedback())
                && critique.missingRequirements().isEmpty()
                && critique.unsupportedClaims().isEmpty()
                && critique.requiredTimestamps().isEmpty()) {
            feedback.add("重新检查目标覆盖、结构完整性和证据绑定");
        }

        return new AgentState.CriticResult(
                passed, feedback, critique.missingRequirements(), blockingUnsupported, requiredTimestamps);
    }

    private boolean timestampCovered(VideoContext context, long timestampMs) {
        return context != null && context.segments().stream()
                .anyMatch(segment -> timestampMs >= segment.startMs() && timestampMs < segment.endMs());
    }
}
