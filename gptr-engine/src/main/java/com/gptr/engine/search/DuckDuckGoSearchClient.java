package com.gptr.engine.search;


import com.gptr.engine.HttpDefaults;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
import com.gptr.integration.exception.QuotaApiException;
import com.gptr.integration.exception.TransientApiException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * DuckDuckGo 检索客户端（零 key 免费）。
 *
 * <p>抓取 {@code html.duckduckgo.com/html/?q=} 结果页并用 Jsoup 解析
 * （原版 gpt-researcher 同款思路）。结果链接为 DDG 重定向
 * （{@code //duckduckgo.com/l/?uddg=...}），需解码 {@code uddg} 参数取真实 URL。
 *
 * <p>错误分类：429/403 → Quota/Transient（触发弹性层重试/降级）；5xx → Transient。
 */
public class DuckDuckGoSearchClient implements SearchClient {

    private static final String ENDPOINT = "https://html.duckduckgo.com/html/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    /** 结果页单请求超时（秒）——检索结果轻量，故短于抓取与 LLM。 */
    private static final int SEARCH_REQUEST_TIMEOUT_SECONDS = 30;

    private final String endpoint;
    private final HttpClient http;

    public DuckDuckGoSearchClient() {
        this(ENDPOINT);
    }

    /** 可指定端点（测试指向本地模拟；生产用默认）。 */
    public DuckDuckGoSearchClient(String endpoint) {
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HttpDefaults.CONNECT_TIMEOUT_SECONDS)).build();
    }

    @Override
    public String name() {
        return "duckduckgo";
    }

    @Override
    public SearchResponse search(String query) {
        return new SearchResponse(doSearch(query), name());
    }

    /** 原生 DDG 无 max_results 参数：客户端截断到 maxResults（retriever 选项忽略）。 */
    @Override
    public SearchResponse search(String query, SearchOptions opts) {
        List<SearchResult> all = doSearch(query);
        if (opts != null && opts.maxResults() != null && opts.maxResults() > 0
                && all.size() > opts.maxResults()) {
            all = all.subList(0, opts.maxResults());
        }
        return new SearchResponse(all, name());
    }

    private List<SearchResult> doSearch(String query) {
        String url = endpoint + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(SEARCH_REQUEST_TIMEOUT_SECONDS))
                    .header("User-Agent", USER_AGENT)
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 429) {
                throw new QuotaApiException(name(), "rate limited: " + status);
            }
            if (status == 403) {
                throw new TransientApiException(name(), "blocked (403) - likely bot check, retry");
            }
            if (status >= 500) {
                throw new TransientApiException(name(), "server error: " + status);
            }
            if (status >= 400) {
                throw new TransientApiException(name(), "http " + status);
            }
            return parse(response.body());
        } catch (TransientApiException e) {
            throw e;
        } catch (Exception e) {
            throw new TransientApiException(name(), "duckduckgo search failed: " + e.getMessage(), e);
        }
    }

    /** 解析 DDG HTML 结果页：{@code .result} 容器 → title / 真实 URL（uddg 解码）/ snippet。 */
    static List<SearchResult> parse(String html) {
        Document doc = Jsoup.parse(html);
        List<SearchResult> results = new ArrayList<>();
        Elements items = doc.select(".result");
        for (Element item : items) {
            Element link = item.selectFirst("a.result__a");
            Element snippet = item.selectFirst("a.result__snippet");
            if (link == null) {
                continue;
            }
            String title = link.text();
            String url = decodeDdgUrl(link.attr("href"));
            if (url == null) {
                continue;
            }
            results.add(new SearchResult(title, url, snippet == null ? "" : snippet.text()));
        }
        return results;
    }

    /** DDG 结果链接形如 {@code //duckduckgo.com/l/?uddg=<encoded-url>&rut=...}，取 uddg 参数解码。 */
    private static String decodeDdgUrl(String href) {
        try {
            if (href.contains("uddg=")) {
                String raw = href.substring(href.indexOf("uddg=") + 5);
                int amp = raw.indexOf('&');
                if (amp >= 0) {
                    raw = raw.substring(0, amp);
                }
                String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
                return decoded.startsWith("http") ? decoded : null;
            }
            return href.startsWith("http") ? href : null;
        } catch (Exception e) {
            // URL 解码/解析失败 → 视为非结果链接（跳过该条目）
            return null;
        }
    }
}
