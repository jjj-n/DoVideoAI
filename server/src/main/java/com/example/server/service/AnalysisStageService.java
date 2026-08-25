package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Advances an analysis stage and publishes its durable observable event. */
@Service
public class AnalysisStageService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisStageService.class);

    private final AnalysisStageTransaction stageTransaction;
    private final AnalysisTaskEventOutboxRelay outboxRelay;
    private final TaskEventService taskEventService;

    public AnalysisStageService(AnalysisStageTransaction stageTransaction,
                                AnalysisTaskEventOutboxRelay outboxRelay,
                                TaskEventService taskEventService) {
        this.stageTransaction = stageTransaction;
        this.outboxRelay = outboxRelay;
        this.taskEventService = taskEventService;
    }

    public void transition(Long mediaId,
                           String goal,
                           AnalysisMode mode,
                           TaskStatus status,
                           TaskStage nextStage) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        final long eventId;
        try {
            eventId = stageTransaction.advanceAndEnqueue(
                    mediaId, goal, resolvedMode, status, nextStage);
        } catch (AnalysisStagePolicy.InvalidTransitionException | ConcurrentTransitionException e) {
            throw e;
        } catch (RuntimeException e) {
            // Preserve the existing degradation path when MySQL itself is unavailable.
            log.warn("analysis_stage_transaction_failed mediaId={} stage={}", mediaId, nextStage, e);
            taskEventService.publishAnalysis(mediaId, goal, resolvedMode, status, nextStage);
            return;
        }
        outboxRelay.publishNow(eventId);
    }

    public static class ConcurrentTransitionException extends IllegalStateException {
        public ConcurrentTransitionException(TaskStage expectedStage, TaskStage nextStage) {
            super("分析任务阶段已被并发修改，拒绝迁移: " + expectedStage + " -> " + nextStage);
        }
    }
}
