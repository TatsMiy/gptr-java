package com.gptr.engine.epoc;


import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.action.AsyncNodeAction;

/**
 * 图节点集合（生成查询 / 搜索 / 提炼 learnings）。
 *
 * <p>全部基于 mock 客户端验证框架机制；
 * prompt 用原版搬运资产（deep-research.properties），验证搬运通道。
 */
public final class EpocNodes {

    private EpocNodes() {
    }

    /** 生成搜索查询（原版 generate_search_queries prompt）。 */
    public static AsyncNodeAction<DeepResearchState> generateQueries(
            BiFunction<String, String, String> llmChatJson) {
        return state -> CompletableFuture.supplyAsync(
                () -> runGenerateQueries(state, llmChatJson));
    }

    /** {@link #generateQueries} 的实现体（原 lambda 体逐字搬入，缩进 −2 层）。 */
    private static Map<String, Object> runGenerateQueries(DeepResearchState state,
                                                          BiFunction<String, String, String> llmChatJson) {
        String system = DeepResearchPrompts.get("generate-search-queries.system");
        String user = DeepResearchPrompts.get("generate-search-queries.user")
                .replace("{numQueries}", String.valueOf(state.breadth()))
                .replace("{query}", state.query());
        String raw = llmChatJson.apply(system, user);
        List<String> queries = DeepResearchPrompts.parseQueryList(raw, state.breadth());
        Map<String, Object> updates = new HashMap<>();
        updates.put(DeepResearchState.K_QUERIES, queries);
        return updates;
    }

    /** 模拟搜索（返回假结果文本；真实 = Python /search）。 */
    public static AsyncNodeAction<DeepResearchState> mockSearch() {
        return state -> CompletableFuture.supplyAsync(
                () -> runMockSearch(state));
    }

    /** {@link #mockSearch} 的实现体（原 lambda 体逐字搬入，缩进 −2 层）。 */
    private static Map<String, Object> runMockSearch(DeepResearchState state) {
        StringBuilder sb = new StringBuilder();
        for (String q : state.queries()) {
            sb.append("Source: https://example.com/").append(q.hashCode() & 0xffff)
                    .append("\nTitle: mock result for ").append(q)
                    .append("\nContent: mock content about ").append(q).append("\n\n");
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(DeepResearchState.K_SEARCH_RESULTS, sb.toString());
        return updates;
    }

    /** 提炼 learnings + 追问（原版 process_research_results prompt），累积进状态。 */
    public static AsyncNodeAction<DeepResearchState> extractLearnings(
            BiFunction<String, String, String> llmChatJson) {
        return state -> CompletableFuture.supplyAsync(
                () -> runExtractLearnings(state, llmChatJson));
    }

    /** {@link #extractLearnings} 的实现体（原 lambda 体逐字搬入，缩进 −2 层）。 */
    private static Map<String, Object> runExtractLearnings(DeepResearchState state,
                                                           BiFunction<String, String, String> llmChatJson) {
        String system = DeepResearchPrompts.get("process-results.system");
        String user = DeepResearchPrompts.get("process-results.user")
                .replace("{query}", state.query())
                .replace("{context}", state.searchResults());
        String raw = llmChatJson.apply(system, user);
        var parsed = DeepResearchPrompts.parseLearnings(raw);

        List<String> learnings = new ArrayList<>(state.learnings());
        learnings.addAll(parsed.learnings());
        List<String> followUps = new ArrayList<>(state.followUpQuestions());
        followUps.addAll(parsed.followUpQuestions());

        Map<String, Object> updates = new HashMap<>();
        updates.put(DeepResearchState.K_LEARNINGS, learnings);
        updates.put(DeepResearchState.K_FOLLOW_UP_QUESTIONS, followUps);
        updates.put(DeepResearchState.K_CURRENT_DEPTH, state.currentDepth() + 1);
        return updates;
    }
}
