package com.gptr.benchmark.dims;

import com.fasterxml.jackson.databind.JsonNode;
import com.gptr.benchmark.dataset.BenchmarkItem;
import com.gptr.benchmark.llm.JudgeClient;

/**
 * D3 客观题准确率（两步判定，根治"judge 与被测同源 + 常识题"的自证）。
 *
 * <p>原缺陷：judge 一次调用直接判 correct——对训练常识题，judge 凭自身知识即可判对，
 * 报告可被完全忽略；evidence 校验只证明引文在报告中，挡不住"常识选句"。
 *
 * <p>两步判定：
 * <ol>
 *   <li><b>judge1（抽取）</b>：不给 gold，只问"报告给出的答案是什么"并【逐字摘录】到
 *       {@code reportedAnswer}；随后<b>机器校验</b> reportedAnswer 真实含于报告——
 *       摘不出来 = 报告根本没答该题 = incorrect。judge 无法用常识补全答案。</li>
 *   <li><b>judge2（等价）</b>：输入只有 {@code gold + reportedAnswer}（<b>无报告</b>），
 *       判二者是否等价——纯语义比较，常识无害，且报告已不在场无作弊面。</li>
 * </ol>
 */
public final class AnswerAccuracyJudge {

    private static final String EXTRACT_SYSTEM = """
            你是研究质量评测员。给定研究问题与一份研究报告，请回答：
            1. 报告是否明确给出了该问题的答案？（answerable: true/false）
            2. 若给出，将报告中【逐字摘录】承载答案的原句到 reportedAnswer
               （必须与报告完全一致，可含标点；不得改写、不得用你自己的知识补全；
               报告中找不到 → reportedAnswer 写空字符串）。
            只输出 JSON：
            {"answerable": true 或 false, "reportedAnswer": "报告原句（逐字摘录）"}
            """;

    /** 判定协议（命题极性规则——实体出现 ≠ 断言一致）。
     *  包可见：MicroGold D3 自检复用同一 prompt（防漂移）。 */
    static final String VERDICT_SYSTEM = """
            你是答案判定器（三分类，对标 wrong_stale 语义）。给定：
            - 标准答案（gold，当前正确事实）
            - 过时答案白名单（superseded，历史上曾流传/上一版本的答案；可为空）
            - 候选答案（reportedAnswer，逐字摘录自研究报告）
            判定候选的主断言属于：
            - correct：候选【主断言】与 gold 一致（允许等价表述、单位换算、中英互译；
              数值/单位/对象等价即可）
            - stale：候选给出的是 superseded 白名单中的过时/旧版答案（如旧测量值、上一届得主）
            - incorrect：其他（未答出、答错、无关）
            极性规则（重要）：候选含否定/转折标记时（中文：并非/不是/没有/并未/否认/
            未授予/不再/不/但/然而/据考证…；英文：not/never/did not/was not/no/denied/
            although/however…），必须先判定候选的断言方向：否定或反驳 gold 的候选
            = incorrect——关键词/实体出现（如 gold 人名）不代表断言一致。
            注意：superseded 为空时不可能出现 stale（一律 incorrect）。
            只输出 JSON：{"verdict": "correct" 或 "stale" 或 "incorrect"}
            """;

    private AnswerAccuracyJudge() {
    }

    /** 判分结果；judge 输出不可解析返回 null（调用方跳过）。verdict ∈ correct/stale/incorrect。 */
    public record Verdict(String verdict, Boolean answerable, String reportedAnswer,
                          Boolean reportedInReport) {

        public boolean isCorrect() {
            return "correct".equals(verdict);
        }

        public boolean isStale() {
            return "stale".equals(verdict);
        }
    }

    public static Verdict judge(BenchmarkItem item, String report, JudgeClient judge) {
        // judge1：抽取报告给出的答案（无 gold）
        String extractUser = "研究问题：%s\n\n研究报告：\n%s"
                .formatted(item.query(), ReportText.truncate(report, 6000));
        JsonNode root1 = judge.chatJson(EXTRACT_SYSTEM, extractUser);
        if (root1 == null || !root1.path("answerable").isBoolean()) {
            return null;
        }
        boolean answerable = root1.path("answerable").asBoolean();
        String reportedAnswer = root1.path("reportedAnswer").asText("").trim();
        // 机器校验：reportedAnswer 必须真实含于报告（judge 无法用常识编造）
        boolean inReport = !reportedAnswer.isEmpty()
                && ReportText.containsNormalized(report, reportedAnswer);
        if (!answerable || !inReport) {
            // 报告没答（或 judge 摘录失败）：incorrect，无需 judge2
            return new Verdict("incorrect", answerable, reportedAnswer, inReport);
        }
        // judge2：三分类 verdict（gold + superseded vs reportedAnswer；无报告在场）
        String superseded = item.superseded() == null || item.superseded().isBlank()
                ? "(无)" : item.superseded();
        String verdictUser = "标准答案（gold）：%s\n\n过时答案白名单（superseded）：%s\n\n候选答案（reportedAnswer）：%s"
                .formatted(item.gold(), superseded, reportedAnswer);
        JsonNode root2 = judge.chatJson(VERDICT_SYSTEM, verdictUser);
        if (root2 == null || !root2.path("verdict").isTextual()) {
            return null;
        }
        String verdict = root2.path("verdict").asText();
        if (!"correct".equals(verdict) && !"stale".equals(verdict)) {
            verdict = "incorrect";
        }
        return new Verdict(verdict, true, reportedAnswer, true);
    }

    /** H2（A/B 增量基线）：无研究的直接作答文本 → 三分类 verdict（跳过 judge1 抽取）。 */
    public static Verdict judgeDirect(BenchmarkItem item, String directAnswer, JudgeClient judge) {
        String answer = directAnswer == null ? "" : directAnswer.trim();
        if (answer.isEmpty()) {
            return new Verdict("incorrect", false, "", false);
        }
        String superseded = item.superseded() == null || item.superseded().isBlank()
                ? "(无)" : item.superseded();
        String verdictUser = "标准答案（gold）：%s\n\n过时答案白名单（superseded）：%s\n\n候选答案（reportedAnswer）：%s"
                .formatted(item.gold(), superseded, answer);
        JsonNode root2 = judge.chatJson(VERDICT_SYSTEM, verdictUser);
        if (root2 == null || !root2.path("verdict").isTextual()) {
            return null;
        }
        String verdict = root2.path("verdict").asText();
        if (!"correct".equals(verdict) && !"stale".equals(verdict)) {
            verdict = "incorrect";
        }
        return new Verdict(verdict, true, answer, true);
    }
}
