package com.example.server.integration;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.example.server.mapper.AgentCheckpointMapper;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.example.server.repository.AgentCheckpointRepository;
import com.example.server.repository.AnalysisTaskEventOutboxRepository;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AnalysisStagePolicy;
import com.example.server.service.AnalysisStageTransaction;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
            RedisBoundaryConfiguration.class
    })
    static class TestApplication {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisBoundaryConfiguration {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
            @SuppressWarnings("unchecked")
            SetOperations<String, String> sets = mock(SetOperations.class);
            when(redis.opsForHash()).thenReturn(hashes);
            when(redis.opsForSet()).thenReturn(sets);
            return redis;
        }
    }
}
