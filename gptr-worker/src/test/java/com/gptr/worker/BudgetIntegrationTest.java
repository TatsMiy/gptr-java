package com.gptr.worker;

import com.gptr.common.repository.TaskRepository;
import com.gptr.common.service.TaskService;
import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import com.gptr.engine.testsupport.IntegrationDbGuard;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算控制验收：步骤/成本任一超限 → 优雅终止
 * （FAILED + {@code BUDGET_<KIND>} + partial_result=true，保留中间结果）。
 */
@SpringBootTest(properties = {
        "gptr.clients.search-chain=tavily:ok",
        "worker.poll-interval-ms=300",
        "worker.reap-interval-ms=300",
        "worker.lease-seconds=5",
        "gptr.engine.allow-mock-config=true"
})
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BudgetIntegrationTest {

    @Autowired TaskService taskService;
    @Autowired TaskRepository taskRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        // worker 异步收尾可能与 TRUNCATE（表级锁）死锁；用 DELETE（行锁）+ 重试
        for (int i = 0; i < 3; i++) {
            try {
                IntegrationDbGuard.deleteTasks(jdbcTemplate);
                return;
            } catch (Exception e) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Test
    void costBudgetExceededFailsGracefully() {
        ResearchTask task = taskService.create(new com.gptr.common.service.CreateTaskCommand(
                "cost budget topic",
                "{\"budgets\":{\"costUsd\":0.00005},\"mock\":{\"costPerStage\":0.001,\"stageDelayMs\":10}}",
                null));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertEquals(TaskStatus.FAILED,
                        taskRepository.findById(task.getId()).orElseThrow().getStatus()));

        ResearchTask failed = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals("BUDGET_COST", failed.getErrorCode());
        assertTrue(failed.isPartialResult(), "graceful termination must keep partial results");
        assertTrue(failed.getCostSpentUsd().signum() > 0, "cost must have been recorded");
    }

    @Test
    void stepBudgetExceededFailsGracefully() {
        ResearchTask task = taskService.create(new com.gptr.common.service.CreateTaskCommand(
                "step budget topic",
                "{\"budgets\":{\"steps\":2},\"mock\":{\"stageDelayMs\":10}}",
                null));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertEquals(TaskStatus.FAILED,
                        taskRepository.findById(task.getId()).orElseThrow().getStatus()));

        ResearchTask failed = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals("BUDGET_STEP", failed.getErrorCode());
        assertTrue(failed.isPartialResult(), "graceful termination must keep partial results");
        // 第 3 阶段前检查触发超限；步骤计数事务回滚，保留已执行阶段数 2
        assertEquals(2, failed.getStepsUsed(), "exactly 2 stages executed before the step budget of 2 tripped");
    }
}
