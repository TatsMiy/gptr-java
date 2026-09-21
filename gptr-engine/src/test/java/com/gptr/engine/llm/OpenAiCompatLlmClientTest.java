package com.gptr.engine.llm;

import com.gptr.integration.exception.PermanentApiException;
import com.gptr.integration.exception.QuotaApiException;
import com.gptr.integration.exception.TransientApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAiCompatLlmClient 契约测试：用 JDK HttpServer 模拟 OpenAI 兼容端点，
 * 验证请求格式（model/messages/json_mode）、响应解析、usage 成本、错误分类。
 */
class OpenAiCompatLlmClientTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> requestBodies = new ArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private volatile String responseBody;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenAiCompatLlmClient client() {
        return new OpenAiCompatLlmClient("llm", baseUrl, "test-key", "deepseek-chat");
    }

    @Test
    void chatParsesContentAndComputesCost() {
        responseBody = """
                {"choices":[{"message":{"role":"assistant","content":"hello from llm"}}],
                 "usage":{"prompt_tokens":100,"completion_tokens":50}}""";
        OpenAiCompatLlmClient client = client();

        assertEquals("hello from llm", client.chat("sys", "user"));
        assertTrue(client.lastCallCostUsd() > 0, "cost must be computed from usage");
        // 请求体校验
        assertTrue(requestBodies.get(0).contains("\"model\":\"deepseek-chat\""));
        assertTrue(requestBodies.get(0).contains("\"role\":\"system\""));
    }

    @Test
    void chatJsonAddsResponseFormat() {
        responseBody = """
                {"choices":[{"message":{"role":"assistant","content":"{\\"queries\\":[]}"}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":10}}""";
        client().chatJson("sys", "user");

        assertTrue(requestBodies.get(0).contains("\"response_format\""));
        assertTrue(requestBodies.get(0).contains("\"json_object\""));
    }

    @Test
    void rateLimitMapsToQuota() {
        status.set(429);
        responseBody = "{\"error\":{\"message\":\"rate limited\"}}";
        assertThrows(QuotaApiException.class, () -> client().chat("sys", "user"));
    }

    @Test
    void serverErrorMapsToTransient() {
        status.set(500);
        responseBody = "{\"error\":{\"message\":\"boom\"}}";
        assertThrows(TransientApiException.class, () -> client().chat("sys", "user"));
    }

    @Test
    void badAuthMapsToPermanent() {
        status.set(401);
        responseBody = "{\"error\":{\"message\":\"invalid key\"}}";
        assertThrows(PermanentApiException.class, () -> client().chat("sys", "user"));
    }
}
