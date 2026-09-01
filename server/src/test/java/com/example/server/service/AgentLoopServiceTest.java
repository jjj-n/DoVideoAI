package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import com.example.server.utils.DeepSeekUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

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
                new CitationAlignmentService(), stages, 2, 100);

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
        when(model.execute(context, plan, null, "")).thenReturn(result);
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

    private AgentLoopService newService(DeepSeekUtils model,
                                        LongVideoContextService contextService,
                                        AgentCheckpointService checkpoints,
                                        AgentTelemetry telemetry,
                                        EvidenceVerificationService evidenceVerification,
                                        CitationAlignmentService citationAlignment,
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
                stages,
                maxRounds,
                120_000,
                maxEstimatedTokens,
                0);
    }
}
