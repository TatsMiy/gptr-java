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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 弹性验收（二）：LLM 配额耗尽。
 *
 * <p>配置 {@code gptr.clients.llm-mode=quota}——LLM 持续返回 429（QuotaApiException），
 * 重试 3 次耗尽后任务必须 FAILED，且错误码为 QUOTA_EXHAUSTED（而非笼统的
 * WORKER_ERROR）。
 */
@SpringBootTest(properties = {
        "gptr.clients.llm-mode=quota",
        "worker.poll-interval-ms=300",
        "worker.reap-interval-ms=300",
        "worker.lease-seconds=5",
        "gptr.engine.allow-mock-config=true"
})
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LlmQuotaIntegrationTest {

    @Autowired TaskService taskService;
    @Autowired TaskRepository taskRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        IntegrationDbGuard.truncateTasks(jdbcTemplate);
    }

    @Test
    void llmQuotaExhaustedFailsTaskWithErrorCode() {
        ResearchTask task = taskService.create(new com.gptr.common.service.CreateTaskCommand(
                "quota topic", "{\"mock\":{\"stageDelayMs\":10}}", null));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertEquals(TaskStatus.FAILED,
                        taskRepository.findById(task.getId()).orElseThrow().getStatus()));

        ResearchTask failed = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals("QUOTA_EXHAUSTED", failed.getErrorCode(),
                "LLM quota exhaustion must map to QUOTA_EXHAUSTED");
        assertNotNull(failed.getErrorDetail());
    }
}
