package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskEventServiceTest {

    @Test
    void durablePublishReportsRedisFailureSoTheOutboxCanRetry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        TaskEventService service = new TaskEventService(redis, new ObjectMapper());
        TaskEvent event = TaskEvent.of(
                TaskStatus.of(TaskStatus.State.PROCESSING, "开始消费"), TaskStage.CONSUMING);
        when(redis.convertAndSend(anyString(), anyString()))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThrows(IllegalStateException.class,
                () -> service.publishStored("analysis:7:digest", event));
        assertDoesNotThrow(() -> service.publishAnalysis(
                7L, "总结课程", AnalysisMode.GENERAL,
                TaskStatus.of(TaskStatus.State.PROCESSING, "开始消费"), TaskStage.CONSUMING));
    }
}
