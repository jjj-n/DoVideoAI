package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStage;
import com.example.server.repository.AgentCheckpointRepository;
import com.example.server.repository.AnalysisTaskEventOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCheckpointServiceTest {

    @Test
    void persistsRevisionBeforeReplacingGoalCheckpoints() {
        StringRedisTemplate redis = redis();
        AgentCheckpointRepository repository = mock(AgentCheckpointRepository.class);
        AnalysisTaskEventOutboxRepository outbox = mock(AnalysisTaskEventOutboxRepository.class);
        AgentCheckpointService service =
                new AgentCheckpointService(redis, new ObjectMapper(), repository, outbox);
        AgentState.AgentPlan plan = new AgentState.AgentPlan("重新审查", List.of("核验第一条结论"));

        service.stageRevision(7L, "审查视频", AnalysisMode.REVIEW, plan);

        ArgumentCaptor<Object> revision = ArgumentCaptor.forClass(Object.class);
        verify(repository).writeStandalone(
                eq(7L), anyString(), anyString(), eq("revision"),
                eq(TaskStage.REVISION_PENDING), revision.capture());
        when(repository.read(
                anyLong(), anyString(), anyString(), eq("revision"), any(Class.class)))
                .thenReturn(revision.getValue());

        assertTrue(service.beginStagedRevision(7L, "审查视频", AnalysisMode.REVIEW));
        verify(repository).deleteByPrefix(eq(7L), anyString());
        verify(repository).writePayload(
                eq(7L), anyString(), anyString(), eq("plan"),
                eq(TaskStage.PLAN_COMPLETED), eq(plan));
        verify(repository).writeStandalone(
                eq(7L), anyString(), anyString(), eq("revision"),
                eq(TaskStage.REVISION_APPLIED), any());
    }

    @Test
    void goalPayloadUsesPayloadOnlyRepositoryPath() {
        StringRedisTemplate redis = redis();
        AgentCheckpointRepository repository = mock(AgentCheckpointRepository.class);
        AnalysisTaskEventOutboxRepository outbox = mock(AnalysisTaskEventOutboxRepository.class);
        AgentCheckpointService service =
                new AgentCheckpointService(redis, new ObjectMapper(), repository, outbox);
        AgentState.AgentPlan plan = new AgentState.AgentPlan("总结", List.of("提取结论"));

        service.savePlan(7L, "总结视频", AnalysisMode.GENERAL, plan);

        verify(repository).writePayload(
                eq(7L), anyString(), anyString(), eq("plan"),
                eq(TaskStage.PLAN_COMPLETED), eq(plan));
    }

    @Test
    void deletingMediaAlsoDeletesItsStoredTaskEvents() {
        StringRedisTemplate redis = redis();
        AgentCheckpointRepository repository = mock(AgentCheckpointRepository.class);
        AnalysisTaskEventOutboxRepository outbox = mock(AnalysisTaskEventOutboxRepository.class);
        AgentCheckpointService service =
                new AgentCheckpointService(redis, new ObjectMapper(), repository, outbox);

        service.deleteMedia(7L);

        verify(repository).deleteByMediaId(7L);
        verify(outbox).deleteByMediaId(7L);
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate redis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        return redis;
    }
}
