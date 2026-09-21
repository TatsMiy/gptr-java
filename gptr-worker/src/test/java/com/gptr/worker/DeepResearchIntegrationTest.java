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
 * 端到端：deep research 模式任务（mock LLM JSON + mock 检索）经 worker 完整跑通。
 *
 * <p>验证：3 阶段 checkpoint（PLANNING/RESEARCH/WRITING）、RESEARCH 产出 learnings、
 * 图内成本计入任务 cost_spent_usd。
 */
@SpringBootTest(properties = {
        "gptr.clients.search-chain=tavily:ok",
        "gptr.clients.llm-mode=json",
        "worker.poll-interval-ms=300",
        "worker.reap-interval-ms=300",
        "worker.lease-seconds=5",
        "gptr.engine.allow-mock-config=true"
})
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeepResearchIntegrationTest {

    @Autowired TaskService taskService;
    @Autowired TaskRepository taskRepository;
    @Autowired TaskCheckpointRepository checkpointRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void cleanDatabase() {
        IntegrationDbGuard.deleteTasks(jdbcTemplate);
        IntegrationDbGuard.truncateCheckpoints(jdbcTemplate);
    }

    @Test
    void deepResearchTaskRunsEndToEnd() throws Exception {
        ResearchTask task = taskService.create(new com.gptr.common.service.CreateTaskCommand(
                "deep research e2e",
                "{\"mode\":\"deep_research\",\"breadth\":2,\"depth\":2,\"mock\":{\"stageDelayMs\":10}}",
                null));

        await().atMost(Duration.ofSeconds(40))
                .untilAsserted(() -> assertEquals(TaskStatus.SUCCEEDED,
                        taskRepository.findById(task.getId()).orElseThrow().getStatus()));

        // 3 阶段 checkpoint（deep research 模式）
        List<TaskCheckpoint> checkpoints = checkpointRepository.findByTaskIdOrderByStageAsc(task.getId());
        assertEquals(3, checkpoints.size(), "PLANNING/RESEARCH/WRITING checkpoints expected");

        TaskCheckpoint research = checkpoints.stream()
                .filter(c -> c.getStage() == TaskStage.RESEARCH).findFirst().orElseThrow();
        JsonNode payload = mapper.readTree(research.getPayload());
        assertEquals(2, payload.get("depthReached").asInt(), "depth budget 2: " + research.getPayload());
        assertTrue(payload.get("learnings").asInt() >= 2,
                "learnings must be produced: " + research.getPayload());

        // 图内成本已计入任务（JSON 模式 LLM 每次 0.001，多次调用）
        ResearchTask done = taskRepository.findById(task.getId()).orElseThrow();
        assertTrue(done.getCostSpentUsd().signum() > 0,
                "graph LLM costs must be recorded into task cost_spent_usd");

        // 审计修正（原断言曾被 mock 单遍报告假绿）：WRITING 必须真实走【逐节写作】主链
        // （writingMode=section）——大纲/逐节任何一步静默降级单遍 → 此处立即红
        TaskCheckpoint writing = checkpoints.stream()
                .filter(c -> c.getStage() == TaskStage.WRITING).findFirst().orElseThrow();
        JsonNode writingPayload = mapper.readTree(writing.getPayload());
        assertEquals("section", writingPayload.path("writingMode").asText(),
                "WRITING 必须走逐节写作主链（P2-2），禁止静默降级单遍: " + writing.getPayload());
        assertTrue(writingPayload.path("sections").asInt() >= 2,
                "大纲应产出 ≥2 节: " + writing.getPayload());

        // 报告结构：Key Takeaways + 各节 + 机械 References（markdown 链接行，非裸 URL）
        String report = java.nio.file.Files.readString(
                java.nio.file.Path.of(done.getResultRef().substring("local://".length())));
        assertTrue(report.startsWith("#"), "report must start with a title");
        assertTrue(report.contains("## Key Takeaways"), "逐节报告应含 Key Takeaways: " + report);
        assertTrue(report.contains("## References"), "report must contain a References section");
        assertTrue(countOccurrences(report, "- [") >= 2,
                "机械 References 应含 ≥2 条 markdown 链接: " + report);
        assertTrue(!report.contains("Mock Research Report"),
                "不得出现单遍 writer 兜底产物（逐节 mock 已契约化）");
        assertEquals(0, countOccurrences(report, "- https://"),
                "References 必须是 markdown 链接而非裸 URL");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
