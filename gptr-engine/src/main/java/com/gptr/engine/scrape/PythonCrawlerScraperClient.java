package com.gptr.engine.scrape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gptr.engine.HttpDefaults;
import com.gptr.integration.client.ScrapeBatch;
import com.gptr.integration.client.ScrapeOutcome;
import com.gptr.integration.client.ScraperClient;
import com.gptr.integration.client.ScrapedContent;
import com.gptr.integration.exception.PermanentApiException;
import com.gptr.integration.exception.QuotaApiException;
import com.gptr.integration.exception.TransientApiException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Python 爬虫服务抓取客户端：调用 gptr-crawler 的 {@code POST /scrape}，
 * 复用 gpt-researcher 的抓取器（beautiful_soup 等）。
 *
 * <p>错误分类：429 → Quota；5xx → Transient；4xx → Permanent。
 */
public class PythonCrawlerScraperClient implements ScraperClient {

    /** {@code /scrape} 单请求超时（秒）——抓取含公网往返与正文清洗，故长于检索。 */
    private static final int SCRAPE_REQUEST_TIMEOUT_SECONDS = 60;

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public PythonCrawlerScraperClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HttpDefaults.CONNECT_TIMEOUT_SECONDS)).build();
    }

    @Override
    public String name() {
        return "python-crawler";
    }

    /**
     * 抓取并带回每个 URL 的交代。关联 id 以 {@link HttpDefaults#REQUEST_ID_HEADER} 发给后端，
     * 使后端日志与任务日志能按同一 id 对上（空值 = 不带该头）。
     */
    @Override
    public ScrapeBatch scrapeDetailed(List<String> urls, int maxCharsPerUrl, String requestId) {
        try {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode arr = body.putArray("urls");
            urls.forEach(arr::add);
            if (maxCharsPerUrl > 0) {
                body.put("max_chars", maxCharsPerUrl);
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/scrape"))
                    .timeout(Duration.ofSeconds(SCRAPE_REQUEST_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json");
            if (requestId != null && !requestId.isBlank()) {
                builder.header(HttpDefaults.REQUEST_ID_HEADER, requestId);
            }
            HttpRequest request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 429) {
                throw new QuotaApiException(name(), "rate limited: " + status);
            }
            if (status >= 500) {
                throw new TransientApiException(name(), "crawler server error: " + status);
            }
            if (status >= 400) {
                throw new PermanentApiException(name(), "crawler http " + status);
            }
            return parseBatch(response.body());
        } catch (PermanentApiException | TransientApiException e) {
            throw e;
        } catch (Exception e) {
            throw new TransientApiException(name(), "crawler scrape failed: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 {@code /scrape} 响应：正文与逐 URL 交代各取所需。
     *
     * <p>只读本客户端要用的字段（树式 {@code path(...)}），后端新增字段不会影响解析
     * —— 这正是"只加字段"能向后兼容的前提。
     */
    static ScrapeBatch parseBatch(String json) throws Exception {
        JsonNode root = new ObjectMapper().readTree(json);
        List<ScrapedContent> contents = new ArrayList<>();
        for (JsonNode item : root.path("contents")) {
            String url = item.path("url").asText("");
            String content = item.path("content").asText("");
            // 防御性过滤：url 或正文为空则跳过（Python 壳已过滤，Java 侧双保险）
            if (url.isBlank() || content.isBlank()) {
                continue;
            }
            contents.add(new ScrapedContent(
                    url,
                    item.path("title").asText(""),
                    content));
        }

        List<ScrapeOutcome> outcomes = new ArrayList<>();
        for (JsonNode item : root.path("outcomes")) {
            String url = item.path("url").asText("");
            if (url.isBlank()) {
                continue;
            }
            outcomes.add(new ScrapeOutcome(
                    url,
                    item.path("reason").asText(""),
                    item.path("page_kind").asText(""),
                    item.path("truncated").asBoolean(false),
                    item.path("degraded").asBoolean(false)));
        }
        return new ScrapeBatch(contents, outcomes);
    }

    /** 只要正文时的视图（与 {@link #parseBatch} 同一份解析结果）。 */
    static List<ScrapedContent> parse(String json) throws Exception {
        return parseBatch(json).contents();
    }
}
