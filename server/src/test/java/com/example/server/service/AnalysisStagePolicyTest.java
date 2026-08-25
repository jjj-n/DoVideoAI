package com.example.server.service;

import com.example.server.dto.TaskStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisStagePolicyTest {

    private final AnalysisStagePolicy policy = new AnalysisStagePolicy();

    @Test
    void acceptsHappyPathAndTargetedCriticRetry() {
        assertAllowed(null, TaskStage.QUEUED);
        assertAllowed(TaskStage.QUEUED, TaskStage.CONSUMING);
        assertAllowed(TaskStage.CONSUMING, TaskStage.VIDEO_CONTEXT);
        assertAllowed(TaskStage.VIDEO_CONTEXT, TaskStage.AGENT_LOOP);
        assertAllowed(TaskStage.AGENT_LOOP, TaskStage.PLAN_COMPLETED);
        assertAllowed(TaskStage.PLAN_COMPLETED, TaskStage.EXECUTOR_STARTED);
        assertAllowed(TaskStage.EXECUTOR_STARTED, TaskStage.EXECUTOR_COMPLETED);
        assertAllowed(TaskStage.EXECUTOR_COMPLETED, TaskStage.CRITIC_STARTED);
        assertAllowed(TaskStage.CRITIC_STARTED, TaskStage.CRITIC_RETRY_REQUIRED);
        assertAllowed(TaskStage.CRITIC_RETRY_REQUIRED, TaskStage.EVIDENCE_REFRESHED);
        assertAllowed(TaskStage.EVIDENCE_REFRESHED, TaskStage.PLAN_COMPLETED);
        assertAllowed(TaskStage.CRITIC_STARTED, TaskStage.CRITIC_PASSED);
        assertAllowed(TaskStage.CRITIC_PASSED, TaskStage.ANALYSIS_COMPLETED);
        assertAllowed(TaskStage.ANALYSIS_COMPLETED, TaskStage.COMPLETED);
    }

    @Test
    void acceptsRecoveryAndManualReplay() {
        assertAllowed(TaskStage.FAILED, TaskStage.RETRYING);
        assertAllowed(TaskStage.RETRYING, TaskStage.CONSUMING);
        assertAllowed(TaskStage.CONSUMING, TaskStage.COMPLETED);
        assertAllowed(TaskStage.DEAD_LETTERED, TaskStage.MANUAL_REPLAY);
        assertAllowed(TaskStage.MANUAL_REPLAY, TaskStage.CONSUMING);
    }

    @Test
    void acceptsRevisionReuseAndFinalWarningPaths() {
        assertAllowed(TaskStage.PLAN_COMPLETED, TaskStage.CONSUMING);
        assertAllowed(TaskStage.ANALYSIS_COMPLETED, TaskStage.COMPLETED_REUSED);
        assertAllowed(TaskStage.CRITIC_RETRY_REQUIRED, TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS);
        assertAllowed(null, TaskStage.DEAD_LETTERED);
    }

    @Test
    void rejectsCompletedTaskRegressionAndTranscriptionStages() {
        assertThrows(IllegalStateException.class,
                () -> policy.requireAllowed(TaskStage.COMPLETED, TaskStage.EXECUTOR_STARTED));
        assertThrows(IllegalStateException.class,
                () -> policy.requireAllowed(TaskStage.CONSUMING, TaskStage.ASR));
        assertThrows(IllegalStateException.class,
                () -> policy.requireAllowed(TaskStage.CONSUMING, TaskStage.RETRIEVAL));
    }

    private void assertAllowed(TaskStage current, TaskStage next) {
        assertDoesNotThrow(() -> policy.requireAllowed(current, next));
    }
}
