package com.example.server.repository;

import com.example.server.dto.TaskEvent;
import com.example.server.entity.AnalysisTaskEventOutbox;
import com.example.server.mapper.AnalysisTaskEventOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AnalysisTaskEventOutboxRepository {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskEventOutboxRepository.class);
    private static final long MAX_RETRY_DELAY_SECONDS = 300;

    private final AnalysisTaskEventOutboxMapper mapper;
    private final ObjectMapper objectMapper;

    public AnalysisTaskEventOutboxRepository(AnalysisTaskEventOutboxMapper mapper,
                                             ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public long enqueue(Long mediaId, String eventKey, TaskEvent event) {
        try {
            AnalysisTaskEventOutbox row = new AnalysisTaskEventOutbox();
            row.setMediaId(mediaId);
            row.setEventKey(eventKey);
            row.setEventPayload(objectMapper.writeValueAsString(event));
            if (mapper.insert(row) != 1 || row.getId() == null) {
                throw new IllegalStateException("任务事件 Outbox 写入未返回主键");
            }
            return row.getId();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("序列化任务事件失败", e);
        }
    }

    public List<Long> findPendingIds(int limit) {
        return mapper.findPendingIds(LocalDateTime.now(), limit);
    }

    public Optional<ClaimedEvent> claim(Long id, Duration lease) {
        LocalDateTime now = LocalDateTime.now();
        String claimToken = UUID.randomUUID().toString();
        if (mapper.claim(id, claimToken, now, now.plus(lease)) != 1) {
            return Optional.empty();
        }
        AnalysisTaskEventOutbox row = mapper.findClaimed(id, claimToken);
        if (row == null) {
            throw new IllegalStateException("任务事件 Outbox 已认领但无法读取: " + id);
        }
        try {
            return Optional.of(new ClaimedEvent(
                    row.getId(), claimToken, row.getEventKey(),
                    objectMapper.readValue(row.getEventPayload(), TaskEvent.class),
                    row.getAttemptCount() == null ? 0 : row.getAttemptCount()));
        } catch (Exception e) {
            releaseFailedClaim(row, claimToken, e.getClass().getSimpleName());
            throw new IllegalStateException("反序列化任务事件 Outbox 失败: " + id, e);
        }
    }

    public void markPublished(ClaimedEvent event) {
        if (mapper.markPublished(event.id(), event.claimToken(), LocalDateTime.now()) != 1) {
            throw new IllegalStateException("任务事件 Outbox 发布确认失败: " + event.id());
        }
    }

    public void markFailed(ClaimedEvent event, RuntimeException error) {
        if (!releaseFailedClaim(event.id(), event.claimToken(), event.attemptCount(),
                error.getClass().getSimpleName())) {
            log.warn("analysis_task_event_outbox_failure_release_lost id={}", event.id());
        }
    }

    public int deletePublishedBefore(LocalDateTime cutoff) {
        return mapper.deletePublishedBefore(cutoff);
    }

    public void deleteByMediaId(Long mediaId) {
        mapper.deleteByMediaId(mediaId);
    }

    private void releaseFailedClaim(AnalysisTaskEventOutbox row,
                                    String claimToken,
                                    String errorType) {
        int attemptCount = row.getAttemptCount() == null ? 0 : row.getAttemptCount();
        if (!releaseFailedClaim(row.getId(), claimToken, attemptCount, errorType)) {
            log.warn("analysis_task_event_outbox_failure_release_lost id={}", row.getId());
        }
    }

    private boolean releaseFailedClaim(Long id,
                                       String claimToken,
                                       int attemptCount,
                                       String errorType) {
        int nextAttempt = attemptCount + 1;
        long delaySeconds = Math.min(MAX_RETRY_DELAY_SECONDS, 1L << Math.min(nextAttempt, 8));
        return mapper.markFailed(
                id, claimToken, LocalDateTime.now().plusSeconds(delaySeconds), errorType) == 1;
    }

    public record ClaimedEvent(
            Long id,
            String claimToken,
            String eventKey,
            TaskEvent event,
            int attemptCount) {
    }
}
