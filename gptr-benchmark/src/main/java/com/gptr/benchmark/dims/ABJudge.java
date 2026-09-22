package com.gptr.benchmark.dims;

import com.fasterxml.jackson.databind.JsonNode;
import com.gptr.benchmark.llm.JudgeClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报告 A/B 盲判（引擎配置消偏对比，对标 py benchmark 的 --ab 思想）。
 *
 * <p>同一问题用两个引擎配置各跑一份报告，匿名编号（report1/report2 随机分配）后由
 * LLM 盲判四维胜率：accuracy（事实准确一致性）/ depth（覆盖深度全面性）/
 * citation（引用与证据质量）/ structure（结构与可读性）。judge 不知 config 映射，
 * 防"知道哪份是实验组就偏好实验组"的消偏。
 */
public final class ABJudge {

    /** 四维（固定顺序，聚合/输出复用）。 */
    public static final List<String> DIMENSIONS = List.of("accuracy", "depth", "citation", "structure");

    /**
          * 写作质量盲判维（跨系统报告对比）：
     * 组织与结构 / 语言与可读性 / 深度与洞察 / 引用纪律 / 克制与诚实 + 整体。
     */
    public static final List<String> WRITING_DIMENSIONS = List.of(
            "org_structure", "language_readability", "depth_insight",
            "citation_discipline", "honesty_restraint", "overall");

    private static final String BLIND_SYSTEM = """
            你是研究报告质量评审（盲判）。给定同一研究问题的两份匿名研究报告
            （report1 / report2；编号随机分配，不暗示质量、模型或任何来源差异），
            请从四个维度分别判定哪一份更优：
            - accuracy：事实准确与一致性（更少错误、臆造、自相矛盾）
            - depth：覆盖深度与全面性（关键方面是否都覆盖、挖掘是否深入）
            - citation：引用与证据质量（引用是否贴切、来源可信、论断与引用对应）
            - structure：结构与可读性（层次清晰、行文流畅、重点突出）

            每个维度只允许一个值："1"（report1 更优）、"2"（report2 更优）或
            "tie"（接近/各有优劣）。
            只输出 JSON，不要任何其他文字，格式：
            {"accuracy": "1", "depth": "tie", "citation": "2", "structure": "1",
             "reasons": {"accuracy": "一句话理由", "depth": "...", "citation": "...", "structure": "..."}}
            """;

    /** 写作质量盲判协议（跨系统报告对比用；judge 同 DeepSeek，提示用中文便于逐维论证）。 */
    static final String WRITING_SYSTEM = """
            你是研究报告写作质量评审（盲判）。给定同一研究问题的两份匿名研究报告
            （report1 / report2；编号随机分配），请从六个维度分别判定哪一份更优，
            每个维度给出**基于报告具体内容**的一句话理由（引用正文片段/章节证据，
            禁止空泛套话；理由将用于事后审计）：
            - org_structure：组织与结构（大纲是否贴合问题、层次与衔接是否清晰）
            - language_readability：语言与可读性（流畅度、信息密度、术语准确、不注水）
            - depth_insight：深度与洞察（是否消化材料而非堆砌；有独立分析/量化支撑）
            - citation_discipline：引用纪律（引用是否贴切嵌入、来源类型与可信度、格式一致）
            - honesty_restraint：克制与诚实（不确定处是否如实标注；是否代理论证/以偏概全；
              宁可留白也不编造）
            - overall：整体更优的一份

            每维只允许 "1"（report1 更优）、"2"（report2 更优）或 "tie"。
            只输出 JSON：{"org_structure":"1", ..., "overall":"1",
            "reasons":{"org_structure":"...", ..., "overall":"..."}}
            """;

    private ABJudge() {
    }

    /** 判分结果；null = judge 输出不可解析（调用方跳过该对）。winner ∈ A/B/tie。 */
    public record Result(Map<String, String> winners, Map<String, String> reasons) {
    }

    /** 盲判一对报告。@param swap true = report1 位置实际放 B（物理对调后 judge 无法
     *  从编号推断配置——防"编号恒对应配置"泄露；归因已按 swap 翻转）。 */
    public static Result judge(String query, String reportA, String reportB, boolean swap,
                               JudgeClient judge) {
        JsonNode root = judge.chatJson(BLIND_SYSTEM, buildUser(query, reportA, reportB, swap));
        if (root == null) {
            return null;
        }
        Map<String, String> winners = parseWinners(root, DIMENSIONS, swap);
        if (winners == null) {
            return null;
        }
        Map<String, String> reasons = new LinkedHashMap<>();
        JsonNode r = root.path("reasons");
        for (String dim : DIMENSIONS) {
            reasons.put(dim, r.path(dim).asText(""));
        }
        return new Result(winners, reasons);
    }

    /** 写作质量盲判（跨系统对比：ABJudge 双跑 swap 一致性在调用方聚合）。 */
    public static Result judgeWriting(String query, String reportA, String reportB,
                                      boolean swap, JudgeClient judge) {
        JsonNode root = judge.chatJson(WRITING_SYSTEM, buildUser(query, reportA, reportB, swap));
        if (root == null) {
            return null;
        }
        Map<String, String> winners = parseWinners(root, WRITING_DIMENSIONS, swap);
        if (winners == null) {
            return null;
        }
        Map<String, String> reasons = new LinkedHashMap<>();
        JsonNode r = root.path("reasons");
        for (String dim : WRITING_DIMENSIONS) {
            reasons.put(dim, r.path(dim).asText(""));
        }
        return new Result(winners, reasons);
    }

    /**
     * 解析判分 JSON（纯函数，可单测）：每维 1/2/tie → A/B/tie（已按 swap 归因）；
     * 缺维/坏值 → 返回 null（整条不可信，调用方跳过——防 judge 注水）。
     */
    static Map<String, String> parseWinners(JsonNode root, List<String> dims, boolean swap) {
        Map<String, String> winners = new LinkedHashMap<>();
        for (String dim : dims) {
            String v = root.path(dim).asText("");
            if ("1".equals(v)) {
                winners.put(dim, swap ? "B" : "A");
            } else if ("2".equals(v)) {
                winners.put(dim, swap ? "A" : "B");
            } else if ("tie".equals(v)) {
                winners.put(dim, "tie");
            } else {
                return null; // 缺维/坏值 → 整条不可信，跳过
            }
        }
        return winners;
    }

    /**
     * 盲判输入护栏（每侧上限，仅防御 LLM 上下文超限，不是常规裁剪）。
     *
     * <p>教训（评测实测，2026-09）：早期 6000 字符护栏把"长报告"系统性切成残篇
     * ——跨系统对比时 py 侧报告常 20k+ chars、java 侧 5–16k，双侧同 6000 截断对
     * 长报告信息损失 60%+，判官把残缺误判为质量差（判词出现"被截断/结构碎片化/
     * 缺独立分析"），导致结论系统性偏向短报告。全量喂入后结论即翻转。护栏只应
     * 防极端超长（两侧合计 token 化后接近模型上下文预算），日常不得触发。
     *
     * <p><b>cap 必须足够大：同型缺陷会随报告变长再次触发</b>——cap 提到 30k 后 java 报告长到 55k
     * （q08=55449），又被前缀截断 46%——被砍掉的正是回答问题的核心章节
     * （Synthesis/瓶颈一~三/结论位于 39k–45k），判官据残缺文本写下"未直接回应
     * how far 与 bottlenecks 两问"，而 py 报告 22640 完整可见、其 Bottleneck 1–4
     * 恰在 9k–15k 落于可见区 ⇒ 该题判定失真。
     * <b>根因不是阈值大小，而是"固定阈值 + 每侧独立前缀截断"</b>：只要报告继续变长
     * 就会周期性复发，且长报告被砍、短报告完整 ⇒ 信息损失不对称。
     * 故：cap 提至 60k 覆盖当前最长报告（deepseek-chat 128k 上下文，两侧合计约 30k
     * tokens 仍有余量），并在触顶时由 {@link #warnIfTruncated} 显式告警；
     * 结构性修法（按小节均匀采样或分段判+聚合）见设计待办。
     */
    static final int TRUNCATE_CAP = 60_000;

    /** 组装盲判 user prompt：swap=true 时物理对调文本（report1 段放 B 的报告）。 */
    static String buildUser(String query, String reportA, String reportB, boolean swap) {
        String first = swap ? reportB : reportA;
        String second = swap ? reportA : reportB;
        warnIfTruncated(first, second);
        return "研究问题：%s\n\n---- report1 ----\n%s\n\n---- report2 ----\n%s"
                .formatted(query, ReportText.truncate(first, TRUNCATE_CAP),
                        ReportText.truncate(second, TRUNCATE_CAP));
    }

    /** 任一报告超 cap 即告警：判定将建立在残缺文本上（长报告损失不对称 → 偏向短报告）。 */
    private static void warnIfTruncated(String first, String second) {
        int max = Math.max(first.length(), second.length());
        if (max > TRUNCATE_CAP) {
            System.err.printf("[ABJudge] 警告：报告 %d 字符超上限 %d，将被前缀截断；"
                            + "信息损失对长报告不对称，结论可能失真（提高 TRUNCATE_CAP 或改均匀采样）%n",
                    max, TRUNCATE_CAP);
        }
    }
}
