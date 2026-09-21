package com.gptr.benchmark.dims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gptr.engine.budget.Budgets;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评测侧来源抓取器（D2 / KAE-lite 共用）：批量抓 URL 正文并缓存（失败记空串防重抓），
 * 以及按"句子引用集"拼接判定语料。URL 抓取失败视为该源不可用（unverifiable），
 * 不中断判定流程。
 */
public final class SourceFetcher {

    /** 单批最多抓取 URL 数（防评测抓取放大）。 */
    public static final int MAX_BATCH_URLS = 8;
    /** 单条 URL 正文进入语料/抽取的字符上限 —— **值取自 engine 的预算载体**
           *  （原此处与 {@code ResearchEngineImpl} 各声明一份 3000，合一）。 */
    private static final int MAX_CHARS_PER_SOURCE =
            Budgets.defaults().extraction().maxCharsPerSource();

    private final String crawlerBase;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public SourceFetcher(String crawlerBase) {
        this.crawlerBase = crawlerBase;
    }

    /** 批量抓取缺失 URL 进缓存：url → 正文（HTTP 失败/未返回 = 空串）。
     *  ③：评测对报告自引 URL 二次公网抓取，429/5xx/网络波动按退避重试（800ms×n），
     *  仍失败才记空串（调用方计 unverifiable/fetchFailed，不中断评测）。 */
    public void fetchInto(List<String> urls, Map<String, String> cache) {
        List<String> missing = new ArrayList<>();
        for (String u : urls) {
            if (!cache.containsKey(u)) {
                missing.add(u);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        List<String> targets = missing.size() > MAX_BATCH_URLS
                ? missing.subList(0, MAX_BATCH_URLS) : missing;
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (attempt > 0) {
                    Thread.sleep(800L * attempt); // 800ms / 1600ms 退避
                }
                ObjectNode body = mapper.createObjectNode();
                ArrayNode arr = body.putArray("urls");
                targets.forEach(arr::add);
                HttpRequest req = HttpRequest.newBuilder(URI.create(crawlerBase + "/scrape"))
                        .timeout(Duration.ofSeconds(90))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status >= 400) {
                    // 429/5xx 瞬时 → 重试；4xx 语义错误 → 放弃重试
                    if (status == 429 || status >= 500) {
                        lastFailure = new IOException("crawler http " + status);
                        continue;
                    }
                    markFailed(targets, cache);
                    return;
                }
                Set<String> returned = new LinkedHashSet<>();
                for (JsonNode c : mapper.readTree(resp.body()).path("contents")) {
                    cache.put(c.path("url").asText(""), c.path("content").asText(""));
                    returned.add(c.path("url").asText(""));
                }
                for (String u : targets) {
                    if (!returned.contains(u)) {
                        cache.put(u, ""); // 未返回 = 抓取失败（缓存空串防重抓）
                    }
                }
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                markFailed(targets, cache);
                return;
            } catch (Exception e) {
                lastFailure = e;
            }
        }
        // 重试耗尽：全部记失败（含 lastFailure 为 null 的防御分支）
        markFailed(targets, cache);
    }

    private static void markFailed(List<String> targets, Map<String, String> cache) {
        for (String u : targets) {
            cache.put(u, "");
        }
    }

    /**
     * 按引用 URL 集拼接判定语料（每条 [来源 url] + 正文截断）；
     * 全部 URL 抓取失败 → null（调用方计 unverifiable）。
     */
    public static String corpusFor(List<String> citationUrls, Map<String, String> cache) {
        StringBuilder sb = new StringBuilder();
        for (String url : citationUrls) {
            String content = cache.get(url);
            if (content == null || content.isBlank()) {
                continue;
            }
            sb.append("[来源 ").append(url).append("]\n")
                    .append(ReportText.truncate(content, MAX_CHARS_PER_SOURCE)).append("\n\n");
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
