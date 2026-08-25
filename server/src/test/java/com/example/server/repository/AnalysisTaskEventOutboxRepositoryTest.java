package com.example.server.repository;

import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.example.server.entity.AnalysisTaskEventOutbox;
import com.example.server.mapper.AnalysisTaskEventOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisTaskEventOutboxRepositoryTest {

    private final AnalysisTaskEventOutboxMapper mapper = mock(AnalysisTaskEventOutboxMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnalysisTaskEventOutboxRepository repository =
            new AnalysisTaskEventOutboxRepository(mapper, objectMapper);

    @Test
    void serializesEventAndReturnsGeneratedOutboxId() throws Exception {
        TaskEvent event = TaskEvent.of(
                TaskStatus.of(TaskStatus.State.PROCESSING, "开始消费"), TaskStage.CONSUMING);
        ArgumentCaptor<AnalysisTaskEventOutbox> row =
                ArgumentCaptor.forClass(AnalysisTaskEventOutbox.class);
        when(mapper.insert(row.capture())).thenAnswer(invocation -> {
            row.getValue().setId(31L);
            return 1;
        });

        assertEquals(31L, repository.enqueue(7L, "analysis:7:digest", event));
        assertEquals(7L, row.getValue().getMediaId());
        assertEquals("analysis:7:digest", row.getValue().getEventKey());
        assertEquals(event, objectMapper.readValue(row.getValue().getEventPayload(), TaskEvent.class));
    }

    @Test
    void claimDeserializesTheStoredTaskEvent() {
        TaskEvent event = TaskEvent.of(
                TaskStatus.of(TaskStatus.State.PROCESSING, "开始消费"), TaskStage.CONSUMING);
        AnalysisTaskEventOutbox row = new AnalysisTaskEventOutbox();
        row.setId(31L);
        row.setEventKey("analysis:7:digest");
        row.setEventPayload("""
                {"state":"PROCESSING","result":null,"message":"开始消费","stage":"CONSUMING"}
                """);
        row.setAttemptCount(2);
        when(mapper.claim(eq(31L), anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        when(mapper.findClaimed(eq(31L), anyString())).thenReturn(row);

        var claimed = repository.claim(31L, Duration.ofSeconds(30));

        assertTrue(claimed.isPresent());
        assertEquals(event, claimed.orElseThrow().event());
        assertEquals(2, claimed.orElseThrow().attemptCount());
    }

    @Test
    void malformedStoredEventReleasesItsClaimForBackoff() {
        AnalysisTaskEventOutbox row = new AnalysisTaskEventOutbox();
        row.setId(31L);
        row.setEventKey("analysis:7:digest");
        row.setEventPayload("not-json");
        row.setAttemptCount(2);
        when(mapper.claim(eq(31L), anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        when(mapper.findClaimed(eq(31L), anyString())).thenReturn(row);
        when(mapper.markFailed(
                eq(31L), anyString(), any(LocalDateTime.class), anyString())).thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> repository.claim(31L, Duration.ofSeconds(30)));
    }
}
