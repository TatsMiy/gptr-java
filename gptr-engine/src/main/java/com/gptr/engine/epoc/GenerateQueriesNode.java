package com.gptr.engine.epoc;


import com.gptr.integration.client.LlmClient;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** GenerateQueriesNode —— 由 DeepResearchGraph 外迁的节点（方法体逐字未改）。 */
final class GenerateQueriesNode {

    private static final Logger LOG = LoggerFactory.getLogger(GenerateQueriesNode.class);

    /** 首层查询总数上界公式 {@code max(倍数 × breadth, 下界)} 中的 breadth 倍数。 */
    private static final int MAX_QUERIES_BREADTH_FACTOR = 2;

    /** 同公式的下界：breadth 很小时仍至少给这么多查询。 */
    private static final int MAX_QUERIES_FLOOR = 4;

    private GenerateQueriesNode() {
    }

    /** 真实生成首层搜索查询（原版 generate-search-queries prompt + chatJson）。
     *  批 2（覆盖自检）：LLM 返回 {queries[{query,researchGoal,targetedDimension}],
     *  uncoveredDimensions[]}；uncoveredDimensions 非空 → 补一轮定向补查（≤缺失数条），
     *  合并去重。旧裸数组 schema 兼容（parseQuerySpecs 容错 → 回退 parseQueryList 语义）。
          *  coverMode 三档——dimensions（新，维度清单+机械下界）/ legacy（批 2
     *  自检补查）/ off（批 1 行为）。 */
    static AsyncNodeAction<DeepResearchState> realGenerateQueries(
            LlmClient llm, DoubleConsumer costCallback, String coverMode) {
        return state -> CompletableFuture.supplyAsync(
                () -> runGenerateQueries(state, llm, costCallback, coverMode));
    }

    /** {@link #realGenerateQueries} 的实现体（原 lambda 体逐字搬入，缩进 −2 层）。 */
    private static Map<String, Object> runGenerateQueries(DeepResearchState state, LlmClient llm,
                                                          DoubleConsumer costCallback, String coverMode) {
        String mode = coverMode == null ? "legacy" : coverMode.toLowerCase(Locale.ROOT);
        if ("dimensions".equals(mode)) {
            return generateByDimensions(llm, costCallback, state);
        }
        return generateLegacy(llm, costCallback, state, "off".equals(mode));
    }

    /**
     * 批 4-pre：维度清单路径（覆盖机制新形态）。
     *
     * <p>LLM 一次给出 {@code dimensions[{dimension, queries[]}]}；Java 只做**机械校验**：
     * 每个维度是否都有查询、查询总数是否 ≥ breadth——缺则触发**一次**定向补齐。
     * 与 legacy 的差别：覆盖判定从"LLM 自证缺失"变为"结构是否完整"，判定可复现。
     */
    private static Map<String, Object> generateByDimensions(LlmClient llm,
                                                            DoubleConsumer costCallback,
                                                            DeepResearchState state) {
        String system = DeepResearchPrompts.get("generate-search-queries.system");
        int maxQueries = Math.max(MAX_QUERIES_BREADTH_FACTOR * state.breadth(), MAX_QUERIES_FLOOR);
        String user = DeepResearchPrompts.get("generate-search-queries-dims.user")
                .replace("{numQueries}", String.valueOf(state.breadth()))
                .replace("{maxQueries}", String.valueOf(maxQueries))
                .replace("{query}", state.query());
        String raw = llm.chatJson(system, user);
        costCallback.accept(llm.lastCallCostUsd());
        DeepResearchPrompts.DimensionPlan plan =
                DeepResearchPrompts.parseDimensions(raw, maxQueries);
        if (plan.isEmpty()) {
            // 新 schema 解析失败 → 回退 legacy 解析（不让覆盖机制阻断主流程）
            LOG.warn("[batch4pre] dimensions schema 解析为空，回退 legacy 解析");
            return generateLegacy(llm, costCallback, state, false);
        }
        List<String> queries = new ArrayList<>(plan.queries());
        List<String> missing = DeepResearchPrompts.dimensionsWithoutQueries(plan);
        boolean shortOfBreadth = queries.size() < state.breadth();
        if (!missing.isEmpty() || shortOfBreadth) {
            LOG.info("[batch4pre] dims: {} dimension(s), {} queries, missing={} shortOfBreadth={}",
                    plan.dimensions().size(), queries.size(), missing, shortOfBreadth);
            List<String> targets = new ArrayList<>(missing);
            if (targets.isEmpty()) {
                targets.add("additional distinct aspects not yet covered "
                        + "(at least " + (state.breadth() - queries.size()) + " more queries)");
            }
            try {
                String fillUser = DeepResearchPrompts.get("generate-search-queries-fill.user")
                        .replace("{missing}", String.join("\n- ", targets))
                        .replace("{existing}", String.join("\n- ", queries));
                String raw2 = llm.chatJson(system, fillUser);
                costCallback.accept(llm.lastCallCostUsd());
                // 补齐上限 = max(缺失维度数, breadth 缺口)——只按缺失维度数会漏掉"维度都全但总数不够"
                int fillLimit = Math.max(targets.size(), state.breadth() - queries.size());
                int added = 0;
                for (DeepResearchPrompts.QuerySpec s
                        : DeepResearchPrompts.parseQuerySpecs(raw2, fillLimit)) {
                    if (!queries.contains(s.query())) {
                        queries.add(s.query());
                        added++;
                    }
                }
                LOG.info("[batch4pre] dims-fill added {} queries", added);
            } catch (Exception e) {
                LOG.warn("[batch4pre] dims-fill failed (keep base queries): {}", e.toString());
            }
        } else {
            LOG.info("[batch4pre] dims: {} dimension(s), {} queries, all dimensions covered",
                    plan.dimensions().size(), queries.size());
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(DeepResearchState.K_QUERIES, queries);
        // 维度清单（JSON 字符串列表，checkpoint 兼容）——批 4 大纲期按维度组织证据目录
        List<String> dims = new ArrayList<>();
        for (DeepResearchPrompts.Dimension d : plan.dimensions()) {
            dims.add(DeepResearchPrompts.dimensionToJson(d.name(), d.queries()));
        }
        updates.put(DeepResearchState.K_DIMENSIONS, dims);
        return updates;
    }

    /** 批 2 路径（legacy）/ 批 1 路径（silent=off：不生成维度、不补查）。 */
    private static Map<String, Object> generateLegacy(LlmClient llm, DoubleConsumer costCallback,
                                                      DeepResearchState state, boolean silent) {
        String system = DeepResearchPrompts.get("generate-search-queries.system");
        String user = DeepResearchPrompts.get("generate-search-queries.user")
                .replace("{numQueries}", String.valueOf(state.breadth()))
                .replace("{query}", state.query());
        String raw = llm.chatJson(system, user);
        costCallback.accept(llm.lastCallCostUsd());
        List<DeepResearchPrompts.QuerySpec> specs =
                DeepResearchPrompts.parseQuerySpecs(raw, state.breadth());
        List<String> queries = new ArrayList<>();
        if (specs.isEmpty()) {
            queries.addAll(DeepResearchPrompts.parseQueryList(raw, state.breadth()));
        } else {
            specs.forEach(s -> queries.add(s.query()));
        }
        if (silent) {
            LOG.info("[batch4pre] coverMode=off: {} queries (no coverage mechanism)", queries.size());
            Map<String, Object> updates = new HashMap<>();
            updates.put(DeepResearchState.K_QUERIES, queries);
            return updates;
        }
        List<String> uncovered = DeepResearchPrompts.parseUncoveredDimensions(raw);
        if (!uncovered.isEmpty()) {
            LOG.info("[batch2] cover-check: {} uncovered dimension(s): {}", uncovered.size(),
                    uncovered);
            try {
                String fillSystem = DeepResearchPrompts.get("generate-search-queries.system");
                String fillUser = DeepResearchPrompts.get("generate-search-queries-fill.user")
                        .replace("{missing}", String.join("\n- ", uncovered))
                        .replace("{existing}", String.join("\n- ", queries));
                String raw2 = llm.chatJson(fillSystem, fillUser);
                costCallback.accept(llm.lastCallCostUsd());
                List<DeepResearchPrompts.QuerySpec> fill =
                        DeepResearchPrompts.parseQuerySpecs(raw2, uncovered.size());
                int added = 0;
                for (DeepResearchPrompts.QuerySpec s : fill) {
                    if (!queries.contains(s.query())) {
                        queries.add(s.query());
                        added++;
                    }
                }
                LOG.info("[batch2] cover-fill added {} quer{}", added, added == 1 ? "y" : "ies");
            } catch (Exception e) {
                LOG.warn("[batch2] cover-fill failed (keep base queries): {}", e.toString());
            }
        } else if (!specs.isEmpty()) {
            LOG.info("[batch2] cover-check: all dimensions covered ({} queries)", queries.size());
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(DeepResearchState.K_QUERIES, queries);
        return updates;
    }
}
