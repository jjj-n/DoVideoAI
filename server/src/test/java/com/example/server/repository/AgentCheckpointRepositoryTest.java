package com.example.server.repository;

import com.example.server.dto.TaskStage;
import com.example.server.mapper.AgentCheckpointMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCheckpointRepositoryTest {

    @Test
    void writesGoalPayloadWithoutAdvancingCanonicalStage() {
        Fixture fixture = fixture();

        fixture.repository.writePayload(
                7L, "goal:plan", "goal:key", "plan", TaskStage.PLAN_COMPLETED, "draft");

        verify(fixture.mapper).upsert(7L, "goal:plan", "PLAN_COMPLETED", "\"draft\"");
        verify(fixture.hashes).put("goal:key", "plan", "\"draft\"");
        verify(fixture.hashes, never()).put("goal:key", "stage", "PLAN_COMPLETED");
    }

    @Test
    void readsGoalPayloadWithoutReplacingCachedCanonicalStage() {
        Fixture fixture = fixture();
        when(fixture.mapper.findPayload(7L, "goal:plan")).thenReturn("\"draft\"");

        assertEquals("draft", fixture.repository.readPayload(
                7L, "goal:plan", "goal:key", "plan", String.class));

        verify(fixture.mapper, never()).findStage(7L, "goal:plan");
        verify(fixture.hashes).put("goal:key", "plan", "\"draft\"");
        verify(fixture.hashes, never()).put("goal:key", "stage", "PLAN_COMPLETED");
    }

    @Test
    void insertsInitialStageWhenThePersistedStageIsAbsent() {
        Fixture fixture = fixture();
        when(fixture.mapper.insertStageIfAbsent(7L, "goal:stage", "QUEUED"))
                .thenReturn(1);

        assertTrue(fixture.repository.compareAndSetStage(
                7L, "goal:stage", "goal:key", null, TaskStage.QUEUED));

        verify(fixture.mapper).insertStageIfAbsent(7L, "goal:stage", "QUEUED");
        verify(fixture.mapper, never()).compareAndSetStage(
                7L, "goal:stage", null, "QUEUED");
        verify(fixture.hashes).put("goal:key", "stage", "QUEUED");
    }

    @Test
    void updatesStageOnlyWhenThePersistedStageStillMatches() {
        Fixture fixture = fixture();
        when(fixture.mapper.compareAndSetStage(
                7L, "goal:stage", "QUEUED", "CONSUMING"))
                .thenReturn(1);

        assertTrue(fixture.repository.compareAndSetStage(
                7L, "goal:stage", "goal:key", TaskStage.QUEUED, TaskStage.CONSUMING));

        verify(fixture.mapper).compareAndSetStage(
                7L, "goal:stage", "QUEUED", "CONSUMING");
        verify(fixture.hashes).put("goal:key", "stage", "CONSUMING");
    }

    @Test
    void evictsStaleCachedStageWhenTheCompareAndSetLosesARace() {
        Fixture fixture = fixture();
        when(fixture.mapper.compareAndSetStage(
                7L, "goal:stage", "QUEUED", "CONSUMING"))
                .thenReturn(0);

        assertFalse(fixture.repository.compareAndSetStage(
                7L, "goal:stage", "goal:key", TaskStage.QUEUED, TaskStage.CONSUMING));

        verify(fixture.hashes).delete("goal:key", "stage");
        verify(fixture.hashes, never()).put("goal:key", "stage", "CONSUMING");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Fixture fixture() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        when(redis.<Object, Object>opsForHash()).thenReturn(hashes);
        return new Fixture(
                new AgentCheckpointRepository(redis, new ObjectMapper(), mapper), mapper, hashes);
    }

    private record Fixture(AgentCheckpointRepository repository,
                           AgentCheckpointMapper mapper,
                           HashOperations<String, Object, Object> hashes) {
    }
}
