package com.gptr.worker;

import com.gptr.common.repository.TaskCheckpointRepository;
import com.gptr.common.repository.TaskEventRepository;
import com.gptr.common.repository.TaskRepository;
import com.gptr.common.service.CreateTaskCommand;
import com.gptr.common.service.EventLogWriter;
import com.gptr.common.service.TaskService;
import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskEvent;
import com.gptr.common.task.TaskEventType;
import com.gptr.common.task.TaskStage;
import com.gptr.common.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.gptr.engine.testsupport.IntegrationDbGuard;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：Flyway 迁移、实体读写、队列全生命周期（提交→出队→五阶段→成功）、
 * 租约回收、取消、失败路径。
 *
 * <p>连接 docker compose 启动的本地 Postgres（application.yml 默认配置：
 * {@code jdbc:postgresql://localhost:5432/gptr}），先执行 {@code docker compose up -d}。
 * 注意：每个测试前会清空 tasks 相关表（开发库）。
 *
 * <p>默认被 Maven 排除（{@code @Tag("integration")}），显式运行：
 * {@code mvn -pl gptr-worker test -Pintegration}
 */
@SpringBootTest(properties = {
        "gptr.clients.search-chain=tavily:ok",
        "gptr.engine.allow-mock-config=true"
})
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerLifecycleIntegrationTest {

    @Autowired TaskService taskService;
    @Autowired TaskRepository taskRepository;
    @Autowired TaskEventRepository eventRepository;
    @Autowired TaskCheckpointRepository checkpointRepository;
    @Autowired LeaseReaper leaseReaper;
    @Autowired EventLogWriter eventLog;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        IntegrationDbGuard.truncateTasks(jdbcTemplate);
    }

    @Test
    void flywayMigratesAndTaskPersists() {
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public'", Integer.class);
        // 7 = 6 张业务表（tasks/task_events/checkpoints/webhook_deliveries/graph_checkpoints/app_config）
        // + flyway_schema_history（V9 新增 app_config）
        assertEquals(7, tables, "expect 6 business tables + flyway_schema_history");

        ResearchTask task = taskService.create(new CreateTaskCommand("what is the weather?", "{}", null));
        ResearchTask loaded = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.PENDING, loaded.getStatus());
        assertNotNull(loaded.getDeadlineAt(), "deadlineAt must be set by @PrePersist");
        assertEquals("{}", loaded.getConfig());

        List<TaskEvent> events = eventRepository.findByTaskIdOrderBySeqAsc(task.getId());
        assertEquals(1, events.size());
        assertEquals(TaskEventType.CREATED, events.get(0).getType());
    }

    @Test
    void taskLifecycleRunsToSuccess() {
        ResearchTask task = taskService.create(new CreateTaskCommand(
                "deep research topic", "{\"mock\":{\"stageDelayMs\":50}}", null));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertEquals(TaskStatus.SUCCEEDED,
                        taskRepository.findById(task.getId()).orElseThrow().getStatus()));

        ResearchTask done = taskRepository.findById(task.getId()).orElseThrow();
        assertNotNull(done.getResultRef(), "resultRef must be set");
        assertTrue(done.getResultRef().startsWith("local://"), "expected local:// ref but got " + done.getResultRef());
        assertNotNull(done.getFinishedAt());

        // 事件链：CREATED, DISPATCHED, (STAGE_STARTED+STAGE_COMPLETED)×5, SUCCEEDED
        List<TaskEvent> events = eventRepository.findByTaskIdOrderBySeqAsc(task.getId());
        assertEquals(TaskEventType.CREATED, events.get(0).getType());
        assertEquals(TaskEventType.DISPATCHED, events.get(1).getType());
        assertEquals(TaskEventType.SUCCEEDED, events.get(events.size() - 1).getType());
        long stageStarted = events.stream().filter(e -> e.getType() == TaskEventType.STAGE_STARTED).count();
        long stageCompleted = events.stream().filter(e -> e.getType() == TaskEventType.STAGE_COMPLETED).count();
        assertEquals(5, stageStarted);
        assertEquals(5, stageCompleted);
        // 五个阶段事件覆盖五阶段枚举（过滤 stage 为 null 的全局事件）
        List<TaskStage> coveredStages = events.stream()
                .map(TaskEvent::getStage)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        assertEquals(5, coveredStages.size());

        // checkpoint 数量 = 5（每阶段一条，复合主键 (task_id, stage)）
        assertEquals(5, checkpointRepository.findByTaskIdOrderByStageAsc(task.getId()).size());
    }

    @Test
    void expiredLeaseReturnsToPending() {
        // 直接造一个 RUNNING 且租约过期的任务（绕过 worker）
        ResearchTask task = taskService.create(new CreateTaskCommand(
                "lease test", "{}", "lease-key-" + UUID.randomUUID()));
        task.setStatus(TaskStatus.RUNNING);
        task.setOwnerToken(UUID.randomUUID());
        task.setLeaseUntil(OffsetDateTime.now().minusMinutes(10));
        taskRepository.save(task);

        leaseReaper.reap();

        ResearchTask after = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.PENDING, after.getStatus(), "expired lease must return to PENDING");
        List<TaskEvent> events = eventRepository.findByTaskIdOrderBySeqAsc(task.getId());
        assertTrue(events.stream().anyMatch(e -> e.getType() == TaskEventType.LEASE_EXPIRED),
                "LEASE_EXPIRED event expected");
    }

    @Test
    void cancelPendingTask() {
        ResearchTask task = taskService.create(new CreateTaskCommand("cancel me", "{}", null));
        taskService.cancel(task.getId());
        assertEquals(TaskStatus.CANCELLED,
                taskRepository.findById(task.getId()).orElseThrow().getStatus());
    }

    @Test
    void mockStageFailureMarksFailed() {
        ResearchTask task = taskService.create(new CreateTaskCommand(
                "failing topic", "{\"mock\":{\"failAtStage\":\"SEARCHING\",\"stageDelayMs\":20}}", null));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertEquals(TaskStatus.FAILED,
                        taskRepository.findById(task.getId()).orElseThrow().getStatus()));

        ResearchTask failed = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals("WORKER_ERROR", failed.getErrorCode());
        assertNotNull(failed.getErrorDetail());
    }
}
