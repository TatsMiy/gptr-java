package com.gptr.worker;

import com.gptr.common.repository.TaskRepository;
import com.gptr.common.service.TaskService;
import com.gptr.common.task.ResearchTask;
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
import java.util.List;
import com.gptr.engine.testsupport.IntegrationDbGuard;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * （对照组）守卫关闭 + 每阶段延迟 2.5s > 租约 2s → LeaseReaper
 * 确定性回收、每轮从零重跑、attempt 三轮达上限 → FAILED（LEASE_EXPIRED_EXHAUSTED）。
 *
 * <p>顺序修复断言：每轮被回收的旧 worker 在阶段完成时前置续租失败 → 不再写
 * checkpoint/STAGE_COMPLETED/成本——事件表中 <b>零条</b> STAGE_COMPLETED、
 * 无 RESEARCH checkpoint、成本零双计（被回收轮次未记账）。这证明"脑裂双写"路径
 * 被收口（旧实现会在 renewLease 之前写阶段产物，此处将产生重复事件）。
 */
@SpringBootTest(properties = {
        "gptr.clients.search-chain=tavily:ok",
        "gptr.clients.llm-mode=json",
        "worker.poll-interval-ms=300",
        "worker.reap-interval-ms=300",
        "worker.lease-seconds=2",
        "worker.lease-guard-enabled=false",
        "gptr.engine.allow-mock-config=true"
})
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerLeaseGuardDisabledIntegrationTest {

    @Autowired TaskService taskService;
    @Autowired TaskRepository taskRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        IntegrationDbGuard.deleteTasks(jdbcTemplate);
        IntegrationDbGuard.truncateCheckpoints(jdbcTemplate);
    }

    @Test
    void reapedRoundsLeaveNoStageArtifactsAndConverge() throws Exception {
        ResearchTask task = taskService.create(new com.gptr.common.service.CreateTaskCommand(
                "lease guard off e2e",
                "{\"mode\":\"deep_research\",\"breadth\":2,\"depth\":2,"
                        + "\"mock\":{\"stageDelayMs\":2500}}",
                null));

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> {
                    ResearchTask t = taskRepository.findById(task.getId()).orElseThrow();
                    assertTrue(t.getStatus() == TaskStatus.FAILED
                                    || t.getStatus() == TaskStatus.SUCCEEDED,
                            "终态收敛，status=" + t.getStatus());
                });

        ResearchTask done = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED, done.getStatus());
        assertEquals(3, done.getAttempt(), "每轮 PLANNING 2.5s > lease 2s → 3 轮达上限");
        assertTrue(done.getErrorCode() != null
                        && done.getErrorCode().contains("LEASE"),
                "失败码应为租约耗尽: " + done.getErrorCode());

        // 顺序修复核心断言：被回收轮次不写阶段产物（旧实现会在 renewLease 前写）
        List<com.gptr.common.task.TaskEvent> events = taskService.listEvents(task.getId());
        long stageCompleted = events.stream()
                .filter(e -> e.getType() == TaskEventType.STAGE_COMPLETED).count();
        assertEquals(0, stageCompleted,
                "被回收 worker 不得写任何 STAGE_COMPLETED（前置续租 fence）: " + events.size());
        long researchStarted = events.stream()
                .filter(e -> e.getType() == TaskEventType.STAGE_STARTED
                        && e.getStage() == TaskStage.RESEARCH).count();
        assertEquals(0, researchStarted, "每轮都止步于 PLANNING（PLANNING 即超租约）");
        // 成本零双计：失败任务成本保持 0（被回收轮次 recordCost 被 fence 跳过）
        assertEquals(0, done.getCostSpentUsd().signum(),
                "被回收轮次成本不得入账: " + done.getCostSpentUsd());
    }
}
