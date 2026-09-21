package com.gptr.api;

import com.gptr.common.repository.TaskRepository;
import com.gptr.common.service.CreateTaskCommand;
import com.gptr.common.service.TaskService;
import com.gptr.common.task.ResearchTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.gptr.engine.testsupport.IntegrationDbGuard;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 验收：订阅 {@code /ws/tasks/{taskId}} 后
 * 收到该任务的事件推送（含创建时的 CREATED 事件）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gptr.ws.poll-interval-ms=200")
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskWebSocketIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired TaskService taskService;
    @Autowired TaskRepository taskRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        IntegrationDbGuard.truncateTasks(jdbcTemplate);
    }

    @Test
    void wsReceivesTaskEvents() throws Exception {
        ResearchTask task = taskService.create(new CreateTaskCommand("ws topic", "{}", null));

        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                messages.add(message.getPayload());
            }
        }, new WebSocketHttpHeaders(),
                URI.create("ws://localhost:" + port + "/ws/tasks/" + task.getId()))
                .get(5, TimeUnit.SECONDS);

        try {
            String msg = messages.poll(10, TimeUnit.SECONDS);
            assertNotNull(msg, "must receive at least one event after subscribing");
            assertTrue(msg.contains("\"type\":\"CREATED\""),
                    "first pushed event should be CREATED, got: " + msg);
            assertTrue(msg.contains(task.getId().toString()), "event payload must carry taskId");
        } finally {
            session.close();
        }
    }
}
