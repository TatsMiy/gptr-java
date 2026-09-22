package com.gptr.engine.epoc;


import com.gptr.engine.budget.RetrievalBudget;
import com.gptr.engine.context.ContextManager;
import com.gptr.integration.client.LlmClient;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.function.DoubleConsumer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ResearchPlanNode —— 由 DeepResearchGraph 外迁的节点（方法体逐字未改）。 */
final class ResearchPlanNode {

    /** 澄清前奏要把「当前时间」写进 prompt（时间敏感的问题需要）。 */
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ResearchPlanNode() {
    }

    /** 澄清前奏（对标 deep_research.py:292-344 generate_research_plan +
     *  run():588-601 自动回答拼接）。初搜仅作方向校准，不写入授权来源 collectedUrls。
     *  初搜失败/LLM 坏输出 → 保留原 query 继续（降级不失败）。 */
    static Map<String, Object> runResearchPlan(DeepResearchState state, LlmClient llm,
                                               SearchClient search, SearchOptions searchOptions,
                                               int numQuestions, DoubleConsumer costCallback,
                                               RetrievalBudget retrieval) {
        Map<String, Object> updates = new HashMap<>();
        String original = state.query();
        // 澄清初搜也携带命中源（累计进 hitSources，与正式检索同口径）
        SearchResponse planResp =
                SearchNode.safeSearch(search, original, searchOptions);
        List<SearchResult> results = planResp.results();
        if (planResp.sourceUsed() != null && !planResp.sourceUsed().isBlank()) {
            Set<String> hits = new LinkedHashSet<>(
                    state.hitSources());
            hits.add(planResp.sourceUsed());
            updates.put(DeepResearchState.K_HIT_SOURCES, new ArrayList<>(hits));
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (SearchResult r : results) {
            if (shown >= retrieval.clarifyMaxShownResults()) {
                break;
            }
            String snippet = ContextManager.truncateEach(
                    r.snippet(), retrieval.clarifySnippetMaxChars());
            sb.append("Title: ").append(r.title()).append("\nURL: ").append(r.url())
                    .append("\nSnippet: ").append(snippet).append("\n\n");
            shown++;
        }
        if (sb.length() > retrieval.clarifyResultsMaxChars()) {
            sb.setLength(retrieval.clarifyResultsMaxChars());
        }
        String system = DeepResearchPrompts.get("research-plan.system");
        String user = DeepResearchPrompts.get("research-plan.user")
                .replace("{query}", original)
                .replace("{currentTime}", LocalDateTime.now().format(TS_FMT))
                .replace("{searchResults}", sb.isEmpty() ? "(no results)" : sb.toString())
                .replace("{numQuestions}", String.valueOf(numQuestions));
        try {
            String raw = llm.chatJson(system, user);
            costCallback.accept(llm.lastCallCostUsd());
            List<String> questions = DeepResearchPrompts.parseQuestionList(raw, numQuestions);
            if (questions.isEmpty()) {
                updates.put(DeepResearchState.K_CLARIFY_APPLIED, false); // 无问题 → 原 query 继续
                return updates;
            }
            StringBuilder calibrated = new StringBuilder("Initial Query: ").append(original);
            calibrated.append("\nFollow-up Questions and Answers:\n");
            for (String q : questions) {
                calibrated.append("Q: ").append(q)
                        .append("\nA: Automatically proceeding with research\n");
            }
            if (calibrated.length() > retrieval.calibratedQueryMaxChars()) {
                calibrated.setLength(retrieval.calibratedQueryMaxChars());
            }
            updates.put(DeepResearchState.K_QUERY, calibrated.toString());
            updates.put(DeepResearchState.K_CLARIFY_APPLIED, true);
        } catch (Exception e) {
            updates.put(DeepResearchState.K_CLARIFY_APPLIED, false); // LLM 失败 → 跳过澄清（防整轮失败）
        }
        return updates;
    }
}
