package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisStageServiceTest {

    private final AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
    private final TaskEventService events = mock(TaskEventService.class);
    private final AnalysisStagePolicy policy = new AnalysisStagePolicy();
    private final AnalysisStageService service = new AnalysisStageService(checkpoints, events, policy);

    @Test
    void persistsStageBeforePublishingEvent() {
        TaskStatus status = TaskStatus.of(TaskStatus.State.PROCESSING, "开始消费");
        when(checkpoints.loadPersistedStage(7L, "总结课程", AnalysisMode.GENERAL))
                .thenReturn(TaskStage.QUEUED);
        when(checkpoints.compareAndSetStage(
                7L, "总结课程", AnalysisMode.GENERAL, TaskStage.QUEUED, TaskStage.CONSUMING))
                .thenReturn(true);

        service.transition(7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING);

        InOrder order = inOrder(checkpoints, events);
        order.verify(checkpoints).compareAndSetStage(
                7L, "总结课程", AnalysisMode.GENERAL, TaskStage.QUEUED, TaskStage.CONSUMING);
        order.verify(events).publishAnalysis(
                7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING);
    }

    @Test
    void publishesPayloadStageWithoutWritingItTwice() {
        TaskStatus status = TaskStatus.of(TaskStatus.State.PROCESSING, "Planner 已完成");
        when(checkpoints.loadPersistedStage(7L, "总结课程", AnalysisMode.GENERAL))
                .thenReturn(TaskStage.PLAN_COMPLETED);

        service.transition(7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.PLAN_COMPLETED);

        verify(checkpoints, never()).compareAndSetStage(
                7L, "总结课程", AnalysisMode.GENERAL,
                TaskStage.PLAN_COMPLETED, TaskStage.PLAN_COMPLETED);
        verify(events).publishAnalysis(
                7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.PLAN_COMPLETED);
    }

    @Test
    void rejectsInvalidTransitionWithoutPublishing() {
        TaskStatus status = TaskStatus.of(TaskStatus.State.PROCESSING, "重新执行");
        when(checkpoints.loadPersistedStage(7L, "总结课程", AnalysisMode.GENERAL))
                .thenReturn(TaskStage.COMPLETED);

        assertThrows(IllegalStateException.class, () -> service.transition(
                7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.EXECUTOR_STARTED));

        verify(checkpoints, never()).compareAndSetStage(
                7L, "总结课程", AnalysisMode.GENERAL,
                TaskStage.COMPLETED, TaskStage.EXECUTOR_STARTED);
        verify(events, never()).publishAnalysis(
                7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.EXECUTOR_STARTED);
    }

    @Test
    void rejectsConcurrentTransitionWithoutPublishingStaleEvent() {
        TaskStatus status = TaskStatus.of(TaskStatus.State.PROCESSING, "开始消费");
        when(checkpoints.loadPersistedStage(7L, "总结课程", AnalysisMode.GENERAL))
                .thenReturn(TaskStage.QUEUED);
        when(checkpoints.compareAndSetStage(
                7L, "总结课程", AnalysisMode.GENERAL, TaskStage.QUEUED, TaskStage.CONSUMING))
                .thenReturn(false);

        assertThrows(AnalysisStageService.ConcurrentTransitionException.class, () -> service.transition(
                7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING));

        verify(events, never()).publishAnalysis(
                7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING);
    }

    @Test
    void keepsEventFallbackWhenCheckpointIsUnavailable() {
        TaskStatus status = TaskStatus.of(TaskStatus.State.PROCESSING, "开始消费");
        when(checkpoints.loadPersistedStage(7L, "总结课程", AnalysisMode.GENERAL))
                .thenThrow(new IllegalStateException("database unavailable"));

        service.transition(7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING);

        verify(events).publishAnalysis(
                7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING);
    }
}
