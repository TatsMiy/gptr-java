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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PythonCrawlerScraperClient 契约测试：JDK HttpServer 模拟 gptr-crawler /scrape。
 */
class PythonCrawlerScraperClientTest {

    private HttpServer server;
    private PythonCrawlerScraperClient client;
    private final AtomicInteger status = new AtomicInteger(200);
    private volatile String responseBody;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/scrape", exchange -> {
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
