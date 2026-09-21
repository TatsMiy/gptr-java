package com.gptr.engine.search;

import com.gptr.integration.client.SearchResult;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DuckDuckGoSearchClient 契约测试：JDK HttpServer 模拟 html.duckduckgo.com，
 * 验证 HTML 解析（含 uddg 重定向解码）、错误分类。
 */
class DuckDuckGoSearchClientTest {

    private HttpServer server;
    private DuckDuckGoSearchClient client;
    private final AtomicInteger status = new AtomicInteger(200);
    private volatile String responseBody;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/html/", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        // 指向本地模拟端点
        client = new DuckDuckGoSearchClient("http://127.0.0.1:" + server.getAddress().getPort() + "/html/");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void parsesResultsAndDecodesDdgRedirect() {
        responseBody = """
                <html><body>
                  <div class="result">
                    <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage%3Fa%3D1&amp;rut=abc">Example Title</a>
                    <a class="result__snippet">Some snippet text</a>
                  </div>
                  <div class="result">
                    <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fother.org%2Fx">Other Org</a>
                  </div>
                  <div class="result">
                    <a class="result__a" href="#skip">No URL</a>
                  </div>
                </body></html>""";
        List<SearchResult> results = client.search("test query").results();

        assertEquals(2, results.size(), "only results with decodable URLs count");
        assertEquals("Example Title", results.get(0).title());
        assertEquals("https://example.com/page?a=1", results.get(0).url(), "uddg must be decoded to real URL");
        assertEquals("Some snippet text", results.get(0).snippet());
        assertEquals("https://other.org/x", results.get(1).url());
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
    void blockedMapsToTransient() {
        status.set(403);
        responseBody = "blocked";
        assertThrows(TransientApiException.class, () -> client.search("q"));
    }

    @Test
    void parseHandlesEmptyHtml() {
        assertTrue(DuckDuckGoSearchClient.parse("<html><body><div class='result'></div></body></html>").isEmpty());
    }
}
