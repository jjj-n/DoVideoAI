package com.example.server.integration;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.example.server.entity.FailedAnalysisTask;
import com.example.server.mapper.AgentCheckpointMapper;
import com.example.server.repository.AgentCheckpointRepository;
import com.example.server.repository.AnalysisTaskEventOutboxRepository;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AnalysisStageService;
import com.example.server.service.AnalysisStagePolicy;
import com.example.server.service.AnalysisStageTransaction;
import com.example.server.service.AnalysisTaskEventOutboxRelay;
import com.example.server.service.FailedAnalysisTaskService;
import com.example.server.service.TaskEventService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(
        classes = AnalysisPersistenceIT.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.flyway.baseline-on-migrate=false")
class AnalysisPersistenceIT {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.44")
            .withDatabaseName("dovideoai");

    @Autowired
    private Flyway flyway;

    @Autowired
    private AnalysisStageTransaction stageTransaction;

    @Autowired
    private AgentCheckpointService checkpointService;

    @Autowired
    private AnalysisTaskEventOutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FailedAnalysisTaskService failedTaskService;

    @Autowired
    private AnalysisStageService analysisStageService;

    @Test
    void appliesEveryDatabaseMigration() {
        assertEquals("4", flyway.info().current().getVersion().getVersion());
    }

    @Test
    void commitsStageAndObservableEventTogether() {
        TaskStatus status = TaskStatus.of(TaskStatus.State.QUEUED, "queued");

        long outboxId = stageTransaction.advanceAndEnqueue(
                101L, "integration-goal", AnalysisMode.GENERAL, status, TaskStage.QUEUED);

        assertEquals(TaskStage.QUEUED,
                checkpointService.loadStage(101L, "integration-goal", AnalysisMode.GENERAL));
        TaskEvent storedEvent = outboxRepository.claim(outboxId, Duration.ofSeconds(30))
                .orElseThrow()
                .event();
        assertEquals(TaskEvent.of(status, TaskStage.QUEUED), storedEvent);
    }

    @Test
    void rollsBackStageWhenItsEventCannotBeStored() {
        jdbcTemplate.execute("RENAME TABLE analysis_task_event_outbox TO analysis_task_event_outbox_unavailable");
        try {
            TaskStatus status = TaskStatus.of(TaskStatus.State.QUEUED, "queued");

            assertThrows(RuntimeException.class, () -> stageTransaction.advanceAndEnqueue(
                    102L, "rollback-goal", AnalysisMode.GENERAL, status, TaskStage.QUEUED));

            assertNull(checkpointService.loadStage(
                    102L, "rollback-goal", AnalysisMode.GENERAL));
        } finally {
            jdbcTemplate.execute("RENAME TABLE analysis_task_event_outbox_unavailable TO analysis_task_event_outbox");
        }
    }

    @Test
    void letsOnlyOneWorkerClaimAnOutboxEvent() throws Exception {
        TaskEvent event = TaskEvent.of(
                TaskStatus.of(TaskStatus.State.QUEUED, "queued"), TaskStage.QUEUED);
        long outboxId = outboxRepository.enqueue(103L, "analysis:103:integration", event);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Optional<AnalysisTaskEventOutboxRepository.ClaimedEvent>> claim = () -> {
            workersReady.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return outboxRepository.claim(outboxId, Duration.ofSeconds(30));
        };

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Optional<AnalysisTaskEventOutboxRepository.ClaimedEvent>>> results = List.of(
                    workers.submit(claim), workers.submit(claim));
            assertTrue(workersReady.await(5, TimeUnit.SECONDS));
            start.countDown();

            long winners = 0;
            for (Future<Optional<AnalysisTaskEventOutboxRepository.ClaimedEvent>> result : results) {
                if (result.get(5, TimeUnit.SECONDS).isPresent()) winners++;
            }
            assertEquals(1, winners);
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void manualReplayStartsWithoutOldGoalCheckpoints() {
        Long mediaId = 104L;
        String goal = "replay-goal";
        AgentState previousResult = new AgentState(
                goal,
                new AgentState.AgentPlan(goal, List.of("old task")),
                new AnalysisResult(
                        "old result",
                        List.of("old conclusion"),
                        List.of(new AnalysisResult.Evidence(
                                1_000, "ASR", "old evidence", "old claim")),
                        List.of(),
                        List.of()),
                null,
                1);
        AgentState previousCriticState = new AgentState(
                goal,
                previousResult.plan(),
                previousResult.result(),
                new AgentState.CriticResult(
                        false,
                        List.of("old feedback"),
                        List.of(),
                        List.of(),
                        List.of()),
                1);
        checkpointService.savePlan(
                mediaId, goal, AnalysisMode.GENERAL, previousResult.plan());
        checkpointService.saveCriticState(
                mediaId, previousCriticState, AnalysisMode.GENERAL);
        checkpointService.saveResult(mediaId, previousResult, AnalysisMode.GENERAL);
        analysisStageService.transition(
                mediaId,
                goal,
                AnalysisMode.GENERAL,
                TaskStatus.of(TaskStatus.State.FAILED, "dead lettered"),
                TaskStage.DEAD_LETTERED);
        failedTaskService.record(
                new AnalysisTaskMsg(
                        mediaId,
                        AnalysisTaskMsg.START_ANALYSIS,
                        "media-" + mediaId,
                        goal,
                        AnalysisMode.GENERAL.name()),
                3,
                new IllegalStateException("analysis failed"));
        FailedAnalysisTask failedTask = failedTaskService.latest().getFirst();
        assertNotNull(checkpointService.loadPlan(mediaId, goal, AnalysisMode.GENERAL));
        assertNotNull(checkpointService.loadCriticState(mediaId, goal, AnalysisMode.GENERAL));
        assertNotNull(checkpointService.loadResult(mediaId, goal, AnalysisMode.GENERAL));

        failedTaskService.replay(failedTask.getId());

        assertNull(checkpointService.loadPlan(mediaId, goal, AnalysisMode.GENERAL));
        assertNull(checkpointService.loadCriticState(mediaId, goal, AnalysisMode.GENERAL));
        assertNull(checkpointService.loadResult(mediaId, goal, AnalysisMode.GENERAL));
        assertEquals(TaskStage.MANUAL_REPLAY,
                checkpointService.loadStage(mediaId, goal, AnalysisMode.GENERAL));
        assertEquals("REQUEUED", failedTaskService.latest().getFirst().getStatus());
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            MybatisPlusAutoConfiguration.class
    })
    @MapperScan(basePackageClasses = AgentCheckpointMapper.class)
    @Import({
            AgentCheckpointRepository.class,
            AnalysisTaskEventOutboxRepository.class,
            AgentCheckpointService.class,
            AnalysisStagePolicy.class,
            AnalysisStageTransaction.class,
            AnalysisTaskEventOutboxRelay.class,
            TaskEventService.class,
            AnalysisStageService.class,
            FailedAnalysisTaskService.class,
            ExternalBoundaryConfiguration.class
    })
    static class TestApplication {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExternalBoundaryConfiguration {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
            @SuppressWarnings("unchecked")
            SetOperations<String, String> sets = mock(SetOperations.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForHash()).thenReturn(hashes);
            when(redis.opsForSet()).thenReturn(sets);
            when(redis.opsForValue()).thenReturn(values);
            when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
            return redis;
        }

        @Bean
        RocketMQTemplate rocketMQTemplate() {
            return mock(RocketMQTemplate.class);
        }
    }
}
