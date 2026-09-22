package com.gptr.engine.epoc;

import com.gptr.engine.budget.ReflectBudget;
import com.gptr.integration.client.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

/** FollowUpNode —— 由 DeepResearchGraph 外迁的节点（方法体逐字未改）。 */
final class FollowUpNode {

    private static final Logger LOG = LoggerFactory.getLogger(FollowUpNode.class);

    /** 无缺口信号时下一层查询数的**硬编码下界**（衰减后再低也不低于此）。 */
    private static final int FOLLOWUP_MIN_QUERIES_NO_GAPS = 2;

    private FollowUpNode() {
    }

    /** learnings 驱动的下一层查询（聚焦知识缺口，breadth×decay 衰减）。
     *  user 注入中央研究状态（已覆盖 vs 缺口），防分支 silo 与主题漂移。
     *  缺口驱动：plan_reflect 产出 gaps 时——数量 = clamp(gaps 数, 1, 衰减上限)，
     *  gaps 逐条作为查询种子（替代硬公式）→ 缺口优先补检；无 gaps 信号回退旧公式（兼容）。 */
    static Map<String, Object> runFollowUpQueries(DeepResearchState state, LlmClient llm,
                                                  DoubleConsumer costCallback,
                                                  double breadthDecay,
                                                  ReflectBudget reflect) {
        Object gapsRaw = state.planGaps();
        List<String> planGaps = new ArrayList<>();
        if (gapsRaw instanceof List<?> l) {
            for (Object o : l) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    planGaps.add(String.valueOf(o).trim());
                }
            }
        }
        int nextCount = followUpCount(state.breadth(), breadthDecay,
                Math.max(1, state.currentDepth()), planGaps);
        String gapsText = planGaps.isEmpty()
                ? PromptText.joinLimit(state.followUpQuestions(), reflect.followUpGapsMaxChars())
                : PromptText.joinLimit(planGaps, reflect.followUpGapsMaxChars());
        String system = DeepResearchPrompts.get("generate-followup-queries.system");
        String user = DeepResearchPrompts.get("generate-followup-queries.user")
                .replace("{query}", state.query())
                .replace("{numQueries}", String.valueOf(nextCount))
                .replace("{learnings}",
                        PromptText.joinLimit(state.learnings(), reflect.followUpLearningsMaxChars()))
                .replace("{gaps}", gapsText)
                .replace("{researchState}", state.researchState().isBlank()
                        ? "(none)" : state.researchState());
        LOG.info("[batch2] follow-up: gaps={} nextCount={}", planGaps.size(), nextCount);
        String raw = llm.chatJson(system, user);
        List<String> queries = DeepResearchPrompts.parseQueryList(raw, nextCount);
        costCallback.accept(llm.lastCallCostUsd());
        Map<String, Object> updates = new HashMap<>();
        updates.put(DeepResearchState.K_QUERIES, queries);
        return updates;
    }

    /** follow-up 数量——缺口为需求下限、衰减为预算上限；
     *  无缺口信号（plan_reflect 未产出/解析失败）→ 旧硬公式（兼容既有行为与测试）。 */
    static int followUpCount(int breadth, double decay, int layer, List<String> gaps) {
        if (gaps == null || gaps.isEmpty()) {
            return Math.max(FOLLOWUP_MIN_QUERIES_NO_GAPS, (int) Math.ceil(breadth * decay));
        }
        int cap = Math.max(1, (int) Math.ceil(breadth * Math.pow(decay, Math.max(1, layer))));
        return Math.max(1, Math.min(gaps.size(), cap));
    }
}
