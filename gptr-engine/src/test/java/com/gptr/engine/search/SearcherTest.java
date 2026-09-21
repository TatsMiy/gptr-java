package com.gptr.engine.search;

import com.gptr.engine.plan.SubQuery;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
import com.gptr.integration.exception.TransientApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 检索器测试：去重聚合、单子查询失败不中断、命中源汇总。
 */
class SearcherTest {

    @Test
    void deduplicatesByUrlAcrossSubQueries() {
        SearchClient client = new SearchClient() {
            @Override
            public String name() {
                return "mock-a";
            }

            @Override
            public SearchResponse search(String query) {
                return new SearchResponse(List.of(
                        new SearchResult("dup", "https://a.com/1", "s1"),
                        new SearchResult("unique-" + query, "https://b.com/" + query, "s2")), name());
            }
        };
        List<SubQuery> queries = List.of(new SubQuery("x", ""), new SubQuery("y", ""));
        Searcher.SearchOutcome outcome = new Searcher(client).searchAll(queries);

        assertEquals(3, outcome.results().size(), "b.com appears twice but must be deduplicated by URL");
        assertEquals("https://a.com/1", outcome.results().get(0).url());
        assertEquals(Set.of("mock-a"), outcome.hitSources(), "successful source must be reported");
    }

    @Test
    void failingSubQueryDoesNotAbortOthers() {
        SearchClient client = new SearchClient() {
            @Override
            public String name() {
                return "mock-b";
            }

            @Override
            public SearchResponse search(String query) {
                if (query.equals("bad")) {
                    throw new TransientApiException("mock-b", "boom");
                }
                return new SearchResponse(List.of(new SearchResult("ok", "https://ok.com", "s")), name());
            }
        };
        List<SubQuery> queries = List.of(new SubQuery("bad", ""), new SubQuery("good", ""));
        Searcher.SearchOutcome outcome = new Searcher(client).searchAll(queries);

        assertEquals(1, outcome.results().size(), "failed sub-query must be skipped, others kept");
        assertEquals("https://ok.com", outcome.results().get(0).url());
        assertEquals(Set.of("mock-b"), outcome.hitSources());
    }
}
