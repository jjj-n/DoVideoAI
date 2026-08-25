package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisStageServiceTest {

    private final AnalysisStageTransaction transaction = mock(AnalysisStageTransaction.class);
    private final AnalysisTaskEventOutboxRelay relay = mock(AnalysisTaskEventOutboxRelay.class);
    private final TaskEventService events = mock(TaskEventService.class);
    private final AnalysisStageService service = new AnalysisStageService(transaction, relay, events);

    @Test
    void publishesDurableEventAfterTheTransactionCommits() {
        TaskStatus status = TaskStatus.of(TaskStatus.State.PROCESSING, "开始消费");
        when(transaction.advanceAndEnqueue(
                7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING))
                .thenReturn(31L);

        service.transition(7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING);

        verify(relay).publishNow(31L);
        verify(events, never()).publishAnalysis(
                7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING);
    }

    @Test
    void rejectsInvalidTransitionWithoutPublishingFallbackEvent() {
        TaskStatus status = TaskStatus.of(TaskStatus.State.PROCESSING, "重新执行");
        doThrow(new AnalysisStagePolicy.InvalidTransitionException("invalid"))
                .when(transaction).advanceAndEnqueue(
                        7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.EXECUTOR_STARTED);

        assertThrows(AnalysisStagePolicy.InvalidTransitionException.class, () -> service.transition(
                7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.EXECUTOR_STARTED));

        verifyNoInteractions(relay);
        verify(events, never()).publishAnalysis(
                7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.EXECUTOR_STARTED);
    }

    @Test
    void keepsDirectEventFallbackWhenTheTransactionInfrastructureIsUnavailable() {
        TaskStatus status = TaskStatus.of(TaskStatus.State.PROCESSING, "开始消费");
        doThrow(new IllegalStateException("database unavailable"))
                .when(transaction).advanceAndEnqueue(
                        7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING);

        service.transition(7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING);

        verify(events).publishAnalysis(
                7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING);
        verifyNoInteractions(relay);
    }
}
