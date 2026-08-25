package com.example.server.service;

import com.example.server.dto.TaskStage;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Defines the legal lifecycle transitions for an analysis task. */
@Component
public class AnalysisStagePolicy {

    private static final Set<TaskStage> ANALYSIS_STAGES = EnumSet.of(
            TaskStage.QUEUED,
            TaskStage.CONSUMING,
            TaskStage.VIDEO_CONTEXT,
            TaskStage.AGENT_LOOP,
            TaskStage.PLAN_COMPLETED,
            TaskStage.EXECUTOR_STARTED,
            TaskStage.EXECUTOR_COMPLETED,
            TaskStage.CRITIC_STARTED,
            TaskStage.CRITIC_PASSED,
            TaskStage.CRITIC_RETRY_REQUIRED,
            TaskStage.EVIDENCE_REFRESHED,
            TaskStage.ANALYSIS_COMPLETED,
            TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS,
            TaskStage.BUDGET_EXHAUSTED,
            TaskStage.RETRYING,
            TaskStage.COMPLETED,
            TaskStage.COMPLETED_REUSED,
            TaskStage.FAILED,
            TaskStage.DEAD_LETTERED,
            TaskStage.MANUAL_REPLAY,
            TaskStage.DISPATCH_FAILED);

    private static final Map<TaskStage, Set<TaskStage>> ALLOWED = allowedTransitions();

    public void requireAllowed(TaskStage current, TaskStage next) {
        if (next == null || !ANALYSIS_STAGES.contains(next)) {
            throw new InvalidTransitionException("分析任务不能进入非分析阶段: " + next);
        }
        if (current == next) return;
        if (current == null) {
            if (next == TaskStage.QUEUED
                    || next == TaskStage.CONSUMING
                    || next == TaskStage.DEAD_LETTERED) return;
            throw invalid(current, next);
        }
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(next)) {
            throw invalid(current, next);
        }
    }

    private InvalidTransitionException invalid(TaskStage current, TaskStage next) {
        return new InvalidTransitionException("非法分析任务阶段迁移: " + current + " -> " + next);
    }

    private static Map<TaskStage, Set<TaskStage>> allowedTransitions() {
        Map<TaskStage, Set<TaskStage>> transitions = new EnumMap<>(TaskStage.class);
        allow(transitions, TaskStage.QUEUED,
                TaskStage.CONSUMING, TaskStage.DISPATCH_FAILED, TaskStage.FAILED, TaskStage.DEAD_LETTERED);
        allow(transitions, TaskStage.CONSUMING,
                TaskStage.VIDEO_CONTEXT, TaskStage.AGENT_LOOP, TaskStage.PLAN_COMPLETED,
                TaskStage.COMPLETED, TaskStage.COMPLETED_REUSED, TaskStage.RETRYING,
                TaskStage.BUDGET_EXHAUSTED, TaskStage.FAILED, TaskStage.DEAD_LETTERED);
        allow(transitions, TaskStage.VIDEO_CONTEXT,
                TaskStage.AGENT_LOOP, TaskStage.PLAN_COMPLETED,
                TaskStage.RETRYING, TaskStage.FAILED, TaskStage.DEAD_LETTERED);
        allow(transitions, TaskStage.AGENT_LOOP,
                TaskStage.PLAN_COMPLETED, TaskStage.EXECUTOR_STARTED,
                TaskStage.RETRYING, TaskStage.FAILED, TaskStage.BUDGET_EXHAUSTED);
        allow(transitions, TaskStage.PLAN_COMPLETED,
                TaskStage.CONSUMING, TaskStage.EXECUTOR_STARTED, TaskStage.EXECUTOR_COMPLETED,
                TaskStage.RETRYING, TaskStage.FAILED, TaskStage.BUDGET_EXHAUSTED);
        allow(transitions, TaskStage.EXECUTOR_STARTED,
                TaskStage.EXECUTOR_COMPLETED, TaskStage.RETRYING,
                TaskStage.FAILED, TaskStage.BUDGET_EXHAUSTED);
        allow(transitions, TaskStage.EXECUTOR_COMPLETED,
                TaskStage.CRITIC_STARTED, TaskStage.CRITIC_PASSED,
                TaskStage.CRITIC_RETRY_REQUIRED, TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS,
                TaskStage.RETRYING, TaskStage.FAILED, TaskStage.BUDGET_EXHAUSTED);
        allow(transitions, TaskStage.CRITIC_STARTED,
                TaskStage.CRITIC_PASSED, TaskStage.CRITIC_RETRY_REQUIRED,
                TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS, TaskStage.RETRYING,
                TaskStage.FAILED, TaskStage.BUDGET_EXHAUSTED);
        allow(transitions, TaskStage.CRITIC_RETRY_REQUIRED,
                TaskStage.EVIDENCE_REFRESHED, TaskStage.PLAN_COMPLETED,
                TaskStage.EXECUTOR_STARTED, TaskStage.EXECUTOR_COMPLETED,
                TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS, TaskStage.RETRYING,
                TaskStage.FAILED, TaskStage.BUDGET_EXHAUSTED);
        allow(transitions, TaskStage.EVIDENCE_REFRESHED,
                TaskStage.PLAN_COMPLETED, TaskStage.EXECUTOR_STARTED,
                TaskStage.EXECUTOR_COMPLETED, TaskStage.RETRYING,
                TaskStage.FAILED, TaskStage.BUDGET_EXHAUSTED);
        allow(transitions, TaskStage.CRITIC_PASSED,
                TaskStage.ANALYSIS_COMPLETED, TaskStage.COMPLETED,
                TaskStage.RETRYING, TaskStage.FAILED);
        allow(transitions, TaskStage.ANALYSIS_COMPLETED,
                TaskStage.COMPLETED, TaskStage.COMPLETED_REUSED, TaskStage.QUEUED);
        allow(transitions, TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS,
                TaskStage.COMPLETED, TaskStage.COMPLETED_REUSED, TaskStage.QUEUED);
        allow(transitions, TaskStage.RETRYING,
                TaskStage.CONSUMING, TaskStage.FAILED, TaskStage.DEAD_LETTERED,
                TaskStage.BUDGET_EXHAUSTED);
        allow(transitions, TaskStage.FAILED,
                TaskStage.RETRYING, TaskStage.BUDGET_EXHAUSTED, TaskStage.DEAD_LETTERED,
                TaskStage.MANUAL_REPLAY, TaskStage.QUEUED, TaskStage.CONSUMING);
        allow(transitions, TaskStage.BUDGET_EXHAUSTED,
                TaskStage.MANUAL_REPLAY, TaskStage.QUEUED, TaskStage.CONSUMING);
        allow(transitions, TaskStage.DEAD_LETTERED,
                TaskStage.MANUAL_REPLAY, TaskStage.QUEUED, TaskStage.CONSUMING);
        allow(transitions, TaskStage.MANUAL_REPLAY,
                TaskStage.CONSUMING, TaskStage.DISPATCH_FAILED, TaskStage.DEAD_LETTERED);
        allow(transitions, TaskStage.DISPATCH_FAILED,
                TaskStage.QUEUED, TaskStage.CONSUMING, TaskStage.DEAD_LETTERED);
        allow(transitions, TaskStage.COMPLETED,
                TaskStage.QUEUED);
        allow(transitions, TaskStage.COMPLETED_REUSED,
                TaskStage.QUEUED);
        return Map.copyOf(transitions);
    }

    private static void allow(Map<TaskStage, Set<TaskStage>> transitions,
                              TaskStage current,
                              TaskStage... next) {
        transitions.put(current, EnumSet.copyOf(java.util.List.of(next)));
    }

    public static class InvalidTransitionException extends IllegalStateException {
        public InvalidTransitionException(String message) {
            super(message);
        }
    }
}
