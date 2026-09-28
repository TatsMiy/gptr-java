package com.gptr.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 爬虫计数的只读入口。
 *
 * <pre>
 * GET /api/v1/crawler/stats   爬虫进程内计数快照（透传）
 * </pre>
 *
 * <p>浏览器不直连爬虫端口：直连会引入跨域，还要多开一个对外端口。这一层<b>只转发</b>——
 * 不改字段、不算汇总、不加缓存：界面要下的判断，依据必须原样来自爬虫；
 * 中间每多一层加工，就多一个与源头漂开的口径。
 *
 * <p>爬虫不可达时回 503 和一句原因，<b>不</b>回空计数：界面据此显示"爬虫不可达"，
 * 而"确实一条都没有"与"根本没问到"是两件事，不能长成同一个样子。
 *
 * <p>爬虫地址沿用 worker 侧的键（{@code gptr.clients.crawler-base-url}），
 * 不新开一个键：同一个部署里两处配置同一个地址，迟早会出现两处不一致。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/crawler")
public class CrawlerController {

    /** 连接本机爬虫的超时：同机服务，慢过这个数就是没起来，不值得再等。 */
    private static final long CONNECT_TIMEOUT_MS = 500;

    /** 读超时：够计数快照返回，又不让界面请求被一个卡住的爬虫拖住。 */
    private static final long READ_TIMEOUT_MS = 2000;

    private final HttpClient http;
    private final String statsUrl;

    public CrawlerController(
            @Value("${gptr.clients.crawler-base-url:http://127.0.0.1:8000}") String crawlerBaseUrl) {
        this.statsUrl = stripTrailingSlash(crawlerBaseUrl) + "/stats";
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
    }

    @GetMapping("/stats")
    public ResponseEntity<Object> stats() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(statsUrl))
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());
        } catch (InterruptedException e) {
            // 中断要还原标志位再退出：吞掉它会让上层的取消逻辑失效
            Thread.currentThread().interrupt();
            log.warn("crawler stats interrupted: {}", statsUrl);
            return unavailable("interrupted");
        } catch (Exception e) {
            log.warn("crawler stats unavailable at {}: {}", statsUrl, describe(e));
            return unavailable(e.getClass().getSimpleName());
        }
    }

    /** 有些连接异常没有 message，此时至少把类型报出来，免得日志里只剩一个 null。 */
    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private ResponseEntity<Object> unavailable(String reason) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "crawler_unreachable", "detail", reason, "url", statsUrl));
    }

    /** 去掉结尾斜杠，避免拼出 {@code //stats} 这种要靠服务端容错的路径。 */
    private static String stripTrailingSlash(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
