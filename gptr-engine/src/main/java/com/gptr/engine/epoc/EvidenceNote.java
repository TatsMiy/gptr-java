package com.gptr.engine.epoc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 证据笔记（EvidenceBank 的元素：insight + quote + 已授权 sourceUrl）。
 *
 * <p>每条 note = extract 从某子查询证据中提炼的结构化证据。其中**两个文本字段语义不同，
 * 不可混用**：
 * <ul>
 *   <li><b>{@code insight}</b> —— **归纳出的学习点/结论**（LLM 生成，可改写；prompt 只要求
 *       "extract key learnings"，**无逐字约束**）。例外：Y 臂 {@code direct=true} 的蒸馏句
 *       产物不经 LLM，此时 insight 就是蒸馏块的逐字原句。</li>
 *   <li><b>{@code quote}</b> —— **支撑该 insight 的逐字原文摘录**（prompt 要求
 *       "quoted VERBATIM"）。存储上限 2000（现由 {@code ExtractionBudget#quoteStoreMax} 供给），
 *       渲染时才切 ≤120 ——
 *       修复 J6 在 parse 时 120 硬切会切断关键数字/否定词的问题。</li>
 * </ul>
 * 简记：<b>insight 是"我们要说的话"，quote 是"原文怎么说的"</b>；二者 +
 * 已授权 {@code sourceUrl} 构成一条 note。
 * <p>（本段此前写作"insight（LLM 原句）"，与 quote 语义混淆 —— 2026-09-15 修正。）
 *
 * <p>工程约束：PostgresCheckpointSaver 把 state Map 整体 Jackson 化，值必须是
 * JSON 友好类型 → evidenceBank 以 {@code List<String>}（每条 = 本类 JSON）承载。
 */
public record EvidenceNote(int idx, int depth, int round, int queryIdx,
                           String queryText, String insight, String quote, String sourceUrl) {

    // 两个存储上限（quote=2000 / queryText=400）已并入预算载体 ExtractionBudget
        // ——消费点经参数接收，本类不再声明
        // （同一份数据不得两处声明）。

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** extract 解析出的原始 note（无 idx/depth/round——由合并处分配）。
     *  direct=true：Y 臂蒸馏句 Java 直通（页级蒸馏无查询视角，跨组复制零增益 →
     *  合并处对其 (sourceUrl, insight) 去重）；false=LLM 提炼产物（不去重，语义可重复）。 */
    public record Raw(String insight, String quote, String sourceUrl, boolean direct) {
    }

    /** 从 JSON 单对象字符串恢复（checkpoint 恢复路径）。 */
    public static EvidenceNote fromJson(String json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            return new EvidenceNote(
                    n.path("idx").asInt(-1),
                    n.path("depth").asInt(0),
                    n.path("round").asInt(0),
                    n.path("queryIdx").asInt(-1),
                    n.path("queryText").asText(""),
                    n.path("insight").asText(""),
                    n.path("quote").asText(""),
                    n.path("sourceUrl").asText(""));
        } catch (Exception e) {
            throw new IllegalStateException("failed to decode EvidenceNote: " + e.getMessage(), e);
        }
    }

    /** 序列化为单对象 JSON（evidenceBank 元素；checkpoint 兼容 String 承载）。 */
    public String toJson() {
        try {
            var n = MAPPER.createObjectNode();
            n.put("idx", idx);
            n.put("depth", depth);
            n.put("round", round);
            n.put("queryIdx", queryIdx);
            n.put("queryText", queryText);
            n.put("insight", insight);
            n.put("quote", quote);
            n.put("sourceUrl", sourceUrl == null ? "" : sourceUrl);
            return MAPPER.writeValueAsString(n);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode EvidenceNote: " + e.getMessage(), e);
        }
    }

    /**
     * 渲染为兼容旧格式的 learnings 文本：{@code insight [quote: ≤120] [source: url]}。
     * quote 渲染截断 120（保真存储在 note.quote）；sourceUrl 为空时不写 [source:]
     * 且连带省略 [quote:]（孤儿 quote 无 URL 锚不可审计，与 J6 剥除红线一致）。
     * 截断统一走 {@link DeepResearchPrompts#truncateQuote}（120 + "…"，与 parseLearnings
     * 渲染逐字一致；不用 ContextManager.truncateEach——其 "\n...[truncated]" 会破坏单行格式）。
     */
    public String renderLearningText() {
        StringBuilder sb = new StringBuilder(insight == null ? "" : insight.trim());
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            if (quote != null && !quote.isBlank()) {
                sb.append(" [quote: ").append(DeepResearchPrompts.truncateQuote(quote)).append("]");
            }
            sb.append(" [source: ").append(sourceUrl).append("]");
        }
        return sb.toString();
    }

    /** bank 元素列表 → 渲染 learnings 视图（与 extract 合并顺序一致）。 */
    public static List<String> renderAll(List<EvidenceNote> bank) {
        List<String> out = new ArrayList<>(bank.size());
        for (EvidenceNote n : bank) {
            out.add(n.renderLearningText());
        }
        return out;
    }
}
