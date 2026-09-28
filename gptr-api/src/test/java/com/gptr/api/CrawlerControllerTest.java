package com.gptr.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 爬虫计数入口的单测：透传、不可达、以及"卡住不拖住界面"。
 *
 * <p>用一个 JDK 自带的 {@code HttpServer} 当假爬虫（不引入任何测试依赖，
 * 也不需要真的起 Python 服务）；不可达用"刚释放的本机端口"制造。
 */
class CrawlerControllerTest {

    private static final String STATS_BODY =
            "{\"uptime_s\":12,\"workers\":1,\"scrape\":{\"total\":50,\"outcome\":{\"ok\":40}}}";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 起一个本机假爬虫，返回它的端口。 */
    private int startFakeCrawler(int status, String body, long delayMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stats", exchange -> {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return server.getAddress().getPort();
    }

    /** 一个刚被释放的本机端口：大概率无人监听。 */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String bodyOf(ResponseEntity<Object> response) {
        return String.valueOf(response.getBody());
    }

    @Test
    void passesTheCrawlerPayloadThroughUnchanged() throws IOException {
        int port = startFakeCrawler(200, STATS_BODY, 0);
        ResponseEntity<Object> response =
                new CrawlerController("http://127.0.0.1:" + port).stats();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(STATS_BODY, bodyOf(response), "透传不得改字段或补算汇总");
    }

    @Test
    void trailingSlashInTheConfiguredAddressDoesNotBreakThePath() throws IOException {
        int port = startFakeCrawler(200, STATS_BODY, 0);
        ResponseEntity<Object> response =
                new CrawlerController("http://127.0.0.1:" + port + "/").stats();
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void crawlerStatusIsPassedThroughRatherThanMasked() throws IOException {
        int port = startFakeCrawler(500, "{\"detail\":\"boom\"}", 0);
        ResponseEntity<Object> response =
                new CrawlerController("http://127.0.0.1:" + port).stats();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void unreachableCrawlerBecomes503WithAReason() throws IOException {
        int port = closedPort();
        ResponseEntity<Object> response =
                new CrawlerController("http://127.0.0.1:" + port).stats();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("crawler_unreachable", body.get("error"));
        // 与"零计数"必须长得不一样，界面才能分辨"问不到"和"确实没有"
        assertTrue(body.containsKey("detail"));
    }

    @Test
    void aStuckCrawlerDoesNotHoldTheInterfaceRequestOpen() throws IOException {
        int port = startFakeCrawler(200, STATS_BODY, 5000);
        long started = System.currentTimeMillis();
        ResponseEntity<Object> response =
                new CrawlerController("http://127.0.0.1:" + port).stats();
        long elapsedMs = System.currentTimeMillis() - started;
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertTrue(elapsedMs < 4000, "读超时必须封顶，实际耗时 " + elapsedMs + "ms");
    }
}
