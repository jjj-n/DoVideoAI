package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import com.example.server.utils.DeepSeekUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentLoopServiceTest {

    @Test
    void rejectsRoundBudgetAboveTheTwoRoundAdrLimit() {
        assertThrows(IllegalArgumentException.class, () -> newService(
                mock(DeepSeekUtils.class),
                mock(LongVideoContextService.class),
                mock(AgentCheckpointService.class),
                mock(AgentTelemetry.class),
                mock(EvidenceVerificationService.class),
                mock(CitationAlignmentService.class),
                mock(AgentResultMerge.class),
                mock(AnalysisStageService.class),
                3,
                50_000));
    }

    @Test
    void checksTokenBudgetAfterTheFinalCriticCall() {
        DeepSeekUtils model = mock(DeepSeekUtils.class);
        LongVideoContextService contextService = mock(LongVideoContextService.class);
        AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
        AgentTelemetry telemetry = mock(AgentTelemetry.class);
        EvidenceVerificationService evidenceVerification = mock(EvidenceVerificationService.class);
        AnalysisStageService stages = mock(AnalysisStageService.class);
        AgentLoopService service = newService(
                model, contextService, checkpoints, telemetry, evidenceVerification,
                new CitationAlignmentService(), new AgentResultMerge(), stages, 2, 100);

        VideoContext context = new VideoContext(
                "lesson.mp4",
                "总结课程",
                List.of(new VideoContext.VideoSegment(
                        0, 60_000, "核心结论", List.of(), List.of())));
        AgentState.AgentPlan plan = new AgentState.AgentPlan("总结课程", List.of("提取核心结论"));
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                1_000, "ASR", "核心结论", "核心结论");
        AnalysisResult result = new AnalysisResult(
                "课程总结", List.of("核心结论"), List.of(evidence), List.of(), List.of());
        AgentState.CriticResult critique = new AgentState.CriticResult(
                true, List.of(), List.of(), List.of(), List.of());

        when(contextService.selectRelevant(7L, context)).thenReturn(context);
        when(model.plan(context, "")).thenReturn(plan);
        when(model.execute(context, plan, null, null, "")).thenReturn(result);
        when(model.critique(context, plan, result, "")).thenReturn(critique);
        when(evidenceVerification.supported(eq(context), any())).thenReturn(true);
        when(evidenceVerification.supportsClaim(eq(context), anyString(), any())).thenReturn(true);
        when(telemetry.currentUsage()).thenReturn(
                new AgentTelemetry.BudgetUsage(0, 0),
                new AgentTelemetry.BudgetUsage(0, 0),
                new AgentTelemetry.BudgetUsage(0, 0),
                new AgentTelemetry.BudgetUsage(101, 0));

        assertThrows(
                AgentLoopService.BudgetExceededException.class,
                () -> service.run(7L, context));
    }

    @Test
    void downgradesRequiredTimestampsToFeedbackWhenAllClaimsSupported() {
        DeepSeekUtils model = mock(DeepSeekUtils.class);
        LongVideoContextService contextService = mock(LongVideoContextService.class);
        AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
        AgentTelemetry telemetry = mock(AgentTelemetry.class);
        EvidenceVerificationService evidenceVerification = mock(EvidenceVerificationService.class);
        CitationAlignmentService citationAlignment = new CitationAlignmentService();
        AgentResultMerge merge = new AgentResultMerge();
        AnalysisStageService stages = mock(AnalysisStageService.class);
        AgentLoopService service = newService(
                model, contextService, checkpoints, telemetry, evidenceVerification,
                citationAlignment, merge, stages, 2, 100_000);

        // 设计：fullContext 包含 [0,60000) 和 [60000,120000)
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

        AgentState.AgentPlan plan = new AgentState.AgentPlan("总结课程", List.of("提取核心结论"));
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                1_000, "ASR", "核心结论", "核心结论");
        AnalysisResult result = new AnalysisResult(
                "课程总结", List.of("核心结论"), List.of(evidence), List.of(), List.of(), false);

        // LLM Critic 请求补检时间戳 90000ms
        AgentState.CriticResult critique = new AgentState.CriticResult(
                false, List.of(), List.of(), List.of(), List.of(90_000L));

        when(contextService.selectRelevant(7L, fullContext)).thenReturn(promptContext);
        when(model.plan(promptContext, "")).thenReturn(plan);
        when(model.execute(promptContext, plan, null, null, "")).thenReturn(result);
        when(model.critique(promptContext, plan, result, "")).thenReturn(critique);
        // 确定性层确认所有 evidence 和 claims 都支持
        when(evidenceVerification.supported(eq(fullContext), any())).thenReturn(true);
        when(evidenceVerification.supportsClaim(eq(fullContext), anyString(), any())).thenReturn(true);
        when(telemetry.currentUsage()).thenReturn(
                new AgentTelemetry.BudgetUsage(0, 0),
                new AgentTelemetry.BudgetUsage(0, 0));

        AgentState state = service.run(7L, fullContext);

        // requiredTimestamps 应被降级为 feedback，Critic 应通过
        assertTrue(state.critique().passed(),
                "Critic should pass when all claims are supported and requiredTimestamps is downgraded");
        assertTrue(state.critique().feedback().stream()
                        .anyMatch(f -> f.contains("未核验的批评") && f.contains("[90000]")),
                "requiredTimestamps should be moved to feedback: " + state.critique().feedback());
        assertTrue(state.critique().requiredTimestamps().isEmpty(),
                "requiredTimestamps should be empty after downgrade");
    }

    private AgentLoopService newService(DeepSeekUtils model,
                                        LongVideoContextService contextService,
                                        AgentCheckpointService checkpoints,
                                        AgentTelemetry telemetry,
                                        EvidenceVerificationService evidenceVerification,
                                        CitationAlignmentService citationAlignment,
                                        AgentResultMerge agentResultMerge,
                                        AnalysisStageService stages,
                                        int maxRounds,
                                        long maxEstimatedTokens) {
        return new AgentLoopService(
                model,
                contextService,
                checkpoints,
                telemetry,
                evidenceVerification,
                citationAlignment,
                agentResultMerge,
                stages,
                maxRounds,
                120_000,
                maxEstimatedTokens,
                0);
    }
}
