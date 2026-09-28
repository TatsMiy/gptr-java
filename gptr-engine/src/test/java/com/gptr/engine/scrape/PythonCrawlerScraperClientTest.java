package com.gptr.engine.scrape;

import com.gptr.integration.client.ScrapedContent;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PythonCrawlerScraperClient 契约测试：JDK HttpServer 模拟 gptr-crawler /scrape。
 */
class PythonCrawlerScraperClientTest {

    private HttpServer server;
    private PythonCrawlerScraperClient client;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> seenRequestId = new AtomicReference<>();
    private volatile String responseBody;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/scrape", exchange -> {
            seenRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new PythonCrawlerScraperClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void parsesScrapedContents() {
        responseBody = """
                {"contents":[{"url":"https://a.com","title":"A","content":"body-a"},
                            {"url":"https://b.com","title":"B","content":""}]}""";
        List<ScrapedContent> contents = client.scrape(List.of("https://a.com", "https://b.com"));

        assertEquals(1, contents.size(), "empty-content items must be skipped");
        assertEquals("https://a.com", contents.get(0).url());
        assertEquals("body-a", contents.get(0).content());
    }

    /** 新增的 outcomes 字段只增不改：contents 的解析结果必须与旧响应完全一致。 */
    @Test
    void ignoresOutcomesFieldAndStillParsesContents() {
        responseBody = """
                {"contents":[{"url":"https://a.com","title":"A","content":"body-a"}],
                 "outcomes":[{"url":"https://a.com","reason":"ok","page_kind":"article"},
                             {"url":"https://b.com","reason":"too_short","page_kind":"unknown"}]}""";
        List<ScrapedContent> contents = client.scrape(List.of("https://a.com", "https://b.com"));

        assertEquals(1, contents.size());
        assertEquals("https://a.com", contents.get(0).url());
        assertEquals("body-a", contents.get(0).content());
    }

    @Test
    void sendsTheCallerRequestIdAsAHeader() {
        responseBody = "{\"contents\":[]}";
        client.scrape(List.of("https://a.com"), 0, "task-123");

        assertEquals("task-123", seenRequestId.get());
    }

    @Test
    void omitsTheRequestIdHeaderWhenThereIsNone() {
        responseBody = "{\"contents\":[]}";
        client.scrape(List.of("https://a.com"));

        assertNull(seenRequestId.get());
    }

    @Test
    void rateLimitMapsToQuota() {
        status.set(429);
        responseBody = "rate limited";
        assertThrows(QuotaApiException.class, () -> client.scrape(List.of("https://a.com")));
    }

    @Test
    void serverErrorMapsToTransient() {
        status.set(502);
        responseBody = "scrape failed";
        assertThrows(TransientApiException.class, () -> client.scrape(List.of("https://a.com")));
    }
}
