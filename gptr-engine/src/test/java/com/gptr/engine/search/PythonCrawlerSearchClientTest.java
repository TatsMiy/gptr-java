package com.gptr.engine.search;

import com.gptr.integration.client.SearchResult;
import com.gptr.integration.exception.PermanentApiException;
import com.gptr.integration.exception.QuotaApiException;
import com.gptr.integration.exception.TransientApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PythonCrawlerSearchClient 契约测试：JDK HttpServer 模拟 gptr-crawler /search，
 * 验证请求格式、响应解析、错误分类。
 */
class PythonCrawlerSearchClientTest {

    private HttpServer server;
    private PythonCrawlerSearchClient client;
    private final AtomicInteger status = new AtomicInteger(200);
    private volatile String responseBody;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new PythonCrawlerSearchClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void parsesSearchResults() {
        responseBody = """
                {"results":[{"title":"T1","url":"https://a.com/1","snippet":"s1"},
                            {"title":"T2","url":"https://b.com/2","snippet":""},
                            {"title":"","url":"","snippet":""}]}""";
        List<SearchResult> results = client.search("java").results();

        assertEquals(2, results.size(), "blank-url results must be skipped");
        assertEquals("T1", results.get(0).title());
        assertEquals("https://a.com/1", results.get(0).url());
        assertEquals("s1", results.get(0).snippet());
    }

    @Test
    void rateLimitMapsToQuota() {
        status.set(429);
        responseBody = "rate limited";
        assertThrows(QuotaApiException.class, () -> client.search("q"));
    }

    @Test
    void serverErrorMapsToTransient() {
        status.set(503);
        responseBody = "down";
        assertThrows(TransientApiException.class, () -> client.search("q"));
    }

    @Test
    void badRequestMapsToPermanent() {
        status.set(400);
        responseBody = "bad retriever";
        assertThrows(PermanentApiException.class, () -> client.search("q"));
    }
}
