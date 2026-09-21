package com.gptr.worker;

import com.gptr.common.repository.TaskCheckpointRepository;
import com.gptr.common.repository.TaskRepository;
import com.gptr.common.service.TaskService;
import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskCheckpoint;
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
import com.gptr.engine.testsupport.IntegrationDbGuard;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * （实验组）阶段内续租守卫开启时，超租约的长阶段（mock stageDelay >
 * lease）不被 LeaseReaper 回收——任务单次完成（attempt=1），无重跑。
 *
 * <p>时序设计：lease-seconds=2s（守卫间隔 max(2s/3,1s)=1s < 租约），阶段延迟 2.5s
 * > 租约 2s——无守卫必然被 reap（对照组见 Disabled 测试）。
 */
@SpringBootTest(properties = {
        "gptr.clients.search-chain=tavily:ok",
        "gptr.clients.llm-mode=json",
        "worker.poll-interval-ms=300",
        "worker.reap-interval-ms=300",
        "worker.lease-seconds=2",
        "worker.lease-guard-enabled=true",
        "gptr.engine.allow-mock-config=true"
})
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerLeaseGuardIntegrationTest {

    @Autowired TaskService taskService;
    @Autowired TaskRepository taskRepository;
    @Autowired TaskCheckpointRepository checkpointRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        IntegrationDbGuard.deleteTasks(jdbcTemplate);
        IntegrationDbGuard.truncateCheckpoints(jdbcTemplate);
    }

    @Test
    void longStageSurvivesWithLeaseGuard() throws Exception {
        ResearchTask task = taskService.create(new com.gptr.common.service.CreateTaskCommand(
                "lease guard e2e",
                "{\"mode\":\"deep_research\",\"breadth\":2,\"depth\":2,"
                        + "\"mock\":{\"stageDelayMs\":2500}}",
                null));

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertEquals(TaskStatus.SUCCEEDED,
                        taskRepository.findById(task.getId()).orElseThrow().getStatus()));

        ResearchTask done = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(1, done.getAttempt(), "守卫续租：任务单次完成，不得被 reaper 重跑");
        // 3 阶段 checkpoint 全落（旧顺序中"被回收 worker 不写产物"不影响本组）
        assertEquals(3, checkpointRepository.findByTaskIdOrderByStageAsc(task.getId()).size());
        // RESEARCH 阶段完成事件恰一条（无重复完成）
        long researchCompleted = taskService.listEvents(task.getId()).stream()
                .filter(e -> e.getType() == TaskEventType.STAGE_COMPLETED
                        && e.getStage() == TaskStage.RESEARCH)
                .count();
        assertEquals(1, researchCompleted, "RESEARCH STAGE_COMPLETED 必须唯一");
        assertTrue(done.getResultRef() != null, "报告已存储");
    }
}
