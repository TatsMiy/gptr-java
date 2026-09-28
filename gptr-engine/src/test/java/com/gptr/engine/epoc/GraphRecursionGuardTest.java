package com.gptr.engine.epoc;

import com.gptr.engine.EffectiveBudgets;
import com.gptr.engine.budget.Budgets;
import com.gptr.integration.client.LlmClient;
import com.gptr.integration.client.ScrapeBatch;
import com.gptr.integration.client.ScraperClient;
import com.gptr.integration.client.ScrapedContent;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
import org.bsc.langgraph4j.CompiledGraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 深研升级专项测试：守卫（空 learnings 提前 END）、breadth 衰减、
 * visited 防重抓、followUpDriven=false 兼容旧线性加深。
 */
class GraphRecursionGuardTest {

    /** 可控 mock LLM：记录 (system,user)；可配空 learnings。 */
    static class MockLlm implements LlmClient {
        final List<String> systems = new CopyOnWriteArrayList<>();
        final List<String> users = new CopyOnWriteArrayList<>();
        boolean emptyLearnings = false;

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            return "";
        }

        @Override
        public String chatJson(String system, String user) {
            systems.add(system);
            users.add(user);
            if (system.contains("generating search queries")) {
                return "[{\"query\":\"q1\",\"researchGoal\":\"g1\"},{\"query\":\"q2\",\"researchGoal\":\"g2\"},"
                        + "{\"query\":\"q3\",\"researchGoal\":\"g3\"},{\"query\":\"q4\",\"researchGoal\":\"g4\"}]";
            }
            if (system.contains("analyzing search results")) {
                if (emptyLearnings) {
                    return "{\"learnings\":[],\"followUpQuestions\":[]}";
                }
                return "{\"learnings\":[{\"insight\":\"l1\",\"sourceUrl\":\"https://u1\"}],"
                        + "\"followUpQuestions\":[\"f1\"]}";
            }
            if (system.contains("deepening an ongoing")) {
                return "[{\"query\":\"fq1\",\"researchGoal\":\"gap1\"},{\"query\":\"fq2\",\"researchGoal\":\"gap2\"}]";
            }
            return "{}";
        }

        @Override
        public double lastCallCostUsd() {
            return 0.001;
        }

        @Override
        public String name() {
            return "mock-llm";
        }
    }

    /** mock 搜索：固定返回同一 URL（visited 测试用）；记录调用次数。 */
    static class MockSearch implements SearchClient {
        final List<String> queries = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "mock-search";
        }

        @Override
        public SearchResponse search(String query) {
            return search(query, SearchOptions.DEFAULT);
        }

        @Override
        public SearchResponse search(String query, SearchOptions opts) {
            queries.add(query);
            return new SearchResponse(List.of(new SearchResult("mock title", "https://mock-u1", "snippet")), name());
        }
    }

    /** mock 抓取：记录调用次数。 */
    static class MockScraper implements ScraperClient {
        int calls = 0;

        @Override
        public String name() {
            return "mock-scraper";
        }

        @Override
        public ScrapeBatch scrapeDetailed(List<String> urls, int maxCharsPerUrl, String requestId) {
            calls++;
            return ScrapeBatch.contentsOnly(
                    List.of(new ScrapedContent(urls.get(0), "t", "full content of page")));
        }
    }

    private CompiledGraph<DeepResearchState> realGraph(MockLlm llm, MockSearch search,
                                                       MockScraper scraper, boolean fetchFullPage,
                                                       int maxScrapeUrls, boolean followUpDriven,
                                                       double decay) throws Exception {
        // 本类 helper：clarify=0（守卫语义测试不引入澄清前奏）、per-query 提炼开（生产默认）
        return DeepResearchGraph.buildReal(
                new DeepResearchGraph.GraphDeps(llm, search, scraper, new DistillGate(3), null,
                        cost -> {
                        }, null),
                SearchOptions.DEFAULT,
                new DeepResearchGraph.ResearchOptions(EffectiveBudgets.of(Budgets.defaults().extraction(), false, 20000),
                        new DeepResearchGraph.PlanningOptions(0, "legacy"),
                        new DeepResearchGraph.ScrapeOptions(fetchFullPage,
                                DeepResearchGraph.ScrapeQuota.flat(maxScrapeUrls), false, false),
                        new DeepResearchGraph.ExtractOptions(true, true),
                        new DeepResearchGraph.CurateOptions(false, 10),
                        new DeepResearchGraph.FollowUpOptions(followUpDriven, decay, false)),
                Budgets.defaults(),
                "test-request-id");
    }

    @Test
    void guardStopsEarlyWhenRoundHasNoLearnings() throws Exception {
        MockLlm llm = new MockLlm();
        llm.emptyLearnings = true;
        GraphRecursionGuardTest.MockSearch search = new GraphRecursionGuardTest.MockSearch();
        CompiledGraph<DeepResearchState> graph = realGraph(llm, search, null, false, 0, true, 0.5);

        DeepResearchState state = graph.invoke(Map.of("query", "t", "breadth", 2, "depth", 3))
                .orElseThrow();

        assertEquals(1, state.currentDepth(), "守卫：首层无 learnings 应立即 END，不空跑 depth 3");
        assertTrue(state.learnings().isEmpty());
        // per-query 提炼 = breadth 次 extract 调用（本轮 2 个 query 各 1 次）
        long extractCalls = llm.systems.stream()
                .filter(s -> s.contains("analyzing search results")).count();
        assertEquals(2, extractCalls, "per-query 提炼应按 query 数各调一次");
    }

    @Test
    void breadthDecaysNextLayerQueries() throws Exception {
        MockLlm llm = new MockLlm();
        GraphRecursionGuardTest.MockSearch search = new GraphRecursionGuardTest.MockSearch();
        CompiledGraph<DeepResearchState> graph = realGraph(llm, search, null, false, 0, true, 0.5);

        graph.invoke(Map.of("query", "t", "breadth", 4, "depth", 2)).orElseThrow();

        // 第二层走 follow_up_queries：numQueries = max(2, ceil(4×0.5)) = 2
        int followupIdx = -1;
        for (int i = 0; i < llm.systems.size(); i++) {
            if (llm.systems.get(i).contains("deepening an ongoing")) {
                followupIdx = i;
            }
        }
        assertTrue(followupIdx >= 0, "follow_up_queries 节点应被调用");
        assertTrue(llm.users.get(followupIdx).contains("Generate 2 follow-up"),
                "衰减后应为 2 个查询: " + llm.users.get(followupIdx));
        // learnings 累积 = 层1 4 query×1 条 + 层2 2 query×1 条
        assertEquals(6, graph.invoke(Map.of("query", "t", "breadth", 4, "depth", 2))
                .orElseThrow().learnings().size());
    }

    @Test
    void visitedUrlsPreventRescrapeAcrossLayers() throws Exception {
        MockLlm llm = new MockLlm();
        GraphRecursionGuardTest.MockSearch search = new GraphRecursionGuardTest.MockSearch();
        GraphRecursionGuardTest.MockScraper scraper = new GraphRecursionGuardTest.MockScraper();
        CompiledGraph<DeepResearchState> graph =
                realGraph(llm, search, scraper, true, 5, true, 0.5);

        DeepResearchState state = graph.invoke(Map.of("query", "t", "breadth", 1, "depth", 2))
                .orElseThrow();

        assertEquals(2, state.currentDepth());
        assertEquals(1, scraper.calls, "同一 URL 跨层只应抓一次（visited 防重）");
        assertTrue(state.visitedUrls().contains("https://mock-u1"));
    }

    @Test
    void followUpDrivenFalseKeepsOriginalQueryPerLayer() throws Exception {
        MockLlm llm = new MockLlm();
        GraphRecursionGuardTest.MockSearch search = new GraphRecursionGuardTest.MockSearch();
        CompiledGraph<DeepResearchState> graph = realGraph(llm, search, null, false, 0, false, 0.5);

        graph.invoke(Map.of("query", "original-topic", "breadth", 1, "depth", 2)).orElseThrow();

        // 旧路径：两层都回 generate_queries（原始 query），且不出现 follow_up 节点
        assertTrue(llm.systems.stream().noneMatch(s -> s.contains("deepening an ongoing")),
                "followUpDriven=false 不应调用 follow_up_queries");
        long generateCalls = llm.systems.stream()
                .filter(s -> s.contains("generating search queries")).count();
        assertEquals(2, generateCalls, "两层都应走 generate_queries");
    }

    // LLM 自报 sourceUrl 不在真实检索集 → 剥除引用标记（防"自报即授权"自证）
    @Test
    void unauthorizedSourceUrlStrippedFromLearning() {
        List<String> authorized = List.of("https://real-source.com/doc",
                "https://real-source.com/doc/"); // canonical 变体也应匹配
        String kept = EvidenceText.stripUnauthorizedSource(
                "量子隧穿已在电路中观测 [source: https://real-source.com/doc]", authorized);
        assertEquals("量子隧穿已在电路中观测 [source: https://real-source.com/doc]", kept);

        String stripped = EvidenceText.stripUnauthorizedSource(
                "编造的来源主张 [source: https://fake-source.com/doc]", authorized);
        assertEquals("编造的来源主张", stripped, "不在真实检索集的 sourceUrl 必须被剥除");

        String noSource = EvidenceText.stripUnauthorizedSource("无来源锚点的论断", authorized);
        assertEquals("无来源锚点的论断", noSource);
    }
}
