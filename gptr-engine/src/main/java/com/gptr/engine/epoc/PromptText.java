package com.gptr.engine.epoc;

import java.util.ArrayList;
import java.util.List;

/**
 * prompt 文本预算：把字符串列表拼成限长文本，供 prompt 占位符（{@code {learnings}} /
 * {@code {gaps}} / {@code {context}}）填充。
 *
 * <p>两个方法的差别只在「空列表返回什么」与「列表项是否带前缀」：
 * <ul>
 *   <li>{@link #joinLimit} —— 列表项带 {@code "- "} 前缀，空列表返回 {@code "(none)"}
 *       （用于"给 LLM 看的清单"——空清单必须有明确表示，不能留白）；</li>
 *   <li>{@link #joinItemsLimit} —— 不带前缀，空列表返回空串（用于"条目直接拼接"）。</li>
 * </ul>
 * 两者超预算时都只附 {@code "...[truncated]"} 标记，<b>不改写条目内容本身</b>。
 *
 * <p>纯函数、无状态：不读配置、不碰 state、不调 LLM、**不写日志**（观测由调用方负责，
 * 见 {@link Joined}）。
 */
final class PromptText {

    private PromptText() {
    }

    /** 一条被预算丢弃的条目（供观测；拼接行为本身不受影响）。 */
    record Dropped(int index, int chars, String kind) {
    }

    /** 拼接结果：文本 + 被丢弃条目清单（未截断时为空列表，非 null）。 */
    record Joined(String text, List<Dropped> dropped) {
    }

        /** 条目类型判据——与生产端前缀一致。 */
    private static String kindOf(String item) {
        if (item.startsWith(EvidenceText.DISTILLED_MARKER)) {
            return "distilled";
        }
        return item.startsWith("Full content of ") ? "content" : "summary";
    }

    /** 条目清单 → 限长文本（列表项带 "- " 前缀；空列表 → "(none)"）。 */
    static String joinLimit(List<String> items, int maxChars) {
        if (items == null || items.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (s == null) {
                continue;
            }
            sb.append("- ").append(s).append("\n");
            if (sb.length() > maxChars) {
                sb.append("...[truncated]");
                break;
            }
        }
        return sb.toString();
    }

    /** 条目清单 → 限长文本（不带前缀；空列表 → 空串），并返回被预算丢弃的条目清单。
     *
     *  <p>丢弃语义与改前一致：**超限即整条不进**（不是截半条），并把该条及其后所有非空条目
     *  记入 {@code dropped}（它们同样未进文本）。null/blank 条目属 {@code continue} 跳过，
     *  **不计入 {@code dropped}**。 */
    static Joined joinItemsLimit(List<String> items, int maxChars) {
        if (items == null || items.isEmpty()) {
            return new Joined("", List.of());
        }
        StringBuilder sb = new StringBuilder();
        int truncatedAt = -1;
        for (int i = 0; i < items.size(); i++) {
            String it = items.get(i);
            if (it == null || it.isBlank()) {
                continue;
            }
            if (sb.length() + it.length() + 2 > maxChars) {
                truncatedAt = i;
                break;
            }
            sb.append(it).append("\n");
        }
        if (truncatedAt < 0) {
            return new Joined(sb.toString(), List.of());
        }
        List<Dropped> dropped = new ArrayList<>();
        for (int i = truncatedAt; i < items.size(); i++) {
            String it = items.get(i);
            if (it == null || it.isBlank()) {
                continue;
            }
            dropped.add(new Dropped(i, it.length(), kindOf(it)));
        }
        sb.append("\n...[truncated]");
        return new Joined(sb.toString(), dropped);
    }
}
