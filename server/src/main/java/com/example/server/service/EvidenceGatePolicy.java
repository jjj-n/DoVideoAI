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
     * 过滤 LLM Critic 声明的问题，并把"passed 即附带意见"的强制翻车收窄为阻断性问题才翻。
     *
     * <p>处理流程：
     * <ol>
     *   <li><b>过滤 unsupportedClaims</b>：
     *     <ul>
     *       <li>用 {@link CitationAlignmentService#bindClaim} 绑定到对应 conclusion</li>
     *       <li>若绑定成功，用 {@link EvidenceVerificationService#supportsClaim} 复核</li>
     *       <li>若复核通过（实际有支持）：降级为 feedback，前缀"（未核验的批评）"</li>
     *       <li>若绑定失败或复核不通过：保留为 blocking</li>
     *     </ul>
     *   </li>
     *   <li><b>过滤 requiredTimestamps</b>：
     *     <ul>
     *       <li>丢弃超出全量 context 时间轴的幻觉值</li>
     *       <li>丢弃已在 prompt context 内的值（Executor 已能看到，补检无意义）</li>
     *     </ul>
     *   </li>
     *   <li><b>降级逻辑</b>：
     *     <ul>
     *       <li>检查所有 claims 是否都已被确定性层确认支持</li>
     *       <li>若是且无 blocking unsupportedClaims：requiredTimestamps 整体降级为 feedback</li>
     *     </ul>
     *   </li>
     *   <li><b>计算 passed</b>：
     *     <ul>
     *       <li>若 critique.passed()=true 且无阻断性问题：保持 passed=true</li>
     *       <li>否则：passed=false</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>注意：本方法不负责覆盖 LLM 的 fail 判断。passed 覆盖在
     * {@link AgentLoopService#enforceEvidenceBounds} 中完成。
     *
     * @param fullContext 全量 VideoContext，用于 EVS 复核
     * @param promptContext Executor 看到的裁剪 VideoContext，用于过滤已在视野内的时间戳
     * @param result Executor 输出的 AnalysisResult，用于绑定 claim
     * @param critique LLM Critic 输出的 CriticResult
     * @return 过滤后的 CriticResult
     */
    public AgentState.CriticResult filterDeclaredProblems(VideoContext fullContext,
                                                          VideoContext promptContext,
                                                          AnalysisResult result,
                                                          AgentState.CriticResult critique) {
        List<String> blockingUnsupported = new ArrayList<>();
        List<String> feedback = new ArrayList<>(critique.feedback());

        // Step 1: 处理 unsupportedClaims
        // 逐条绑定到 conclusion 并用 EVS 复核，区分真缺口与换述类批评
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

        // Step 2: 处理 requiredTimestamps
        // 过滤掉幻觉值和已在 prompt context 内的值
        List<Long> requiredTimestamps = critique.requiredTimestamps().stream()
                .filter(timestamp -> timestampCovered(fullContext, timestamp))
                .filter(timestamp -> !timestampCovered(promptContext, timestamp))
                .toList();

        // Step 3: 降级逻辑
        // 检查所有 claims 是否都已被确定性层确认支持
        // 若是且无 blocking unsupportedClaims：requiredTimestamps 整体降级为 feedback
        boolean allClaimsSupported = result != null && result.conclusions() != null
                && result.conclusions().stream().allMatch(conclusion ->
                        result.evidence().stream().anyMatch(evidence ->
                                evidenceVerificationService.supportsClaim(fullContext, conclusion, evidence)));

        if (allClaimsSupported && blockingUnsupported.isEmpty() && !requiredTimestamps.isEmpty()) {
            feedback.add("（未核验的批评,现有证据已足够）Critic 请求补检时间戳: " + requiredTimestamps);
            requiredTimestamps = List.of();
        }

        // Step 4: 计算 passed
        // 若 critique.passed()=true 且无阻断性问题：保持 passed=true
        // 否则：passed=false
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
