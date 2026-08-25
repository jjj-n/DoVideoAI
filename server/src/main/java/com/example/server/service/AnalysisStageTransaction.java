package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.example.server.repository.AnalysisTaskEventOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits a canonical stage change and its observable event in one database transaction. */
@Service
public class AnalysisStageTransaction {

    private final AgentCheckpointService checkpointService;
    private final AnalysisStagePolicy stagePolicy;
    private final AnalysisTaskEventOutboxRepository outboxRepository;

    public AnalysisStageTransaction(AgentCheckpointService checkpointService,
                                    AnalysisStagePolicy stagePolicy,
                                    AnalysisTaskEventOutboxRepository outboxRepository) {
        this.checkpointService = checkpointService;
        this.stagePolicy = stagePolicy;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public long advanceAndEnqueue(Long mediaId,
                                  String goal,
                                  AnalysisMode mode,
                                  TaskStatus status,
                                  TaskStage nextStage) {
        TaskStage currentStage = checkpointService.loadPersistedStage(mediaId, goal, mode);
        stagePolicy.requireAllowed(currentStage, nextStage);
        if (currentStage != nextStage && !checkpointService.compareAndSetStage(
                mediaId, goal, mode, currentStage, nextStage)) {
            throw new AnalysisStageService.ConcurrentTransitionException(currentStage, nextStage);
        }
        return outboxRepository.enqueue(
                mediaId,
                TaskEventService.analysisKey(mediaId, goal, mode),
                TaskEvent.of(status, nextStage));
    }
}
