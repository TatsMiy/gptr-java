package com.gptr.engine.epoc;

import com.gptr.engine.EffectiveBudgets;
import com.gptr.engine.budget.Budgets;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 层间计划反思（plan_reflect）与 quote 保真专项测试：
 * - plan_reflect 层间节点：中央研究状态写回并驱动下轮 follow-up（坏输出回退 "(none)"）；
 * - evidenceQuote：extract 解析编码 [quote:]；未授权时 quote+source 一并剥除。
 */
class GraphPlanReflectTest {

    /** 可控 mock LLM：plan-reflect 走独立分支（其余同 GraphClarifyExtractCurateTest 的 mock）。 */
    static class MockLlm implements LlmClient {
        final List<String> systems = new CopyOnWriteArrayList<>();
        final List<String> users = new CopyOnWriteArrayList<>();
        String planReflectJson = "{\"researchState\":\"已覆盖：技术原理与主流路线；缺口：成本与产业落地数据\"}";
        String learningsJson = "{\"learnings\":[{\"insight\":\"insight-x\",\"sourceUrl\":\"\","
                + "\"evidenceQuote\":\"原文关键句 42\"}],\"followUpQuestions\":[\"fq\"]}";

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            return "";
        }

        @Override
        public String chatJson(String system, String user) {
            systems.add(system);
            users.add(user);
            if (system.contains("generating search queries")) {
                return "[{\"query\":\"q1\",\"researchGoal\":\"g1\"}]";
            }
            if (system.contains("reviewing an ongoing deep research investigation")) {
                return planReflectJson;
            }
            if (system.contains("deepening an ongoing")) {
                return "[{\"query\":\"fq1\",\"researchGoal\":\"gap1\"}]";
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
            return "mock-llm-j6";
        }
    }

    static class MockSearch implements SearchClient {
        @Override
        public String name() {
            return "mock-search-j6";
        }

        @Override
        public SearchResponse search(String query) {
            return search(query, SearchOptions.DEFAULT);
        }

        @Override
        public SearchResponse search(String query, SearchOptions opts) {
            return new SearchResponse(List.of(new SearchResult("t", "https://u1", "s")), name());
        }
    }

    private CompiledGraph<DeepResearchState> graph(MockLlm llm, boolean planReflect)
            throws Exception {
        return DeepResearchGraph.buildReal(
                new DeepResearchGraph.GraphDeps(llm, new MockSearch(), null, new DistillGate(3),
                        null, cost -> {
                        }, null),
                SearchOptions.DEFAULT,
                new DeepResearchGraph.ResearchOptions(EffectiveBudgets.of(Budgets.defaults().extraction(), false, 20000),
                        new DeepResearchGraph.PlanningOptions(0, "legacy"),
                        new DeepResearchGraph.ScrapeOptions(false,
                                DeepResearchGraph.ScrapeQuota.flat(0), false, false),
                        new DeepResearchGraph.ExtractOptions(true, true),
                        new DeepResearchGraph.CurateOptions(false, 10),
                        new DeepResearchGraph.FollowUpOptions(true, 0.5, planReflect)),
                Budgets.defaults());
    }

    @Test
    void planReflectWritesCentralStateAndDrivesNextRound() throws Exception {
        MockLlm llm = new MockLlm();
        CompiledGraph<DeepResearchState> graph = graph(llm, true);

        DeepResearchState state = graph.invoke(Map.of("query", "t", "breadth", 1, "depth", 2))
                .orElseThrow();

        assertEquals("已覆盖：技术原理与主流路线；缺口：成本与产业落地数据",
                state.researchState(), "中央研究状态应写回状态");
        // 下轮 follow-up 的 user 应含中央状态（防分支 silo / 主题漂移）
        String followupUser = llm.users.stream()
                .filter(u -> u.contains("Central research state")).findFirst().orElse("");
        assertTrue(followupUser.contains("已覆盖：技术原理"), "follow-up 应读取 researchState: " + followupUser);
        // extract 后每层（除最后一层）一次 plan_reflect
        long reflectCalls = llm.systems.stream()
                .filter(s -> s.contains("reviewing an ongoing deep research investigation")).count();
        assertEquals(1, reflectCalls, "depth=2 → 应只反思一次（末层守卫 END）");
    }

    @Test
    void planReflectBadOutputFallsBackToNone() throws Exception {
        MockLlm llm = new MockLlm();
        llm.planReflectJson = "not json"; // 坏输出 → "(none)" 继续不失败
        CompiledGraph<DeepResearchState> graph = graph(llm, true);

        DeepResearchState state = graph.invoke(Map.of("query", "t", "breadth", 1, "depth", 2))
                .orElseThrow();

        assertEquals("(none)", state.researchState(), "坏输出回退 (none)");
        assertTrue(state.currentDepth() >= 2, "流程不应被 plan_reflect 失败中断");
    }

    @Test
    void planReflectDisabledSkipsNode() throws Exception {
        MockLlm llm = new MockLlm();
        CompiledGraph<DeepResearchState> graph = graph(llm, false);

        graph.invoke(Map.of("query", "t", "breadth", 1, "depth", 2)).orElseThrow();

        long reflectCalls = llm.systems.stream()
                .filter(s -> s.contains("reviewing an ongoing deep research investigation")).count();
        assertEquals(0, reflectCalls, "planReflect=false 不建节点");
    }

    @Test
    void evidenceQuoteParsedIntoLearningText() {
        var parsed = DeepResearchPrompts.parseLearnings(
                "{\"learnings\":[{\"insight\":\"量子霸权已实现\",\"sourceUrl\":\"https://u1\","
                        + "\"evidenceQuote\":\"原文关键句 42\"}],\"followUpQuestions\":[]}");
        assertEquals(1, parsed.learnings().size());
        assertEquals("量子霸权已实现 [quote: 原文关键句 42] [source: https://u1]",
                parsed.learnings().get(0), "quote 编码在 source 前（剥除/回显正则兼容）");
    }

    @Test
    void unauthorizedSourceStripsQuoteTogether() {
        List<String> authorized = List.of("https://real-source.com/doc");
        // 授权：quote+source 原样保留
        String kept = EvidenceText.stripUnauthorizedSource(
                "论断 [quote: 原句] [source: https://real-source.com/doc]", authorized);
        assertEquals("论断 [quote: 原句] [source: https://real-source.com/doc]", kept);
        // 未授权：source 与孤儿 quote 一并剥除（quote 无 URL 锚不可审计，不留残渣）
        String stripped = EvidenceText.stripUnauthorizedSource(
                "论断 [quote: 原句] [source: https://fake.com/x]", authorized);
        assertEquals("论断", stripped, "未授权时 quote+source 一起剥净");
        // 无 quote 的旧格式不受影响
        String legacy = EvidenceText.stripUnauthorizedSource(
                "论断 [source: https://fake.com/x]", authorized);
        assertEquals("论断", legacy);
    }
}
