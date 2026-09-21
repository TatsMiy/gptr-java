package com.gptr.engine.epoc;


import com.gptr.engine.budget.RetrievalBudget;
import com.gptr.engine.context.ContextManager;
import com.gptr.integration.exception.TransientApiException;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SearchNode —— 由 DeepResearchGraph 外迁的节点（方法体逐字未改）。 */
final class SearchNode {

    private static final Logger LOG = LoggerFactory.getLogger(SearchNode.class);

    private SearchNode() {
    }

    /** 真实检索（降级链包装 SearchClient；层内子查询虚拟线程并行）。
     *  I-7：同时把每子查询结果组装为独立条目组（queryItems，与 queries 对齐）。
     *  每子查询响应带实际命中源 → 去重累积进状态 hitSources（观测 detail）。 */
    static AsyncNodeAction<DeepResearchState> realSearch(SearchClient search, SearchOptions searchOptions,
                                                        RetrievalBudget retrieval) {
        return state -> CompletableFuture.supplyAsync(
                () -> runSearch(state, search, searchOptions, retrieval));
    }

    /** {@link #realSearch} 的实现体（原 lambda 体逐字搬入，缩进 −2 层）。 */
    private static Map<String, Object> runSearch(DeepResearchState state, SearchClient search,
                                                 SearchOptions searchOptions, RetrievalBudget retrieval) {
        List<String> queries = state.queries();
        Map<String, Object> updates = new HashMap<>();
        if (queries.isEmpty()) {
            updates.put(DeepResearchState.K_SEARCH_RESULTS, "");
            return updates;
        }
        StringBuilder sb = new StringBuilder();
        List<List<String>> groups = new ArrayList<>();
        List<String> collected = new ArrayList<>(state.collectedUrls());
        Set<String> hits = new LinkedHashSet<>(state.hitSources());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<SearchResponse>> futures = queries.stream()
                    .map(q -> CompletableFuture.supplyAsync(
                            () -> safeSearch(search, q, searchOptions), pool))
                    .toList();
            List<SearchResponse> perQueryResponses = new ArrayList<>();
            for (CompletableFuture<SearchResponse> f : futures) {
                perQueryResponses.add(f.join());
            }
            // 失败形态统计（2026-09-17）：① 抛异常（safeSearch 兜底 ⇒ sourceUsed 为空）
                    // ② 接口正常但 0 结果。二者此前在下游完全不可区分。
            int failed = 0;
            int emptyOk = 0;
            for (SearchResponse resp : perQueryResponses) {
                boolean ok = resp.sourceUsed() != null && !resp.sourceUsed().isBlank();
                if (!ok) {
                    failed++;
                } else if (resp.results().isEmpty()) {
                    emptyOk++;
                }
                if (ok) {
                    hits.add(resp.sourceUsed());
                }
                List<String> group = new ArrayList<>();
                for (SearchResult r : resp.results()) {
                    String block = "Title: " + r.title() + "\nURL: " + r.url()
                            + "\nSnippet: " + r.snippet();
                    if (r.hasContent()) {
                        block += "\nContent: " + ContextManager.truncateEach(
                                r.content(), retrieval.searchContentMaxChars());
                    }
                    group.add(block);
                    sb.append(block).append("\n\n");
                    if (!collected.contains(r.url())) {
                        collected.add(r.url()); // C3-D：真实检索 URL 累积（授权来源）
                    }
                }
                groups.add(group);
            }
            checkSearchOutcome(state, queries.size(), failed, emptyOk);
        }
        updates.put(DeepResearchState.K_SEARCH_RESULTS, sb.toString());
        updates.put(DeepResearchState.K_QUERY_ITEMS, groups);
        updates.put(DeepResearchState.K_COLLECTED_URLS, collected);
        updates.put(DeepResearchState.K_HIT_SOURCES, new ArrayList<>(hits));
        // 批 2 诊断：每条查询命中数 + 该组贡献的"新 URL"数（覆盖补查是否真带新来源看 new）
        Set<String> seenUrls = new LinkedHashSet<>();
        StringBuilder perGroup = new StringBuilder();
        for (int i = 0; i < groups.size(); i++) {
            int fresh = 0;
            for (String item : groups.get(i)) {
                String url = ScrapeQuotaScheduler.urlOfItem(item);
                if (url != null && seenUrls.add(url)) {
                    fresh++;
                }
            }
            perGroup.append(i).append(':').append(groups.get(i).size())
                    .append("/new").append(fresh).append(' ');
        }
        LOG.info("[batch2] search d{}: queries={} hits[{}] distinctUrls={}",
                state.currentDepth() + 1, queries.size(), perGroup.toString().trim(),
                seenUrls.size());
        return updates;
    }

    /** 本轮检索的失败形态判定（2026-09-17 从 {@link #runSearch} 抽出，为守住「方法 ≤80 行」棘轮）。
     *
     *  <p>两种失败形态此前在下游**完全不可区分**，是"故障伪装成成功"链的第一环
     *  （audit-01 S1/S2 记录：静默空 → 守卫把"检索失败"与"本轮无新信息"混同 → WRITING 拿空上下文
     *  照常出报告 → SUCCEEDED；更糟的是空上下文下 LLM 可能编造 learning+sourceUrl ⇒ 守卫反而不触发
     *  ⇒ 编造内容全链路成功）。
     *
     *  @param queryCount 本轮子查询数
     *  @param failed     形态①：抛异常（{@link #safeSearch} 兜底 ⇒ {@code sourceUsed} 为空）
     *  @param emptyOk    形态②：接口正常但 0 结果
     */
    private static void checkSearchOutcome(DeepResearchState state, int queryCount,
                                           int failed, int emptyOk) {
        // 形态①：**全部**抛异常 ⇒ 这是检索故障，不是"没资料" ⇒ 不得伪装成成功。
        if (failed == queryCount) {
            throw new TransientApiException("search", "all " + queryCount
                    + " sub-queries failed（详见上方 [search] query failed 日志）");
        }
        // 形态②：本轮无任何结果但接口正常 ⇒ **不抛**（可能真是冷门题），但必须 WARN，
        // 让"检索器失效"与"该题确实无资料"在日志里可分。
        if (emptyOk > 0 && emptyOk + failed == queryCount) {
            LOG.warn("[batch2] search d{}: 本轮 {} 个查询全部无结果（{} 个失败 / {} 个空）—— "
                    + "可能是检索器失效，也可能是该题确实无资料（此前二者不可区分）",
                    state.currentDepth() + 1, queryCount, failed, emptyOk);
        }
    }

    /** 单查询检索：失败**记日志**并返回空（不再静默）。
     *
     *  <p><b>边界</b>：本方法只保证「单查询失败**可见**」，**不判断「全部失败」**——那是调用点的事。
     *  两处调用点语义不同：正式检索全失败 = 研究无素材（须失败，见 {@link #runSearch}）；
     *  澄清前奏的初搜失败 = 可跳过（{@code ResearchPlanNode}，初搜仅作方向校准）。
     *
     *  <p>2026-09-17 修正：原实现为静默 {@code return SearchResponse.empty()}，被三份审计点名
     *  （{@code audit-01} S3「检索故障被静默吞成"无结果"」、{@code audit-02} M4「吞掉包括熔断打开
     *  在内的所有异常」、{@code C3}「与 {@code Searcher.searchAll} 失败语义不一致」），
      *  但从未修也从未登记。失败不可见时，"检索器挂了"与"确实没结果"在日志里
     *  **完全一样**——本次冒烟即因此靠手工探测 crawler 才定位。 */
    static SearchResponse safeSearch(
            SearchClient search, String query, SearchOptions opts) {
        try {
            return search.search(query, opts);
        } catch (Exception e) {
            LOG.warn("[search] query failed: {} | {}: {}", query,
                    e.getClass().getSimpleName(), e.getMessage());
            return SearchResponse.empty();
        }
    }
}
