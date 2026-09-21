package com.gptr.worker;

import com.gptr.common.repository.TaskRepository;
import com.gptr.common.service.TaskService;
import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskStatus;
import com.gptr.integration.storage.ReportStorage;
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
 * 报告存储验收：MinIO 模式（连接 docker compose 的 MinIO，
 * localhost:9000），任务完成后报告上传对象存储，result_ref 为 minio:// 且可读回。
 */
@SpringBootTest(properties = {
        "gptr.clients.search-chain=tavily:ok",
        "worker.poll-interval-ms=300",
        "worker.reap-interval-ms=300",
        "worker.lease-seconds=5",
        "gptr.storage.type=minio",
        "gptr.storage.minio.endpoint=http://localhost:9000",
        "gptr.storage.minio.access-key=gptr",
        "gptr.storage.minio.secret-key=gptr12345",
        "gptr.storage.minio.bucket=gptr-reports",
        "gptr.engine.allow-mock-config=true"
})
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MinioStorageIntegrationTest {

    @Autowired TaskService taskService;
    @Autowired TaskRepository taskRepository;
    @Autowired ReportStorage reportStorage;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        IntegrationDbGuard.truncateTasks(jdbcTemplate);
    }

    @Test
    void reportUploadedToMinioOnSuccess() {
        ResearchTask task = taskService.create(new com.gptr.common.service.CreateTaskCommand(
                "minio storage topic", "{\"mock\":{\"stageDelayMs\":10}}", null));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertEquals(TaskStatus.SUCCEEDED,
                        taskRepository.findById(task.getId()).orElseThrow().getStatus()));

        ResearchTask done = taskRepository.findById(task.getId()).orElseThrow();
        assertTrue(done.getResultRef().startsWith("minio://"),
                "result_ref must point at MinIO, got: " + done.getResultRef());

        // 读回报告内容验证
        String content = reportStorage.get(done.getResultRef());
        assertTrue(content != null && !content.isBlank(),
                "stored report must be readable and non-blank");
    }
}
