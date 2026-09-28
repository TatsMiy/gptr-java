package com.gptr.engine.epoc;

import com.gptr.engine.EffectiveBudgets;
import com.gptr.engine.budget.Budgets;
import com.gptr.engine.context.ContextManager;
import com.gptr.integration.client.LlmClient;
import com.gptr.integration.client.ScraperClient;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
import org.bsc.langgraph4j.CompiledGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 专项测试：结构化证据库（EvidenceBank）。
 * - extract 产 note 写 bank、learnings 渲染视图单点双写（数/序/格式一致）；
 * - quote 完整入库（>120 保真）、渲染才截 120；未授权 sourceUrl → quote/source 一并剥夺；
 * - perQueryExtract=false（旧整层路径）不产 note（bank 空）；
 * - ContextManager：同源多条保留（修复 #1）、buildEvidenceContext 组内≥1+来源多样（修复 #2）。
 */
class EvidenceBankTest {

    /** 超过渲染截断线（120）的引文：头部铺垫 + 尾部唯一标记 TAIL-9876543210
     *  （渲染截 120 必切掉尾部标记；完整保真在 note 内）。 */
    static final String LONG_QUOTE = "占位铺垫文字没有实质信息只用于把引文长度撑过渲染截断线。".repeat(4)
            + "TAIL-9876543210";

    static class MockLlm implements LlmClient {
        String learningsJson =
                "{\"learnings\":[{\"insight\":\"ins-a\",\"sourceUrl\":\"https://u1\","
                        + "\"evidenceQuote\":\"" + LONG_QUOTE + "\"},"
                        + "{\"insight\":\"ins-b\",\"sourceUrl\":\"https://fake-unauthorized\","
                        + "\"evidenceQuote\":\"孤儿引用句\"}],\"followUpQuestions\":[\"fq\"]}";

        @Override
        public String chat(String s, String u) {
            return "";
        }

        @Override
        public String chatJson(String system, String user) {
            if (system.contains("generating search queries")
                    || system.contains("deepening an ongoing")) {
                return "[{\"query\":\"q1\",\"researchGoal\":\"g1\"}]";
            }
            if (system.contains("analyzing search results")) {
                return learningsJson;
            }
            return "{}";
        }

        @Override
        public double lastCallCostUsd() {
            return 0.001;
        }

        @Override
        public String name() {
            return "mock-p2";
        }
    }

    static class MockSearch implements SearchClient {
        @Override
        public String name() {
            return "mock-search-p2";
        }

        @Override
        public SearchResponse search(String query) {
            return search(query, SearchOptions.DEFAULT);
        }

        @Override
        public SearchResponse search(String query, SearchOptions opts) {
            return new SearchResponse(List.of(new SearchResult("t", "https://u1", "s1")), name());
        }
    }

    private CompiledGraph<DeepResearchState> graph(boolean perQuery) throws Exception {
        return DeepResearchGraph.buildReal(
                new DeepResearchGraph.GraphDeps(new MockLlm(), new MockSearch(), null,
                        new DistillGate(3), null, cost -> {
                        }, null),
                SearchOptions.DEFAULT,
                new DeepResearchGraph.ResearchOptions(
                        EffectiveBudgets.of(Budgets.defaults().extraction(), false, 20000),
                        new DeepResearchGraph.PlanningOptions(0, "legacy"),
                        new DeepResearchGraph.ScrapeOptions(false,
                                DeepResearchGraph.ScrapeQuota.flat(0), false, false),
                        new DeepResearchGraph.ExtractOptions(perQuery, true),
                        new DeepResearchGraph.CurateOptions(false, 10),
                        new DeepResearchGraph.FollowUpOptions(true, 0.5, false)),
                Budgets.defaults(),
                "test-request-id");
    }

    @Test
    void extractWritesBankAndLearningsInSync() throws Exception {
        CompiledGraph<DeepResearchState> graph = graph(true);

        DeepResearchState state = graph.invoke(Map.of("query", "t", "breadth", 1, "depth", 1))
                .orElseThrow();

        assertEquals(2, state.evidenceBank().size(), "本轮 2 条 raw → bank 2 条");
        assertEquals(2, state.learnings().size(), "learnings = bank 渲染视图（同数）");
        // 解码验证字段与槽位
        EvidenceNote note0 = EvidenceNote.fromJson(state.evidenceBank().get(0));
        assertEquals(0, note0.queryIdx(), "首条槽位 0");
        assertEquals("q1", note0.queryText(), "queryText 打标");
        assertEquals(1, note0.depth(), "层号");
        assertEquals("https://u1", note0.sourceUrl(), "授权 URL 保留");
        assertTrue(note0.quote().length() > 120, "quote 完整入库（保真）: len=" + note0.quote().length());
        assertEquals(LONG_QUOTE, note0.quote(), "quote 逐字完整、无任何截断");
        // 未授权：sourceUrl 与孤儿 quote 一并剥夺
        EvidenceNote note1 = EvidenceNote.fromJson(state.evidenceBank().get(1));
        assertEquals("", note1.sourceUrl(), "未授权 URL 剥夺");
        assertEquals("", note1.quote(), "孤儿 quote 一并剥夺（无锚不可审计）");
        // 渲染视图与 note 一致
        assertEquals(note0.renderLearningText(), state.learnings().get(0), "渲染=视图");
        assertTrue(state.learnings().get(0).contains("[quote: "), "渲染带 quote");
        assertTrue(!state.learnings().get(0).contains("TAIL-9876543210"),
                "渲染截 120 切掉句尾标记（完整保真在 note 内，供 D2 复核）");
        assertEquals("ins-b", state.learnings().get(1), "未授权条渲染为纯 insight");
    }

    @Test
    void roundPathWithoutPerQueryKeepsBankEmpty() throws Exception {
        CompiledGraph<DeepResearchState> graph = graph(false);

        DeepResearchState state = graph.invoke(Map.of("query", "t", "breadth", 1, "depth", 1))
                .orElseThrow();

        assertTrue(state.evidenceBank().isEmpty(), "perQueryExtract=false 不产 note（无槽位可打标）");
        assertTrue(!state.learnings().isEmpty(), "learnings 照旧文本累积");
    }

    @Test
    void evidenceNoteJsonRoundTrip() {
        EvidenceNote note = new EvidenceNote(7, 2, 2, 1, "q-txt", "insight 甲",
                "quote 完整内容 42", "https://u.org/a/");
        EvidenceNote back = EvidenceNote.fromJson(note.toJson());
        assertEquals(note, back, "JSON 往返无损");
        // 渲染：quote ≤120 截断位
        String longText = "x".repeat(200);
        EvidenceNote longQuote = new EvidenceNote(0, 1, 1, 0, "q", "ins", longText, "https://u");
        assertTrue(longQuote.renderLearningText().length() < 200 + 60, "渲染截 120");
        // url 空 → 渲染省略 quote 与 source（孤儿不出现）
        EvidenceNote orphan = new EvidenceNote(0, 1, 1, 0, "q", "ins", "孤", "");
        assertEquals("ins", orphan.renderLearningText());
    }

    @Test
    void contextManagerKeepsSameSourceMultiples() {
        ContextManager cm = new ContextManager(10000);
        List<String> items = List.of(
                "甲 [source: https://u1]",
                "乙 [source: https://u1]", // 同 URL 第二条（旧实现会误丢）
                "丙 [source: https://u2]",
                "乙 [source: https://u1]", // 全文重复仍去
                "甲 [source: https://u1]");
        List<String> out = cm.deduplicate(items);
        assertEquals(3, out.size(), "同源多条保留；全文相同才去重");
        assertTrue(out.contains("甲 [source: https://u1]") && out.contains("乙 [source: https://u1]")
                && out.contains("丙 [source: https://u2]"));
    }

    @Test
    void buildEvidenceContextKeepsAtLeastOnePerQueryAndSpreadsSources() {
        ContextManager cm = new ContextManager(10000);
        // 构造 bank：q0 3 条（2 URL），q1 1 条，未归组 1 条
        var q0a = new EvidenceNote(0, 1, 1, 0, "q0", "q0-a", "", "https://a");
        var q0b = new EvidenceNote(1, 1, 1, 0, "q0", "q0-b", "", "https://a"); // 同源第二条
        var q0c = new EvidenceNote(2, 1, 1, 0, "q0", "q0-c", "", "https://b");
        var q1a = new EvidenceNote(3, 1, 1, 1, "q1", "q1-a", "", "https://c");
        var ung = new EvidenceNote(4, 1, 1, -1, "x", "ungrouped-x", "", "https://d");
        List<String> bank = List.of(q0a.toJson(), q0b.toJson(), q0c.toJson(),
                q1a.toJson(), ung.toJson());

        String ctx = cm.buildEvidenceContext(bank, 10000);

        // 每个子问题（含未归组）至少 1 条
        assertTrue(ctx.contains("q0-a"), "q0 首条在场");
        assertTrue(ctx.contains("q1-a"), "q1 首条在场");
        assertTrue(ctx.contains("ungrouped-x"), "未归组兜底在场");
        assertTrue(ctx.contains("q0-c"), "来源多样优先：q0 第二来源在场");
        // 同 URL ≤3 深度回填——同源第二条不再被"每 URL 只 1 条"抛弃
        assertTrue(ctx.contains("q0-b"), "同源第二条回填（预算内；URL 上限 3）");
    }

    @Test
    void evidenceContextCapsPerUrlDepthAtThree() {
        ContextManager cm = new ContextManager(10000);
        // 单 URL 5 条论据 → 只保留 ≤3（设计上限 MAX_PER_URL）
        java.util.List<com.gptr.engine.epoc.EvidenceNote> notes = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            notes.add(new EvidenceNote(i, 1, 1, 0, "q0",
                    "insight-" + i, "", "https://same"));
        }
        List<String> bank = notes.stream().map(com.gptr.engine.epoc.EvidenceNote::toJson).toList();
        String ctx = cm.buildEvidenceContext(bank, 10000);
        int kept = 0;
        for (int i = 0; i < 5; i++) {
            if (ctx.contains("insight-" + i)) {
                kept++;
            }
        }
        assertEquals(3, kept, "同 URL 论据深度回填上限为 "
                + Budgets.defaults().curate().maxPerUrl() + " 条");
    }
}
