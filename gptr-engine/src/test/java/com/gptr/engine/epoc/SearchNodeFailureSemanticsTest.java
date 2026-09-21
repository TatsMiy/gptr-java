package com.gptr.engine.epoc;

import com.gptr.engine.budget.Budgets;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.exception.TransientApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索失败语义（2026-09-17）——两种失败形态必须与「确实没结果」可分。
 *
 * <p>背景：{@code SearchNode#safeSearch} 原先静默 {@code return SearchResponse.empty()}，
  * 被三份审计点名却从未修。失败不可见时，
 * 「检索器挂了」与「该题确实没资料」在下游**完全一样** ⇒ 守卫误判 ⇒ 空上下文照常出报告。
 *
 * <p>本测试锁住两条边界：
 * <ol>
 *   <li><b>全部子查询抛异常</b> ⇒ 必须向上传播（不得伪装成"本轮无新信息"）；</li>
 *   <li><b>部分失败</b>或<b>全部 0 结果（接口正常）</b> ⇒ **不得**抛异常（可能是冷门题，
 *       也可能是长尾查询失败——后者本就是设计上允许跳过的）。</li>
 * </ol>
 */
class SearchNodeFailureSemanticsTest {

    /** 最小状态：只要有 queries / collectedUrls / hitSources / currentDepth 四个键。 */
    private static DeepResearchState stateOf(String... queries) {
        Map<String, Object> init = new HashMap<>();
        init.put("queries", List.of(queries));
        init.put("collectedUrls", List.of());
        init.put("hitSources", List.of());
        init.put("currentDepth", 0);
        return new DeepResearchState(init);
    }

    private static SearchClient client(String name, Function<String, SearchResponse> behavior) {
        return new SearchClient() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public SearchResponse search(String query) {
                return behavior.apply(query);
            }
        };
    }

    /** 形态①：全部抛异常 ⇒ 必须传播（这是检索故障，不是"没资料"）。 */
    @Test
    void allQueriesThrowingFailsTheNodeInsteadOfSilentlySucceeding() {
        SearchClient boom = client("boom", q -> {
            throw new TransientApiException("boom", "network down");
        });
        var action = SearchNode.realSearch(boom, SearchOptions.DEFAULT, Budgets.defaults().retrieval());

        CompletionException ex = assertThrows(CompletionException.class,
                () -> action.apply(stateOf("q1", "q2")).join(),
                "全部子查询失败时必须向上传播——否则下游守卫会把'检索故障'当成'本轮无新信息'");

        assertTrue(ex.getCause() instanceof TransientApiException,
                "传播的应是 TransientApiException（可重试），实际 cause=" + ex.getCause());
    }

    /** 形态①的边界：**部分**失败仍应继续（长尾查询失败本就是设计允许的）。 */
    @Test
    void partiallyFailingQueriesStillProceed() {
        SearchClient mixed = client("mixed", q -> {
            if (q.equals("q1")) {
                return SearchResponse.empty(); // 这一条失败（safeSearch 兜底后的形态）
            }
            return new SearchResponse(List.of(), "mock-ok"); // 这一条成功但无结果
        });
        var action = SearchNode.realSearch(mixed, SearchOptions.DEFAULT, Budgets.defaults().retrieval());

        Map<String, Object> updates = action.apply(stateOf("q1", "q2")).join();
        assertTrue(updates.containsKey(DeepResearchState.K_SEARCH_RESULTS),
                "部分失败不得中断本轮检索");
    }

    /** 形态②：全部接口正常但 0 结果 ⇒ **不得**抛（可能真是冷门题），只是空结果 + WARN。 */
    @Test
    void allQueriesEmptyDoesNotFailTheNode() {
        SearchClient emptyOk = client("empty-ok", q -> new SearchResponse(List.of(), "mock-ok"));
        var action = SearchNode.realSearch(emptyOk, SearchOptions.DEFAULT, Budgets.defaults().retrieval());

        Map<String, Object> updates = action.apply(stateOf("q1", "q2")).join();
        assertEquals("", updates.get(DeepResearchState.K_SEARCH_RESULTS),
                "全 0 结果应产出空检索串，而不是抛异常");
    }
}
