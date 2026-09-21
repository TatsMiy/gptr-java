package com.gptr.engine.context;


import com.gptr.engine.budget.Budgets;
import com.gptr.engine.budget.CurateBudget;
import com.gptr.engine.epoc.EvidenceNote;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 研究上下文管理器（对应原版 context/compression.py 的轻量 Java 版）。
 *
 * <p>职责：把 deep research 产出的 learnings 合并为研究上下文——去重（原文完全/
 * 前缀重复）、按总长度预算截断（保护 LLM 输入窗口）、按 query 相关性过滤。
 */
@Component
public class ContextManager {

    private final int maxChars;
    /** 单 URL 深度回填上限——原为 {@code MAX_PER_URL} 常量，现由精选域预算供给。 */
    private final int maxPerUrl;

    public ContextManager() {
        this(Budgets.defaults().writing().contextMaxChars());
    }

    public ContextManager(int maxChars) {
        this(maxChars, Budgets.defaults().curate());
    }

    /** 按域构造注入：只注入本类需要的子预算，不注入整包 {@code Budgets}。 */
    public ContextManager(int maxChars, CurateBudget curate) {
        this.maxChars = maxChars;
        this.maxPerUrl = curate.maxPerUrl();
    }

    /**
     * 把任意长文本截断到预算（保留首部 + 省略标记）。进 LLM 的 context 统一走此入口。
     */
    public String truncateToBudget(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...[truncated by ContextManager]";
    }

    /** 单条文本截断到指定上限（逐条截断用，如每条来源正文）。 */
    public static String truncateEach(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...[truncated]";
    }

    /**
     * 合并 learnings 为研究上下文：去重 → 截断到 maxChars。
     * 每条 learning 保持原样（含来源 URL），合并后整体限长。
     */
    public String buildContext(List<String> learnings) {
        List<String> deduped = deduplicate(learnings);
        StringBuilder sb = new StringBuilder();
        for (String learning : deduped) {
            String block = "- " + learning + "\n";
            if (sb.length() + block.length() > maxChars) {
                // 【2026-09-14 修复】原为 break：单条超预算即停止累积，而**首条就超预算时
                // sb 仍为空 → 本方法返回空串**，空上下文被静默送进写作，违反 C3-S1
                // （"空组不调 LLM / 防空上下文编造"）不变量。
                // 改为跳过放不下的单条、继续尝试后续更短者；若首条即超预算，
                // 至少收下它的截断版 —— 有候选时永不返回空串。
                if (sb.length() == 0) {
                    sb.append(truncateEach(block, Math.max(1, maxChars)));
                }
                continue;
            }
            sb.append(block);
        }
        return sb.toString();
    }

    /**
     * 去重：只按全文相同去重（跨 URL 重复仍去）。
     * 移除旧"同一来源 URL 只保留第一条"——同源后续关键证据被误丢（正确性缺陷）。
     */
    public List<String> deduplicate(List<String> items) {
        Set<String> seenText = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String normalized = item.strip();
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (seenText.contains(lower)) {
                continue;
            }
            seenText.add(lower);
            out.add(normalized);
        }
        return out;
    }

    /**
     * 按证据库组装上下文（deep 写作入口）：先每个子问题至少保留 1 条（零丢失底线），
     * 剩余预算按"来源多样优先、再小长度"填充（多样轮转后**同源深度回填**：每 URL 至多
     * {@code maxPerUrl} 条，替代旧的"每 URL 1 条即抛弃同源后序论据"）。输入 = evidenceBank
     * 元素（EvidenceNote JSON 串）。
     */
    public String buildEvidenceContext(List<String> bankJson, int maxChars) {
        if (bankJson == null || bankJson.isEmpty()) {
            return "";
        }
        // 解码并分组（queryIdx；-1 = 未归组）
        Map<Integer, List<String>> byQuery = new LinkedHashMap<>();
        for (String json : bankJson) {
            try {
                var note = EvidenceNote.fromJson(json);
                byQuery.computeIfAbsent(note.queryIdx(), k -> new ArrayList<>())
                        .add(json);
            } catch (Exception ignored) {
                // 坏元素跳过（不中断）
            }
        }
        List<String> selected = new ArrayList<>();
        Map<String, Integer> urlCount = new HashMap<>();
        // 1) 每子问题至少 1 条（到达序首条；同 URL 底线优先——跨组同源底线条不受上限约束）
        for (List<String> group : byQuery.values()) {
            if (!group.isEmpty()) {
                String first = group.get(0);
                selected.add(first);
                countUrl(urlCount, urlOf(first));
            }
        }
        // 2) 剩余：来源多样优先轮转（每轮每个 URL 至多 +1），深度回填到 maxPerUrl；
        //    长度小优先（每轮内先选可用者——保持原到达序近似）。
        boolean added;
        do {
            added = false;
            for (List<String> group : byQuery.values()) {
                for (String json : group) {
                    if (selected.contains(json)) {
                        continue;
                    }
                    if (!claimUrl(urlCount, urlOf(json))) {
                        continue; // 该 URL 已达深度上限（或空锚）
                    }
                    selected.add(json);
                    added = true;
                    break;
                }
            }
        } while (added);
        // 3) 预算内拼接（渲染 = renderLearningText 逐条）
        StringBuilder sb = new StringBuilder();
        for (String json : selected) {
            String line;
            try {
                line = EvidenceNote.fromJson(json).renderLearningText();
            } catch (Exception e) {
                // 坏 JSON 元素（截断/历史格式）→ 跳过该条，不阻断其余证据
                continue;
            }
            String block = "- " + line + "\n";
            if (sb.length() + block.length() > maxChars) {
                // 【2026-09-14 修复】同 buildContext：原 break 在"首条即超预算"时
                // 返回空串并静默送进写作。改为跳过 + 首条兜底截断，有候选就不返回空串。
                if (sb.length() == 0) {
                    sb.append(truncateEach(block, Math.max(1, maxChars)));
                }
                continue;
            }
            sb.append(block);
        }
        return sb.toString();
    }

    private static String urlOf(String json) {
        try {
            return EvidenceNote.fromJson(json).sourceUrl();
        } catch (Exception e) {
            // 解析失败 → 视为无 URL（调用方按空值分组）
            return "";
        }
    }

    private static void countUrl(Map<String, Integer> m, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        m.merge(url, 1, Integer::sum);
    }

    /** 尝试占一个 URL 名额：未达 {@code maxPerUrl} → 占用并 true；否则 false。
     *  实例方法（原为 static）：上限已由构造注入供给。 */
    private boolean claimUrl(Map<String, Integer> m, String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        int c = m.getOrDefault(url, 0);
        if (c >= maxPerUrl) {
            return false;
        }
        m.put(url, c + 1);
        return true;
    }

    /** 从 learning 文本提取 "[source: <url>]" 的来源 URL（无则 null）。 */
    static String extractSource(String text) {
        int idx = text.indexOf("[source:");
        if (idx < 0) {
            return null;
        }
        String tail = text.substring(idx + "[source:".length()).trim();
        int end = tail.indexOf(']');
        return end < 0 ? tail : tail.substring(0, end).trim();
    }
}
