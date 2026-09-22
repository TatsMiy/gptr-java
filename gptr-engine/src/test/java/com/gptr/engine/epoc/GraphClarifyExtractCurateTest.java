package com.gptr.engine.epoc;

import com.gptr.engine.EffectiveBudgets;
import com.gptr.engine.budget.Budgets;
import com.gptr.integration.client.LlmClient;
import com.gptr.integration.client.ScraperClient;
import com.gptr.integration.client.ScrapedContent;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
import org.bsc.langgraph4j.CompiledGraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I 批专项测试（对标 gpt-researcher skills/deep_research.py）：
 * 澄清前奏（research_plan 应用/降级）、per-query 独立提炼（分组/空分支跳过）、
 * 来源质量闸（curate 保序精选/坏输出回退/全删回退）。
 */
class GraphClarifyExtractCurateTest {

    /** 可控 mock LLM：按 system 关键词分流 research-plan / curate / extract。 */
    static class MockLlm implements LlmClient {
        final List<String> systems = new CopyOnWriteArrayList<>();
        final List<String> users = new CopyOnWriteArrayList<>();
        String queriesJson = "[{\"query\":\"q1\",\"researchGoal\":\"g1\"},"
                + "{\"query\":\"q2\",\"researchGoal\":\"g2\"}]";
        String planJson = "{\"questions\":[\"plan-q1\",\"plan-q2\"]}";
        String curateJson = "{\"kept\":[2,1]}";
        /** 固定 extract 输出（1 条 learning + 1 followup）。 */
        String learningsJson = "{\"learnings\":[{\"insight\":\"insight-for-{q}\",\"sourceUrl\":\"\"}],"
                + "\"followUpQuestions\":[\"fq\"]}";

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            return "";
        }

        @Override
        public String chatJson(String system, String user) {
            systems.add(system);
            users.add(user);
            if (system.contains("generating search queries")) {
                return queriesJson;
            }
            if (system.contains("explore different aspects")) {
                return planJson;
            }
            if (system.contains("source curator")) {
                return curateJson;
            }
            if (system.contains("deepening an ongoing")) {
                return queriesJson;
            }
            if (system.contains("analyzing search results")) {
                String marker = user.contains("'q1'") ? "q1" : user.contains("'q2'") ? "q2" : "x";
                return learningsJson.replace("{q}", marker);
            }
            return "{}";
        }

        @Override
        public double lastCallCostUsd() {
            return 0.001;
        }

        @Override
        public String name() {
            return "mock-llm-i";
        }
    }

    /** 可控 mock 搜索：per-query 结果表（缺省 = 空结果）；记录调用。 */
    static class MockSearch implements SearchClient {
        final Map<String, List<SearchResult>> perQuery = new LinkedHashMap<>();
        final List<String> queries = new CopyOnWriteArrayList<>();

        MockSearch with(String query, SearchResult... results) {
            perQuery.put(query, List.of(results));
            return this;
        }

        @Override
        public String name() {
            return "mock-search-i";
        }

        @Override
        public SearchResponse search(String query) {
            return search(query, SearchOptions.DEFAULT);
        }

        @Override
        public SearchResponse search(String query, SearchOptions opts) {
            queries.add(query);
            return new SearchResponse(perQuery.getOrDefault(query, List.of()), name());
        }
    }

    static class MockScraper implements ScraperClient {
        @Override
        public String name() {
            return "mock-scraper-i";
        }

        @Override
        public List<ScrapedContent> scrape(List<String> urls) {
            List<ScrapedContent> out = new ArrayList<>();
            for (String u : urls) {
                out.add(new ScrapedContent(u, "t", "full body of " + u));
            }
            return out;
        }
    }

    private CompiledGraph<DeepResearchState> graph(MockLlm llm, MockSearch search,
                                                   MockScraper scraper, boolean fetch,
                                                   int clarify, boolean perQuery,
                                                   boolean curate) throws Exception {
        return DeepResearchGraph.buildReal(
                new DeepResearchGraph.GraphDeps(llm, search, scraper, new DistillGate(3), null, cost -> {
                }, null),
                SearchOptions.DEFAULT,
                new DeepResearchGraph.ResearchOptions(EffectiveBudgets.of(Budgets.defaults().extraction(), false, 20000),
                        new DeepResearchGraph.PlanningOptions(clarify, "legacy"),
                        new DeepResearchGraph.ScrapeOptions(fetch,
                                DeepResearchGraph.ScrapeQuota.flat(5), false, false),
                        new DeepResearchGraph.ExtractOptions(perQuery, true),
                        new DeepResearchGraph.CurateOptions(curate, 10),
                        new DeepResearchGraph.FollowUpOptions(true, 0.5, false)),
                Budgets.defaults());
    }

    private static long countSystem(List<String> systems, String marker) {
        return systems.stream().filter(s -> s.contains(marker)).count();
    }

    // ------------------------------------------------------------------
    // 澄清追问前奏
    // ------------------------------------------------------------------

    @Test
    void clarifyPlanCalibratesQueryWhenQuestionsReturned() throws Exception {
        MockLlm llm = new MockLlm();
        MockSearch search = new MockSearch().with("t",
                new SearchResult("t1", "https://seed-1", "seed snippet"));
        CompiledGraph<DeepResearchState> graph = graph(llm, search, null, false, 3, true, false);

        DeepResearchState state = graph.invoke(Map.of("query", "t", "breadth", 1, "depth", 1))
                .orElseThrow();

        assertEquals(true, state.clarifyApplied(), "澄清应已应用");
        // research_plan 初搜以原 query 执行
        assertTrue(search.queries.contains("t"), "澄清前奏应先初搜原 query");
        // generate 的 query 应为校准后文本（Initial Query + Q/A 自动回答）
        String genUser = llm.users.stream()
                .filter(u -> u.startsWith("Given the following prompt")).findFirst().orElse("");
        assertTrue(genUser.contains("Initial Query: t"), "generate 应使用校准后 query: " + genUser);
        assertTrue(genUser.contains("Q: plan-q1"), "校准文本应含澄清问题: " + genUser);
        assertTrue(genUser.contains("Automatically proceeding with research"),
                "自动回答占位（对标 py run():589）: " + genUser);
    }

    @Test
    void clarifyPlanSkippedWhenNoQuestionsParsed() throws Exception {
        MockLlm llm = new MockLlm();
        llm.planJson = "{\"questions\":[]}"; // LLM 无问题 → 降级
        MockSearch search = new MockSearch();
        CompiledGraph<DeepResearchState> graph = graph(llm, search, null, false, 3, true, false);

        DeepResearchState state = graph.invoke(Map.of("query", "t", "breadth", 1, "depth", 1))
                .orElseThrow();

        assertEquals(false, state.value(DeepResearchState.K_CLARIFY_APPLIED, true), "无问题时应跳过澄清");
        String genUser = llm.users.stream()
                .filter(u -> u.startsWith("Given the following prompt")).findFirst().orElse("");
        assertFalse(genUser.contains("Initial Query:"), "降级时 query 应保持原样");
        assertTrue(genUser.contains("Prompt: t"), "原 query 应继续: " + genUser);
    }

    @Test
    void clarifyDisabledByConfigSkipsInitialSearch() throws Exception {
        MockLlm llm = new MockLlm();
        MockSearch search = new MockSearch().with("t",
                new SearchResult("t1", "https://seed-1", "seed snippet"));
        CompiledGraph<DeepResearchState> graph = graph(llm, search, null, false, 0, true, false);

        graph.invoke(Map.of("query", "t", "breadth", 1, "depth", 1)).orElseThrow();

        assertEquals(0, countSystem(llm.systems, "explore different aspects"),
                "clarifyQuestions=0 不调 research-plan");
        assertFalse(search.queries.contains("t"),
                "不澄清则不应有以原 query 发起的初搜（正式研究搜索 query 是子查询）");
    }

    // ------------------------------------------------------------------
    // per-query 独立提炼
    // ------------------------------------------------------------------

    @Test
    void perQueryExtractKeepsBranchesIndependent() throws Exception {
        MockLlm llm = new MockLlm();
        MockSearch search = new MockSearch()
                .with("q1", new SearchResult("r1", "https://u1", "s1"))
                .with("q2", new SearchResult("r2", "https://u2", "s2"));
        CompiledGraph<DeepResearchState> graph = graph(llm, search, null, false, 0, true, false);

        graph.invoke(Map.of("query", "t", "breadth", 2, "depth", 1)).orElseThrow();

        assertEquals(2, countSystem(llm.systems, "analyzing search results"),
                "breadth=2 → 两次独立 extract 调用");
        List<String> extractUsers = llm.users.stream()
                .filter(u -> u.startsWith("Given the following research results"))
                .toList();
        assertEquals(2, extractUsers.size());
        // 并行调用无顺序保证：分别断言各分支的 user（只含自己的 query 与证据）
        String branch1 = extractUsers.stream().filter(u -> u.contains("https://u1")).findFirst().orElse("");
        String branch2 = extractUsers.stream().filter(u -> u.contains("https://u2")).findFirst().orElse("");
        assertTrue(branch1.contains("'q1'") && !branch1.contains("https://u2"),
                "分支 1 只含自己的 query 与证据: " + branch1);
        assertTrue(branch2.contains("'q2'") && !branch2.contains("https://u1"),
                "分支 2 只含自己的 query 与证据: " + branch2);
    }

    @Test
    void emptyBranchSkippedWithoutLlmCall() throws Exception {
        MockLlm llm = new MockLlm();
        MockSearch search = new MockSearch()
                .with("q1", new SearchResult("r1", "https://u1", "s1"));
        // q2 无结果 → 该分支无证据，不调 LLM（防空上下文编造），其余分支不受影响
        CompiledGraph<DeepResearchState> graph = graph(llm, search, null, false, 0, true, false);

        DeepResearchState state = graph.invoke(Map.of("query", "t", "breadth", 2, "depth", 1))
                .orElseThrow();

        assertEquals(1, countSystem(llm.systems, "analyzing search results"),
                "空分支不应调 extract LLM");
        assertTrue(state.learnings().size() >= 1, "非空分支 learnings 保留");
    }

    @Test
    void scrapeFullContentAppendedToReferencingBranchOnly() throws Exception {
        MockLlm llm = new MockLlm();
        MockSearch search = new MockSearch()
                .with("q1", new SearchResult("r1", "https://u1", "s1"))
                .with("q2", new SearchResult("r2", "https://u2", "s2"));
        MockScraper scraper = new MockScraper();
        CompiledGraph<DeepResearchState> graph = graph(llm, search, scraper, true, 0, true, false);

        graph.invoke(Map.of("query", "t", "breadth", 2, "depth", 1)).orElseThrow();

        List<String> extractUsers = llm.users.stream()
                .filter(u -> u.startsWith("Given the following research results"))
                .toList();
        assertEquals(2, extractUsers.size());
        String branch1 = extractUsers.stream().filter(u -> u.contains("https://u1")).findFirst().orElse("");
        String branch2 = extractUsers.stream().filter(u -> u.contains("https://u2")).findFirst().orElse("");
        assertTrue(branch1.contains("full body of https://u1") && !branch1.contains("full body of https://u2"),
                "全文应归位到引用它的分支 1，且不串组: " + branch1);
        assertTrue(branch2.contains("full body of https://u2") && !branch2.contains("full body of https://u1"),
                "分支 2 应收到 u2 全文且不串组: " + branch2);
    }

    // ------------------------------------------------------------------
    // 来源质量闸（curate）
    // ------------------------------------------------------------------

    @Test
    void curateReordersAndFiltersPerQueryItems() throws Exception {
        MockLlm llm = new MockLlm();
        llm.curateJson = "{\"kept\":[2,1]}"; // 保留两条但倒序（优先级 2 > 1）
        MockSearch search = new MockSearch()
                .with("q1", new SearchResult("r1", "https://u1", "s1"),
                        new SearchResult("r2", "https://u2", "s2"));
        CompiledGraph<DeepResearchState> graph = graph(llm, search, null, false, 0, true, true);

        graph.invoke(Map.of("query", "t", "breadth", 1, "depth", 1)).orElseThrow();

        List<String> extractUsers = llm.users.stream()
                .filter(u -> u.startsWith("Given the following research results"))
                .toList();
        assertEquals(1, extractUsers.size());
        int pos1 = extractUsers.get(0).indexOf("https://u2");
        int pos2 = extractUsers.get(0).indexOf("https://u1");
        assertTrue(pos1 >= 0 && pos2 >= 0 && pos1 < pos2,
                "curate 后按 kept 顺序（u2 在前）: " + extractUsers.get(0));
    }

    @Test
    void curateBadOutputFallsBackToOriginal() throws Exception {
        MockLlm llm = new MockLlm();
        llm.curateJson = "not json at all"; // 坏输出 → 回退原文（防误杀）
        MockSearch search = new MockSearch()
                .with("q1", new SearchResult("r1", "https://u1", "s1"),
                        new SearchResult("r2", "https://u2", "s2"));
        CompiledGraph<DeepResearchState> graph = graph(llm, search, null, false, 0, true, true);

        graph.invoke(Map.of("query", "t", "breadth", 1, "depth", 1)).orElseThrow();

        List<String> extractUsers = llm.users.stream()
                .filter(u -> u.startsWith("Given the following research results"))
                .toList();
        assertTrue(extractUsers.get(0).contains("https://u1") && extractUsers.get(0).contains("https://u2"),
                "坏输出回退：两条原文都应在");
        int pos1 = extractUsers.get(0).indexOf("https://u1");
        int pos2 = extractUsers.get(0).indexOf("https://u2");
        assertTrue(pos1 < pos2, "回退保原文顺序");
    }

    @Test
    void curateEmptyKeptFallsBackToOriginal() throws Exception {
        MockLlm llm = new MockLlm();
        llm.curateJson = "{\"kept\":[]}"; // LLM 全删 → 回退原文（宁可不过滤不可删光）
        MockSearch search = new MockSearch()
                .with("q1", new SearchResult("r1", "https://u1", "s1"));
        CompiledGraph<DeepResearchState> graph = graph(llm, search, null, false, 0, true, true);

        graph.invoke(Map.of("query", "t", "breadth", 1, "depth", 1)).orElseThrow();

        List<String> extractUsers = llm.users.stream()
                .filter(u -> u.startsWith("Given the following research results"))
                .toList();
        assertTrue(extractUsers.get(0).contains("https://u1"), "全删回退：原文仍应在 extract 上下文");
    }
}
