package com.gptr.engine.search;


import com.gptr.engine.plan.SubQuery;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
import com.gptr.integration.exception.TransientApiException;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 检索器（SEARCHING 阶段）：子查询并行走检索降级链 → 按 URL 去重聚合。
 *
 * <p>并发用 Java 21 虚拟线程（try-with-resources 自动等待全部完成，无池泄漏）；
 * 任一子查询失败（降级链全失效）不中断整体，跳过该子查询。
 */
@Component
public class Searcher {

    /** 全败时收集的失败根因条数上限（每源一条摘要）。 */
    private static final int MAX_FAILURE_CAUSES = 3;

    /** 单条失败根因摘要的截断长度。 */
    private static final int FAILURE_CAUSE_MAX_CHARS = 160;

    private final SearchClient searchClient; // 经弹性层包装（降级链 + 重试 + 熔断）

    public Searcher(SearchClient searchClient) {
        this.searchClient = searchClient;
    }

    /** 编排结果 = 去重结果 + 实际命中源集（每次成功响应 sourceUsed 去重）。 */
    public record SearchOutcome(List<SearchResult> results, Set<String> hitSources) {
    }

    /** 并行检索全部子查询，返回按 URL 去重后的结果（保序）。 */
    public SearchOutcome searchAll(List<SubQuery> queries) {
        return searchAll(queries, SearchOptions.DEFAULT);
    }

    /** 并行检索全部子查询（透传引擎/结果数选项）。 */
    public SearchOutcome searchAll(List<SubQuery> queries, SearchOptions options) {
        SearchOptions opts = options == null ? SearchOptions.DEFAULT : options;
        Set<String> failureCauses = new LinkedHashSet<>(); // 根因摘要（诊断黑盒修复）
        Set<String> hitSources = new LinkedHashSet<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // 每个子查询一个虚拟线程；失败在 searchOne 内隔离（部分失败容忍）
            List<CompletableFuture<Optional<SearchResponse>>> futures = new ArrayList<>(queries.size());
            for (SubQuery subQuery : queries) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> searchOne(subQuery, opts, failureCauses), pool));
            }
            Map<String, SearchResult> dedup = new LinkedHashMap<>();
            int succeeded = 0;
            for (CompletableFuture<Optional<SearchResponse>> f : futures) {
                Optional<SearchResponse> r = f.join();
                if (r.isEmpty()) {
                    continue; // 失败子查询跳过（部分失败容忍）
                }
                succeeded++;
                SearchResponse resp = r.get();
                if (resp.sourceUsed() != null && !resp.sourceUsed().isBlank()) {
                    hitSources.add(resp.sourceUsed());
                }
                for (SearchResult sr : resp.results()) {
                    dedup.putIfAbsent(sr.url(), sr);
                }
            }
            // 全部子查询失败 = 检索系统故障——显式失败（可重试），
            // 不再静默吞成"空结果"（否则 WRITING 将在空上下文上编造报告）。
            // 失败消息带根因摘要（每源首条），杜绝"黑盒失败靠猜"。
            if (succeeded == 0) {
                String causes = failureCauses.isEmpty() ? "(no failure detail)"
                        : String.join(" | ", failureCauses);
                throw new TransientApiException(searchClient.name(),
                        "all " + queries.size() + " search queries failed after retries; causes: " + causes);
            }
            return new SearchOutcome(new ArrayList<>(dedup.values()), hitSources);
        }
    }

    /** 检索单个子查询：失败返回 empty 并记录异常链根因摘要（原 lambda 体逐字搬入）。
     *  根因按"最多 {@value #MAX_FAILURE_CAUSES} 条、取异常链最内层消息首
     *  {@value #FAILURE_CAUSE_MAX_CHARS} 字符"去重收集，供全败时的诊断信息。 */
    private Optional<SearchResponse> searchOne(SubQuery subQuery, SearchOptions opts,
                                               Set<String> failureCauses) {
        try {
            return Optional.ofNullable(searchClient.search(subQuery.query(), opts));
        } catch (Exception e) {
            synchronized (failureCauses) {
                // 取异常链根因第一行（去重），供失败诊断
                if (failureCauses.size() < MAX_FAILURE_CAUSES) {
                    Throwable root = e;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    failureCauses.add(shortCause(root));
                }
            }
            return Optional.<SearchResponse>empty(); // 该子查询失败（弹性层已重试/降级）
        }
    }

    /** 失败根因摘要（原嵌套三元抽为具名方法）：无消息用类名，有消息截断到
     *  {@value #FAILURE_CAUSE_MAX_CHARS} 字符。 */
    private static String shortCause(Throwable root) {
        String causeMsg = root.getMessage();
        if (causeMsg == null) {
            return root.getClass().getSimpleName();
        }
        return causeMsg.length() <= FAILURE_CAUSE_MAX_CHARS
                ? causeMsg : causeMsg.substring(0, FAILURE_CAUSE_MAX_CHARS);
    }
}
