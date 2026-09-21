package com.gptr.engine.search;

import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * P0-4 禁止来源过滤（对标 Bench II blocked list 的工具层屏蔽，比 prompt 阻断更彻底）：
 * 在检索结果层丢弃命中 blocked 的 URL（条目语义：含 "://" = URL 前缀匹配；
 * 否则 = 域名匹配 host 或其子域）。flat 与 deep（图内 search/scrape 目标）都经此过滤
 * ——被屏蔽源不会进入检索结果/抓取目标，报告自然无法引用它（引用闸门兜底）。
 */
public final class BlockedSearchClient implements SearchClient {

    private final SearchClient delegate;
    private final List<String> blocked;

    public BlockedSearchClient(SearchClient delegate, List<String> blocked) {
        this.delegate = delegate;
        this.blocked = blocked == null ? List.of() : blocked;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public SearchResponse search(String query) {
        return search(query, SearchOptions.DEFAULT);
    }

    @Override
    public SearchResponse search(String query, SearchOptions opts) {
        // sourceUsed 透传底层真实源（屏蔽 URL 不影响"谁命中"标识）
        SearchResponse resp = delegate.search(query, opts);
        if (blocked.isEmpty() || resp.results().isEmpty()) {
            return resp;
        }
        List<SearchResult> out = new ArrayList<>();
        for (SearchResult r : resp.results()) {
            if (!matches(r.url())) {
                out.add(r);
            }
        }
        return new SearchResponse(out, resp.sourceUsed());
    }

    /** blocked 条目命中判定：含 "://" → URL 前缀；否则 → host 或子域。 */
    boolean matches(String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim().toLowerCase();
        for (String b : blocked) {
            String entry = b.trim().toLowerCase();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.contains("://")) {
                if (u.startsWith(entry)) {
                    return true;
                }
            } else {
                String host = hostOf(u);
                if (host != null && (host.equals(entry) || host.endsWith("." + entry))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 提取 host（去协议/路径/查询/端口）。 */
    static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        String s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        return s.isEmpty() ? null : s;
    }
}
