package com.gptr.engine.epoc;

import com.gptr.integration.client.LlmClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 取名前的来源分档器：**只依据 URL** 判断来源质量，返回"高 / 普通"两组下标。
 *
 * <p>用途（关口 A）：q08 缺失根因的第一断裂段是「抓取取名按检索原序取前 N，不看来源价值」
 * ——含全题最硬数据的文章因组内排第 4 而落选。本类在取名前给出质量信号。
 *
 * <p><b>与 {@code CurateNode} 的分工</b>（勿混）：本类在 {@code scrape} **之前**、只看 URL、
 * 只调顺序；curate 在 {@code scrape} **之后**、看条目内容、做筛选。
 *
 * <p><b>失败即回退</b>：调用异常 / 非 JSON / 空 {@code high} → 返回 {@code null}，
 * 调用方据此完整回退检索原序（行为与开关关闭时逐字相同）。
 */
final class SourceRanker {

    private static final Logger LOG = LoggerFactory.getLogger(SourceRanker.class);

    /** 分档结果：在入参列表中的下标，按档分组（**档内保持原序**）。 */
    record Priority(List<Integer> high, List<Integer> normal) {
    }

    /**
     * 对候选 URL 分档。
     *
     * @param urls          候选 URL（顺序即检索原序；调用方不得传入正文/snippet）
     * @param query         研究问题——分档要判断"对本题而言是否权威"，故必须传入
     * @param llm           当前任务的 LLM 客户端（不引入新模型）
     * @param costCallback  成本回调（与引擎其余调用同口径）
     * @return 分档结果；**候选 &lt; 2 条或任何失败 → {@code null}**（调用方回退原序）
     */
    static Priority rank(
            List<String> urls, String query, LlmClient llm, DoubleConsumer costCallback) {
        if (urls == null || urls.size() < 2) {
            return null; // 排序无意义，且避免为单条候选付费
        }
        try {
            StringBuilder numbered = new StringBuilder();
            for (int i = 0; i < urls.size(); i++) {
                numbered.append(i + 1).append(". ").append(urls.get(i)).append('\n');
            }
            String user = DeepResearchPrompts.get("rank-sources.user")
                .replace("{query}", query == null ? "" : query)
                .replace("{urls}", numbered.toString());
            String raw = llm.chatJson(DeepResearchPrompts.get("rank-sources.system"), user);
            costCallback.accept(llm.lastCallCostUsd());

            List<Integer> high = DeepResearchPrompts.parseHighIndices(raw, urls.size());
            if (high.isEmpty()) {
                return null; // 坏输出/空 high → 回退原序
            }
            Set<Integer> highSet = new LinkedHashSet<>(high);
            List<Integer> normal = new ArrayList<>();
            for (int i = 0; i < urls.size(); i++) {
                if (!highSet.contains(i)) { normal.add(i); } // 未列入 high 仍是 normal：本闸不过滤
            }
            LOG.info("[diag] rank sources: n={} high={} normal={}", urls.size(), highSet, normal.size());
            return new Priority(new ArrayList<>(highSet), normal);
        } catch (Exception e) {
            LOG.warn("[diag] rank sources failed, fallback to original order: {}", e.toString());
            return null;
        }
    }

    /**
     * 按分档结果重排：**high 组前置，normal 随后**，组内保持各自原序。
     *
     * @return 重排后的新列表；{@code priority} 为 {@code null} 时**返回原列表**
     *         （调用方无需判空分支）
     */
    static <T> List<T> reorder(List<T> items, Priority priority) {
        if (priority == null || items == null) {
            return items;
        }
        List<T> out = new ArrayList<>(items.size());
        priority.high().forEach(i -> out.add(items.get(i)));
        priority.normal().forEach(i -> out.add(items.get(i)));
        return out;
    }

    private SourceRanker() {
    }
}
