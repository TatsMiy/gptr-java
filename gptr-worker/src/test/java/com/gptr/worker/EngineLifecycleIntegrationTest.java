package com.gptr.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.common.repository.TaskCheckpointRepository;
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
 * 端到端：ResearchEngineImpl 替换 Mock 引擎后，任务走真实五阶段流水线
 * （mock LLM + mock 检索），验证 PLANNING 产出子查询、SEARCHING 产出结果、
 * 5 个 checkpoint 与事件链。
 */
@SpringBootTest(properties = {
        "gptr.clients.search-chain=tavily:ok",
        "gptr.clients.llm-mode=ok",
        "worker.poll-interval-ms=300",
        "worker.reap-interval-ms=300",
        "worker.lease-seconds=5",
        "gptr.engine.allow-mock-config=true"
})
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EngineLifecycleIntegrationTest {

    @Autowired TaskService taskService;
    @Autowired TaskRepository taskRepository;
    @Autowired TaskCheckpointRepository checkpointRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void cleanDatabase() {
        IntegrationDbGuard.deleteTasks(jdbcTemplate);
    }

    @Test
    void fiveStagePipelineRunsToSuccess() throws Exception {
        ResearchTask task = taskService.create(new com.gptr.common.service.CreateTaskCommand(
                "E1 pipeline topic", "{\"maxSubQueries\":3,\"mock\":{\"stageDelayMs\":10}}", null));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertEquals(TaskStatus.SUCCEEDED,
                        taskRepository.findById(task.getId()).orElseThrow().getStatus()));

        List<TaskCheckpoint> checkpoints = checkpointRepository.findByTaskIdOrderByStageAsc(task.getId());
        assertEquals(5, checkpoints.size(), "five stages must each leave a checkpoint");

        // PLANNING：产出子查询（mock LLM 非 JSON → 容错退化为单查询，仍须有 queries 字段）
        TaskCheckpoint planning = checkpoints.stream()
                .filter(c -> c.getStage() == TaskStage.PLANNING).findFirst().orElseThrow();
        JsonNode planPayload = mapper.readTree(planning.getPayload());
        assertTrue(planPayload.has("queries"), "PLANNING checkpoint must carry sub-queries");

        // SEARCHING：mock 检索返回 2 条结果
        TaskCheckpoint searching = checkpoints.stream()
                .filter(c -> c.getStage() == TaskStage.SEARCHING).findFirst().orElseThrow();
        JsonNode searchPayload = mapper.readTree(searching.getPayload());
        assertEquals(2, searchPayload.get("results").asInt(),
                "mock search returns 2 results: " + searching.getPayload());

        // WRITING 占位报告已生成
        ResearchTask done = taskRepository.findById(task.getId()).orElseThrow();
        assertTrue(done.getResultRef().startsWith("local://"), "report must be stored");
    }
}
