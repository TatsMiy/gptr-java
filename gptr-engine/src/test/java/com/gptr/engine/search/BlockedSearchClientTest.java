package com.gptr.engine.search;

import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * blocked 来源过滤单测：URL 前缀与域名（含子域）两种条目语义、端口剥离。
 */
class BlockedSearchClientTest {

    private static SearchClient stub(List<String> urls) {
        return new SearchClient() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public SearchResponse search(String query) {
                return search(query, SearchOptions.DEFAULT);
            }

            @Override
            public SearchResponse search(String query, SearchOptions opts) {
                return new SearchResponse(urls.stream()
                        .map(u -> new SearchResult("t", u, "snippet"))
                        .toList(), name());
            }
        };
    }

    @Test
    void domainEntryBlocksHostAndSubdomains() {
        BlockedSearchClient c = new BlockedSearchClient(stub(List.of(
                "https://nobelprize.org/prizes/medicine/2024/press-release",
                "https://www.nobelprize.org/prizes/chemistry/",
                "https://en.wikipedia.org/wiki/Nobel_Prize",
                "https://news.example.org/med2024")), List.of("nobelprize.org"));

        List<SearchResult> out = c.search("nobel 2024").results();
        assertEquals(2, out.size(), "nobelprize.org 及子域应被过滤（www 也算子域）");
        assertTrue(out.get(0).url().contains("wikipedia.org"));
        assertTrue(out.get(1).url().contains("news.example.org"));
    }

    @Test
    void prefixEntryBlocksExactUrlPath() {
        BlockedSearchClient c = new BlockedSearchClient(stub(List.of(
                "https://example.gov/awards/2024/result",
                "https://example.gov/other/2024/result")),
                List.of("https://example.gov/awards/"));

        List<SearchResult> out = c.search("q").results();
        assertEquals(1, out.size());
        assertTrue(out.get(0).url().contains("/other/"));
    }

    @Test
    void hostOfStripsSchemePathPortAndQuery() {
        assertEquals("example.com", BlockedSearchClient.hostOf("https://example.com:8443/a/b?q=1"));
        assertEquals("a.example.org", BlockedSearchClient.hostOf("http://a.example.org"));
        assertEquals(null, BlockedSearchClient.hostOf(""));
    }

    @Test
    void noBlockedEntriesPassesThrough() {
        BlockedSearchClient c = new BlockedSearchClient(stub(List.of(
                "https://a.org/x", "https://b.org/y")), List.of());
        assertEquals(2, c.search("q").results().size());
        assertFalse(c.matches("https://a.org/x"));
    }
}
