package com.gptr.engine.epoc;

import com.gptr.engine.epoc.DeepResearchPrompts.ParsedLearnings;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.NodeOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收（一）：LangGraph4j 图机制。
 *
 * <p>验证：① 图 2 层递归跑通（条件边 + 深度预算）② learnings/追问在状态中累积
 * ③ 原版 prompt 搬运通道（properties 加载 + 占位符替换）④ JSON 解析容错。
 */
class GraphMechanicsTest {

    /** mock LLM：按 prompt 返回结构化 JSON（模拟原版契约）。 */
    private static BiFunction<String, String, String> mockLlm() {
        return (system, user) -> {
            if (system.contains("generating search queries")) {
                return "[{\"query\":\"q1\",\"researchGoal\":\"g1\"},{\"query\":\"q2\",\"researchGoal\":\"g2\"}]";
            }
            if (system.contains("analyzing search results")) {
                return "{\"learnings\":[{\"insight\":\"learning-a\",\"sourceUrl\":\"https://a.com\"},"
                        + "{\"insight\":\"learning-b\",\"sourceUrl\":\"\"}],"
                        + "\"followUpQuestions\":[\"f1\",\"f2\"]}";
            }
            return "{}";
        };
    }

    @Test
    void graphRunsTwoLayersAndAccumulatesState() throws Exception {
        CompiledGraph<DeepResearchState> graph = DeepResearchGraph.build(mockLlm()).compile();

        DeepResearchState state = graph.invoke(Map.of("query", "poC topic", "breadth", 2, "depth", 2))
                .orElseThrow();

        assertEquals(2, state.currentDepth(), "two layers must be executed (depth budget 2)");
        // 每层 extract 产出 2 learnings × 2 层 = 4
        assertEquals(4, state.learnings().size(), "learnings must accumulate across layers");
        assertEquals(4, state.followUpQuestions().size(), "follow-up questions must accumulate");

        // learnings 带来源 URL（原版契约：citation 保留）
        assertTrue(state.learnings().get(0).contains("https://a.com"),
                "learning citation must be preserved: " + state.learnings());
    }

    @Test
    void depthBudgetOneRunsSingleLayer() throws Exception {
        CompiledGraph<DeepResearchState> graph = DeepResearchGraph.build(mockLlm()).compile();
        DeepResearchState state = graph.invoke(Map.of("query", "t", "breadth", 1, "depth", 1))
                .orElseThrow();
        assertEquals(1, state.currentDepth());
        assertEquals(2, state.learnings().size());
    }

    @Test
    void promptsLoadAndSubstitute() {
        assertTrue(DeepResearchPrompts.get("generate-search-queries.system").contains("Return valid JSON only"),
                "ported system prompt must load");
        String user = DeepResearchPrompts.get("generate-search-queries.user")
                .replace("{numQueries}", "3").replace("{query}", "Q");
        assertTrue(user.contains("3 unique search queries"));
        assertTrue(user.contains("Prompt: Q"));
    }

    @Test
    void jsonParsingIsResilient() {
        // markdown 包裹
        List<String> queries = DeepResearchPrompts.parseQueryList(
                "```json\n[{\"query\":\"a\",\"researchGoal\":\"\"}]\n```", 3);
        assertEquals(List.of("a"), queries);
        // 非法 → 退化
        assertEquals(List.of("fallback-query"),
                DeepResearchPrompts.parseQueryList("not json at all", 3));
        // learnings 解析
        ParsedLearnings parsed = DeepResearchPrompts.parseLearnings(
                "{\"learnings\":[{\"insight\":\"x\",\"sourceUrl\":\"https://x\"}],\"followUpQuestions\":[\"f\"]}");
        assertEquals(1, parsed.learnings().size());
        assertEquals(1, parsed.followUpQuestions().size());
    }

    @Test
    void nodeOutputsStreamingAvailable() throws Exception {
        CompiledGraph<DeepResearchState> graph = DeepResearchGraph.build(mockLlm()).compile();
        List<NodeOutput<DeepResearchState>> steps = new java.util.ArrayList<>();
        graph.stream(Map.of("query", "t", "breadth", 1, "depth", 1))
                .forEachAsync(steps::add).join();
        assertTrue(steps.size() >= 4, "START + 3 nodes should emit outputs, got " + steps.size());
    }
}
