package com.gptr.engine.epoc;

import com.gptr.engine.budget.ReflectBudget;
import com.gptr.engine.context.ContextManager;
import com.gptr.integration.client.LlmClient;
import org.bsc.langgraph4j.action.AsyncNodeAction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;

/** PlanReflectNode —— 由 DeepResearchGraph 外迁的节点（方法体逐字未改）。 */
final class PlanReflectNode {

    private PlanReflectNode() {
    }

    /** 层间计划反思——把"已覆盖 vs 剩余缺口"压成中央研究状态（researchState）
     *  写回状态，供下一层 follow-up 查询生成读取（对标 Reflect-Evolve 顺序反思 +
     *  DeepPlanner 受管理计划的折中：层边界反思，非逐查询）。坏输出 → "(none)" 继续。 */
    static AsyncNodeAction<DeepResearchState> realPlanReflect(
            LlmClient llm, DoubleConsumer costCallback, ReflectBudget reflect) {
        return state -> CompletableFuture.supplyAsync(
                () -> runPlanReflect(state, llm, costCallback, reflect));
    }

    /** {@link #realPlanReflect} 的实现体（原 lambda 体逐字搬入，缩进 −2 层）。 */
    private static Map<String, Object> runPlanReflect(DeepResearchState state, LlmClient llm,
                                                      DoubleConsumer costCallback,
                                                      ReflectBudget reflect) {
        Map<String, Object> updates = new HashMap<>();
        String system = DeepResearchPrompts.get("plan-reflect.system");
        String user = DeepResearchPrompts.get("plan-reflect.user")
                .replace("{query}", state.query())
                .replace("{learnings}",
                        PromptText.joinLimit(state.learnings(), reflect.planReflectLearningsMaxChars()))
                .replace("{gaps}",
                        PromptText.joinLimit(state.followUpQuestions(), reflect.planReflectGapsMaxChars()));
        try {
            String raw = llm.chatJson(system, user);
            costCallback.accept(llm.lastCallCostUsd());
            var parsed = DeepResearchPrompts.parseResearchState(raw);
            updates.put(DeepResearchState.K_RESEARCH_STATE, parsed == null || parsed.isBlank()
                    ? "(none)" : ContextManager.truncateEach(parsed, reflect.researchStateMaxChars()));
            // 批 2（缺口驱动）：显式缺口清单 → 下一轮 follow-up 的数量依据与查询种子
            updates.put(DeepResearchState.K_PLAN_GAPS, DeepResearchPrompts.parseGaps(raw));
        } catch (Exception e) {
            updates.put(DeepResearchState.K_RESEARCH_STATE, "(none)"); // 坏输出 → 继续不失败
            updates.put(DeepResearchState.K_PLAN_GAPS, List.of());
        }
        return updates;
    }
}
