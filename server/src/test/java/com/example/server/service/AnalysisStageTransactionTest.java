package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.example.server.repository.AnalysisTaskEventOutboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisStageTransactionTest {

    private final AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
    private final AnalysisTaskEventOutboxRepository outbox = mock(AnalysisTaskEventOutboxRepository.class);
    private final AnalysisStageTransaction transaction =
            new AnalysisStageTransaction(checkpoints, new AnalysisStagePolicy(), outbox);

    @Test
    void advancesStageBeforeEnqueuingItsEvent() {
        TaskStatus status = TaskStatus.of(TaskStatus.State.PROCESSING, "开始消费");
        when(checkpoints.loadPersistedStage(7L, "总结课程", AnalysisMode.GENERAL))
                .thenReturn(TaskStage.QUEUED);
        when(checkpoints.compareAndSetStage(
                7L, "总结课程", AnalysisMode.GENERAL, TaskStage.QUEUED, TaskStage.CONSUMING))
                .thenReturn(true);
        when(outbox.enqueue(
                7L,
                TaskEventService.analysisKey(7L, "总结课程", AnalysisMode.GENERAL),
                TaskEvent.of(status, TaskStage.CONSUMING)))
                .thenReturn(31L);

        assertEquals(31L, transaction.advanceAndEnqueue(
                7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING));

        InOrder order = inOrder(checkpoints, outbox);
        order.verify(checkpoints).compareAndSetStage(
                7L, "总结课程", AnalysisMode.GENERAL, TaskStage.QUEUED, TaskStage.CONSUMING);
        order.verify(outbox).enqueue(
                7L,
                TaskEventService.analysisKey(7L, "总结课程", AnalysisMode.GENERAL),
                TaskEvent.of(status, TaskStage.CONSUMING));
    }

    @Test
    void sameStageNotificationStillGetsAStoredEvent() {
        TaskStatus status = TaskStatus.of(TaskStatus.State.PROCESSING, "开始消费");
        when(checkpoints.loadPersistedStage(7L, "总结课程", AnalysisMode.GENERAL))
                .thenReturn(TaskStage.CONSUMING);
        when(outbox.enqueue(
                7L,
                TaskEventService.analysisKey(7L, "总结课程", AnalysisMode.GENERAL),
                TaskEvent.of(status, TaskStage.CONSUMING)))
                .thenReturn(32L);

        assertEquals(32L, transaction.advanceAndEnqueue(
                7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING));

        verify(checkpoints, never()).compareAndSetStage(
                7L, "总结课程", AnalysisMode.GENERAL,
                TaskStage.CONSUMING, TaskStage.CONSUMING);
    }

    @Test
    void concurrentStageChangeDoesNotEnqueueAStaleEvent() {
        TaskStatus status = TaskStatus.of(TaskStatus.State.PROCESSING, "开始消费");
        when(checkpoints.loadPersistedStage(7L, "总结课程", AnalysisMode.GENERAL))
                .thenReturn(TaskStage.QUEUED);
        when(checkpoints.compareAndSetStage(
                7L, "总结课程", AnalysisMode.GENERAL, TaskStage.QUEUED, TaskStage.CONSUMING))
                .thenReturn(false);

        assertThrows(AnalysisStageService.ConcurrentTransitionException.class,
                () -> transaction.advanceAndEnqueue(
                        7L, "总结课程", AnalysisMode.GENERAL, status, TaskStage.CONSUMING));

        verify(outbox, never()).enqueue(
                7L,
                TaskEventService.analysisKey(7L, "总结课程", AnalysisMode.GENERAL),
                TaskEvent.of(status, TaskStage.CONSUMING));
    }
}
