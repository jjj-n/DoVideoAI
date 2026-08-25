package com.example.server.service;

import com.example.server.repository.AnalysisTaskEventOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/** Claims committed task events and publishes them with at-least-once delivery. */
@Service
public class AnalysisTaskEventOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskEventOutboxRelay.class);

    private final AnalysisTaskEventOutboxRepository outboxRepository;
    private final TaskEventService taskEventService;
    private final int batchSize;
    private final Duration claimLease;
    private final Duration retention;

    public AnalysisTaskEventOutboxRelay(
            AnalysisTaskEventOutboxRepository outboxRepository,
            TaskEventService taskEventService,
            @Value("${task-event.outbox.batch-size:50}") int batchSize,
            @Value("${task-event.outbox.claim-lease:30s}") Duration claimLease,
            @Value("${task-event.outbox.retention:7d}") Duration retention) {
        this.outboxRepository = outboxRepository;
        this.taskEventService = taskEventService;
        this.batchSize = batchSize;
        this.claimLease = claimLease;
        this.retention = retention;
    }

    public void publishNow(Long eventId) {
        AnalysisTaskEventOutboxRepository.ClaimedEvent claimed = null;
        try {
            claimed = outboxRepository.claim(eventId, claimLease).orElse(null);
            if (claimed == null) return;
            taskEventService.publishStored(claimed.eventKey(), claimed.event());
            outboxRepository.markPublished(claimed);
        } catch (RuntimeException e) {
            if (claimed != null) {
                try {
                    outboxRepository.markFailed(claimed, e);
                } catch (RuntimeException releaseError) {
                    e.addSuppressed(releaseError);
                }
            }
            log.warn("analysis_task_event_outbox_publish_failed id={}", eventId, e);
        }
    }

    @Scheduled(fixedDelayString = "${task-event.outbox.poll-interval-ms:2000}")
    public void publishPending() {
        try {
            outboxRepository.findPendingIds(batchSize).forEach(this::publishNow);
        } catch (RuntimeException e) {
            log.warn("analysis_task_event_outbox_poll_failed", e);
        }
    }

    @Scheduled(cron = "${task-event.outbox.cleanup-cron:0 0 3 * * *}")
    public void cleanupPublished() {
        try {
            int deleted = outboxRepository.deletePublishedBefore(LocalDateTime.now().minus(retention));
            if (deleted > 0) {
                log.info("analysis_task_event_outbox_cleaned count={}", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("analysis_task_event_outbox_cleanup_failed", e);
        }
    }
}
