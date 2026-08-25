package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Persists an analysis stage before publishing its observable task event. */
@Service
public class AnalysisStageService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisStageService.class);

    private final AgentCheckpointService checkpointService;
    private final TaskEventService taskEventService;
    private final AnalysisStagePolicy stagePolicy;

    public AnalysisStageService(AgentCheckpointService checkpointService,
                                TaskEventService taskEventService,
                                AnalysisStagePolicy stagePolicy) {
        this.checkpointService = checkpointService;
        this.taskEventService = taskEventService;
        this.stagePolicy = stagePolicy;
    }

    public void transition(Long mediaId,
                           String goal,
                           AnalysisMode mode,
                           TaskStatus status,
                           TaskStage nextStage) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        try {
            TaskStage currentStage = checkpointService.loadStage(mediaId, goal, resolvedMode);
            stagePolicy.requireAllowed(currentStage, nextStage);
            // Payload checkpoints persist their stage atomically. A same-stage call only publishes it.
            if (currentStage != nextStage) {
                checkpointService.saveStage(mediaId, goal, resolvedMode, nextStage);
            }
        } catch (AnalysisStagePolicy.InvalidTransitionException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("analysis_stage_checkpoint_failed mediaId={} stage={}", mediaId, nextStage, e);
        }
        taskEventService.publishAnalysis(mediaId, goal, resolvedMode, status, nextStage);
    }
}
