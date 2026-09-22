package com.gptr.engine.epoc;

import com.gptr.engine.budget.CurateBudget;
import com.gptr.engine.context.ContextManager;
import com.gptr.integration.client.LlmClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.DoubleConsumer;

/** CurateNode —— 由 DeepResearchGraph 外迁的节点（方法体逐字未改）。 */
final class CurateNode {

    private CurateNode() {
    }

    /** 来源质量闸（对标 py SourceCurator；{@code curateSources} **默认开**，见 EngineConfig）。
     *  每子查询独立并行 curate：LLM 输出**有序**精选（kept 序号 1-based，"最佳→次佳"）；
     *  该顺序被保留并写回条目组，故下游 extract 让高质量条目优先占预算。
     *  坏输出/空结果/异常 → 回退原文（排序非删除，防误杀）；编号条目每条截断降本。 */
    //有CurateBudget，maxSources两个相关预算
    static Map<String, Object> runCurateSources(DeepResearchState state, LlmClient llm,
                                                int maxSources, DoubleConsumer costCallback,
                                                CurateBudget curate) {
        List<String> queries = state.queries();
        List<List<String>> items = state.queryItems();
        if (queries.isEmpty() || items.isEmpty()) {
            return Map.of();
        }
        List<List<String>> curated = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<String>>> futures = new ArrayList<>();
            for (int i = 0; i < queries.size(); i++) {
                final int idx = i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> curateOne(queries.get(idx),
                                idx < items.size() ? items.get(idx) : List.of(),
                                llm, maxSources, costCallback, curate), pool));
            }
            for (CompletableFuture<List<String>> f : futures) {
                curated.add(f.join());
            }
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(DeepResearchState.K_QUERY_ITEMS, curated);
        return updates;
    }

    /** 单组 curate：成功且 kept 非空 → 按 kept 顺序重组（≤maxSources）；否则原文。 */
    private static List<String> curateOne(String query, List<String> items, LlmClient llm,
                                          int maxSources, DoubleConsumer costCallback,
                                          CurateBudget curate) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            String entry = ContextManager.truncateEach(items.get(i), curate.entryMaxChars());
            entries.append(i + 1).append(". ").append(entry).append("\n\n");
            if (entries.length() > curate.totalMaxChars()) {
                entries.append("...[truncated]");
                break;
            }
        }
        String system = DeepResearchPrompts.get("curate-sources.system");
        String user = DeepResearchPrompts.get("curate-sources.user")
                .replace("{query}", query)
                .replace("{maxSources}", String.valueOf(maxSources))
                .replace("{entries}", entries.toString());
        try {
            String raw = llm.chatJson(system, user);
            costCallback.accept(llm.lastCallCostUsd());
            List<Integer> kept = DeepResearchPrompts.parseKeptIndices(raw);
            if (kept == null || kept.isEmpty()) {
                return items; // 坏输出/空 → 回退原文（防误杀）
            }
            List<String> out = new ArrayList<>();
            for (int k : kept) {
                if (k >= 1 && k <= items.size() && !out.contains(items.get(k - 1))) {
                    out.add(items.get(k - 1));
                }
                if (out.size() >= maxSources) {
                    break;
                }
            }
            return out.isEmpty() ? items : out; // LLM 全删 → 回退原文（宁可不过滤不可删光）
        } catch (Exception e) {
            return items; // 异常回退
        }
    }
}
