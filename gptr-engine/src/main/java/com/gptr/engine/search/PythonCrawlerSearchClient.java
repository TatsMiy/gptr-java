package com.gptr.engine.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gptr.common.config.RetrieverKeyNames;
import com.gptr.engine.HttpDefaults;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
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
 * Python 爬虫服务检索客户端：调用 gptr-crawler 的 {@code POST /search}，
 * 复用 gpt-researcher 的 21 个检索器。检索器不硬编码——构造时给默认
 * retriever/maxResults，单次搜索可用 {@link SearchOptions} 覆盖（引擎任务级 config 传入）。
 *
 * <p>错误分类：429 → Quota；5xx → Transient；4xx → Permanent。
 */
public class PythonCrawlerSearchClient implements SearchClient {

    public static final String DEFAULT_RETRIEVER = "duckduckgo";
    public static final int DEFAULT_MAX_RESULTS = 5;

    /** {@code /search} 单请求超时（秒）——检索结果轻量，故短于抓取与 LLM。 */
    private static final int SEARCH_REQUEST_TIMEOUT_SECONDS = 30;

    private final String baseUrl;
    private final String defaultRetriever;
    private final int defaultMaxResults;
    /** 检索器 key 表；{@code EMPTY} = 全部回落 env，等价于改动前行为。 */
    private final RetrieverKeys retrieverKeys;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public PythonCrawlerSearchClient(String baseUrl) {
        this(baseUrl, DEFAULT_RETRIEVER, DEFAULT_MAX_RESULTS);
    }

    /** @param defaultRetriever 默认检索器（如 duckduckgo/arxiv/bocha），可被 SearchOptions 覆盖 */
    public PythonCrawlerSearchClient(String baseUrl, String defaultRetriever, int defaultMaxResults) {
        this(baseUrl, defaultRetriever, defaultMaxResults, RetrieverKeys.EMPTY);
    }

        /** 生产构造：额外接受检索器 key 表，按当前检索器注入 header。 */
    public PythonCrawlerSearchClient(String baseUrl, String defaultRetriever, int defaultMaxResults,
                                     RetrieverKeys retrieverKeys) {
        this.baseUrl = baseUrl;
        this.defaultRetriever = defaultRetriever == null ? DEFAULT_RETRIEVER : defaultRetriever;
        this.defaultMaxResults = defaultMaxResults > 0 ? defaultMaxResults : DEFAULT_MAX_RESULTS;
        this.retrieverKeys = retrieverKeys == null ? RetrieverKeys.EMPTY : retrieverKeys;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HttpDefaults.CONNECT_TIMEOUT_SECONDS)).build();
    }

    @Override
    public String name() {
        return "python-crawler";
    }

    @Override
    public SearchResponse search(String query) {
        return search(query, SearchOptions.DEFAULT);
    }

    @Override
    public SearchResponse search(String query, SearchOptions opts) {
        SearchOptions effective = (opts == null ? SearchOptions.DEFAULT : opts)
                .withDefaults(new SearchOptions(defaultRetriever, defaultMaxResults));
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("query", query);
            body.put("max_results", effective.maxResults());
            body.put("retriever", effective.retriever());

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/search"))
                    .timeout(Duration.ofSeconds(SEARCH_REQUEST_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json");
                        // 按当前检索器注入 key。
                        // 只发当前检索器所需的那一个；未配置 ⇒ 不发 header（由 crawler 回落环境变量）。
            String retriever = effective.retriever();
            if (retrieverKeys.has(retriever)) {
                builder.header(retrieverKeys.headerName(retriever), retrieverKeys.keyOf(retriever));
            }
            if (RetrieverKeyNames.GOOGLE.equals(retriever) && retrieverKeys.googleCxKey() != null) {
                builder.header(RetrieverKeyNames.googleCxHeader(), retrieverKeys.googleCxKey());
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
                throw new PermanentApiException(name(), "crawler http " + status + ": " + truncate(response.body()));
            }
            return new SearchResponse(parse(response.body()), name());
        } catch (PermanentApiException | TransientApiException e) {
            throw e;
        } catch (Exception e) {
            throw new TransientApiException(name(), "crawler search failed: " + e.getMessage(), e);
        }
    }

    static List<SearchResult> parse(String json) throws Exception {
        JsonNode root = new ObjectMapper().readTree(json);
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            String url = item.path("url").asText("");
            if (url.isBlank()) {
                continue;
            }
            results.add(new SearchResult(
                    item.path("title").asText(""),
                    url,
                    item.path("snippet").asText(""),
                    item.path("content").asText("")));
        }
        return results;
    }

    private static String truncate(String s) {
        return s == null ? ""
                : s.substring(0, Math.min(HttpDefaults.ERROR_BODY_MAX_CHARS, s.length()));
    }
}
