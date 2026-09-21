package com.gptr.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.common.repository.TaskCheckpointRepository;
import com.gptr.common.repository.TaskEventRepository;
import com.gptr.common.repository.TaskRepository;
import com.gptr.common.service.TaskService;
import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskCheckpoint;
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
import java.util.List;
import com.gptr.engine.testsupport.IntegrationDbGuard;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹性验收（一）：检索降级链。
 *
 * <p>配置检索链 {@code tavily:transient,serper:ok}——Tavily 持续瞬时失败，
 * 重试耗尽后自动切换到 Serper；任务仍应成功完成，且 SEARCHING 检查点
 * 记录实际使用的源为 serper（2 条结果）。
 */
@SpringBootTest(properties = {
        "gptr.clients.search-chain=tavily:transient,serper:ok",
        "gptr.clients.llm-mode=ok",
        "worker.poll-interval-ms=300",
        "worker.reap-interval-ms=300",
        "worker.lease-seconds=5",
        "gptr.engine.allow-mock-config=true"
})
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ElasticityIntegrationTest {

    @Autowired TaskService taskService;
    @Autowired TaskRepository taskRepository;
    @Autowired TaskCheckpointRepository checkpointRepository;
    @Autowired TaskEventRepository eventRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        IntegrationDbGuard.truncateTasks(jdbcTemplate);
    }

    @Test
    void searchFallbackKeepsTaskSuccessful() throws Exception {
        ResearchTask task = taskService.create(new com.gptr.common.service.CreateTaskCommand(
                "elasticity topic", "{\"mock\":{\"stageDelayMs\":20}}", null));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertEquals(TaskStatus.SUCCEEDED,
                        taskRepository.findById(task.getId()).orElseThrow().getStatus()));

        // SEARCHING 检查点：实际由 serper 提供结果（tavily 已被降级链跳过）
        List<TaskCheckpoint> checkpoints = checkpointRepository.findByTaskIdOrderByStageAsc(task.getId());
        TaskCheckpoint searching = checkpoints.stream()
                .filter(c -> c.getStage() == TaskStage.SEARCHING)
                .findFirst().orElseThrow();
        // payload 为 jsonb，读回时键序/空格被 Postgres 规范化，需解析 JSON 断言
        JsonNode node = new ObjectMapper().readTree(searching.getPayload());
        assertEquals(2, node.get("results").asInt(),
                "serper mock returns 2 results, got: " + searching.getPayload());
        assertTrue(node.get("sources").asText().contains("serper"),
                "SEARCHING payload must record serper as the serving source, got: " + searching.getPayload());
    }
}
