package com.gptr.worker;

import com.gptr.common.repository.TaskRepository;
import com.gptr.common.repository.WebhookDeliveryRepository;
import com.gptr.common.service.TaskService;
import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskStatus;
import com.gptr.common.task.WebhookDelivery;
import com.gptr.common.task.WebhookStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.gptr.engine.testsupport.IntegrationDbGuard;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Webhook 验收：任务终态 → 签名投递；失败指数退避重试。
 *
 * <p>用 JDK 内置 HttpServer 在随机端口接收 webhook，验证 HMAC 签名与负载。
 */
@SpringBootTest(properties = {
        "gptr.clients.search-chain=tavily:ok",
        "worker.poll-interval-ms=300",
        "worker.reap-interval-ms=300",
        "worker.lease-seconds=5",
        "gptr.webhook.secret=gptr-test-secret",
        "gptr.webhook.backoff-base-ms=100",
        "gptr.webhook.poll-interval-ms=200",
        "gptr.webhook.allow-private-urls=true",
        "gptr.engine.allow-mock-config=true"
})
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebhookIntegrationTest {

    @Autowired TaskService taskService;
    @Autowired TaskRepository taskRepository;
    @Autowired WebhookDeliveryRepository webhookRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static HttpServer server;
    private static int port;
    private static final List<Received> received = new CopyOnWriteArrayList<>();
    private static volatile boolean failFirst = false;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(
                    exchange.getRequestHeaders().getFirst("X-GPTR-Signature"),
                    exchange.getRequestHeaders().getFirst("X-GPTR-Timestamp"),
                    body));
            int code = failFirst && received.size() == 1 ? 500 : 200;
            exchange.sendResponseHeaders(code, 0);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void clean() {
        IntegrationDbGuard.truncateTasks(jdbcTemplate);
        received.clear();
        failFirst = false;
    }

    @Test
    void webhookDeliveredWithSignatureOnSuccess() throws Exception {
        String callback = "http://127.0.0.1:" + port + "/hook";
        ResearchTask task = taskService.create(new com.gptr.common.service.CreateTaskCommand(
                "webhook topic",
                "{\"callbackUrl\":\"" + callback + "\",\"mock\":{\"stageDelayMs\":10}}",
                null));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertEquals(TaskStatus.SUCCEEDED,
                        taskRepository.findById(task.getId()).orElseThrow().getStatus()));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertEquals(1, received.size(), "webhook must be delivered once"));

        Received r = received.get(0);
        assertTrue(r.body().contains("\"status\":\"SUCCEEDED\""), "payload must carry terminal status");
        assertTrue(r.body().contains(task.getId().toString()), "payload must carry taskId");
        // 签名验证：secret + timestamp + "." + payload 的 HMAC-SHA256
        String expected = sign("gptr-test-secret", r.timestamp() + "." + r.body());
        assertEquals(expected, r.signature(), "HMAC signature must match");

        WebhookDelivery delivery = webhookRepository.findAll().stream()
                .filter(d -> d.getTaskId().equals(task.getId())).findFirst().orElseThrow();
        assertEquals(WebhookStatus.SENT, delivery.getStatus());
        assertNotNull(delivery.getLastSuccessAt());
    }

    @Test
    void webhookRetriesAfterFailure() {
        failFirst = true;
        String callback = "http://127.0.0.1:" + port + "/hook";
        ResearchTask task = taskService.create(new com.gptr.common.service.CreateTaskCommand(
                "webhook retry topic",
                "{\"callbackUrl\":\"" + callback + "\",\"mock\":{\"stageDelayMs\":10}}",
                null));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertEquals(TaskStatus.SUCCEEDED,
                        taskRepository.findById(task.getId()).orElseThrow().getStatus()));

        // 第一次 500 失败 → 退避重试第二次成功；投递记录最终置 SENT（轮询等待落库）
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertTrue(received.size() >= 2, "expected retry after failure"));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    WebhookDelivery delivery = webhookRepository.findAll().stream()
                            .filter(d -> d.getTaskId().equals(task.getId())).findFirst().orElseThrow();
                    assertEquals(WebhookStatus.SENT, delivery.getStatus(), "must be SENT after retry");
                    assertTrue(delivery.getAttempts() >= 1, "must have recorded at least one failure");
                });
    }

    private static String sign(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private record Received(String signature, String timestamp, String body) {
    }
}
