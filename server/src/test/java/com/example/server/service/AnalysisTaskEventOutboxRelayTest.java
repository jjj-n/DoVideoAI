package com.example.server.service;

import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.example.server.repository.AnalysisTaskEventOutboxRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisTaskEventOutboxRelayTest {

    private final AnalysisTaskEventOutboxRepository outbox = mock(AnalysisTaskEventOutboxRepository.class);
    private final TaskEventService events = mock(TaskEventService.class);
    private final AnalysisTaskEventOutboxRelay relay = new AnalysisTaskEventOutboxRelay(
            outbox, events, 50, Duration.ofSeconds(30), Duration.ofDays(7));

    @Test
    void marksClaimedEventPublishedAfterDelivery() {
        TaskEvent event = TaskEvent.of(
                TaskStatus.of(TaskStatus.State.PROCESSING, "开始消费"), TaskStage.CONSUMING);
        AnalysisTaskEventOutboxRepository.ClaimedEvent claimed =
                new AnalysisTaskEventOutboxRepository.ClaimedEvent(
                        31L, "claim-1", "analysis:7:digest", event, 0);
        when(outbox.claim(31L, Duration.ofSeconds(30))).thenReturn(Optional.of(claimed));

        relay.publishNow(31L);

        verify(events).publishStored("analysis:7:digest", event);
        verify(outbox).markPublished(claimed);
    }

    @Test
    void leavesFailedDeliveryForScheduledRetry() {
        TaskEvent event = TaskEvent.of(
                TaskStatus.of(TaskStatus.State.PROCESSING, "开始消费"), TaskStage.CONSUMING);
        AnalysisTaskEventOutboxRepository.ClaimedEvent claimed =
                new AnalysisTaskEventOutboxRepository.ClaimedEvent(
                        31L, "claim-1", "analysis:7:digest", event, 0);
        IllegalStateException failure = new IllegalStateException("publish unavailable");
        when(outbox.claim(31L, Duration.ofSeconds(30))).thenReturn(Optional.of(claimed));
        doThrow(failure).when(events).publishStored("analysis:7:digest", event);

        relay.publishNow(31L);

        verify(outbox).markFailed(claimed, failure);
        verify(outbox, never()).markPublished(claimed);
    }

    @Test
    void skipsAnEventClaimedByAnotherInstance() {
        when(outbox.claim(31L, Duration.ofSeconds(30))).thenReturn(Optional.empty());

        relay.publishNow(31L);

        verifyNoInteractions(events);
    }
}
