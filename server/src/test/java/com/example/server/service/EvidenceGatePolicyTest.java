package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EvidenceGatePolicy 单元测试。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>requiredTimestamps 在所有 claims 已支持时降级为 feedback</li>
 *   <li>requiredTimestamps 在证据真实缺失时保持 blocking</li>
 * </ul>
 */
class EvidenceGatePolicyTest {

    private CitationAlignmentService citationAlignment;
    private EvidenceVerificationService evidenceVerification;
    private EvidenceGatePolicy policy;

    @BeforeEach
    void setUp() {
        citationAlignment = new CitationAlignmentService();
        evidenceVerification = mock(EvidenceVerificationService.class);
        policy = new EvidenceGatePolicy(citationAlignment, evidenceVerification);
    }

    @Test
    void downgradesRequiredTimestampsWhenAllClaimsSupported() {
        // 设置：fullContext 包含 [0,60000) 和 [60000,120000)
        //       promptContext 只包含 [0,60000)
        //       测试时间戳 90000：在 fullContext 中被覆盖，在 promptContext 中不被覆盖
        VideoContext fullContext = new VideoContext(
                "lesson.mp4",
                "总结课程",
                List.of(
                        new VideoContext.VideoSegment(0, 60_000, "核心结论", List.of(), List.of()),
                        new VideoContext.VideoSegment(60_000, 120_000, "更多细节", List.of(), List.of())));
        VideoContext promptContext = new VideoContext(
                "lesson.mp4",
                "总结课程",
                List.of(new VideoContext.VideoSegment(0, 60_000, "核心结论", List.of(), List.of())));

        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                1_000, "ASR", "核心结论", "核心结论");
        AnalysisResult result = new AnalysisResult(
                "课程总结", List.of("核心结论"), List.of(evidence), List.of(), List.of(), false);

        // LLM Critic 请求补检时间戳 90000ms
        AgentState.CriticResult critique = new AgentState.CriticResult(
                false, List.of(), List.of(), List.of(), List.of(90_000L));

        // 确定性层确认所有 evidence 和 claims 都支持
        when(evidenceVerification.supported(eq(fullContext), any())).thenReturn(true);
        when(evidenceVerification.supportsClaim(eq(fullContext), anyString(), any())).thenReturn(true);

        AgentState.CriticResult filtered = policy.filterDeclaredProblems(
                fullContext, promptContext, result, critique);

        // 验证：requiredTimestamps 被降级为 feedback
        // 注意：filterDeclaredProblems 不负责覆盖 passed 状态，那是 enforceEvidenceBounds 的职责
        assertTrue(filtered.feedback().stream()
                        .anyMatch(f -> f.contains("未核验的批评") && f.contains("[90000]")),
                "requiredTimestamps should be moved to feedback: " + filtered.feedback());
        assertTrue(filtered.requiredTimestamps().isEmpty(),
                "requiredTimestamps should be empty after downgrade");
        // 原始 critique.passed() 为 false，filterDeclaredProblems 不会改变它
        assertFalse(filtered.passed(),
                "filterDeclaredProblems should not override passed status");
    }

    @Test
    void keepsRequiredTimestampsBlockingWhenEvidenceGenuinelyMissing() {
        // 同样的 context 设置
        VideoContext fullContext = new VideoContext(
                "lesson.mp4",
                "总结课程",
                List.of(
                        new VideoContext.VideoSegment(0, 60_000, "核心结论", List.of(), List.of()),
                        new VideoContext.VideoSegment(60_000, 120_000, "更多细节", List.of(), List.of())));
        VideoContext promptContext = new VideoContext(
                "lesson.mp4",
                "总结课程",
                List.of(new VideoContext.VideoSegment(0, 60_000, "核心结论", List.of(), List.of())));

        // 关键差异：evidence 在 90000ms，但 EVS 会返回 supported=false
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                90_000, "ASR", "缺失的证据", "缺失的结论");
        AnalysisResult result = new AnalysisResult(
                "课程总结", List.of("缺失的结论"), List.of(evidence), List.of(), List.of(), false);

        // LLM Critic 也请求补检时间戳 90000ms
        AgentState.CriticResult critique = new AgentState.CriticResult(
                false, List.of(), List.of(), List.of(), List.of(90_000L));

        // 关键差异：EVS 对 90000ms 的证据返回 supported=false
        when(evidenceVerification.supported(eq(fullContext), any())).thenReturn(false);
        when(evidenceVerification.supportsClaim(eq(fullContext), anyString(), any())).thenReturn(false);

        AgentState.CriticResult filtered = policy.filterDeclaredProblems(
                fullContext, promptContext, result, critique);

        // 验证：当证据确实缺失时，requiredTimestamps 应保持 blocking，Critic 不应通过
        assertFalse(filtered.passed(),
                "Critic should fail when evidence is genuinely missing");
        assertTrue(filtered.requiredTimestamps().contains(90_000L),
                "requiredTimestamps should contain 90000 when evidence is genuinely missing");
        assertEquals(1, filtered.requiredTimestamps().size(),
                "requiredTimestamps should only contain 90000");
    }
}
