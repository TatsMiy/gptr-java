package com.gptr.benchmark.dims;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReportText 单测（C3：句子切分/内联引用提取/归一化包含）。修正基线跑暴露的
 * 小数切分、References 标题变体、URL 提取问题。
 */
class ReportTextTest {

    @Test
    void splitFindsReferencesHeadingVariants() {
        ReportText.Split s1 = ReportText.split(
                "正文内容。\n## References\n1. [x](https://a.com)");
        assertTrue(s1.body().contains("正文内容"));
        assertTrue(s1.references().contains("References"));
        // 四/五级标题变体
        ReportText.Split s2 = ReportText.split("body\n#### References\n1. [x](https://a.com)");
        assertTrue(s2.references().contains("References"), "#### References 应被识别");
        // 无 References 段 → 整篇 body
        ReportText.Split s3 = ReportText.split("只有正文没有来源段");
        assertEquals("", s3.references());
        assertTrue(s3.body().contains("只有正文"));
    }

    @Test
    void sentenceSplitKeepsDecimalsAndExtractsInlineUrls() {
        String text = "长江全长约6300公里，光速为299,792.458公里/秒（[来源](https://a.com/x)）。"
                + "这符合[另一来源](https://b.com/y)的说法。";
        List<ReportText.Sentence> sentences = ReportText.sentences(text, 10);
        assertEquals(2, sentences.size());
        // 第一句含小数不应被切
        assertTrue(sentences.get(0).text().contains("299,792.458"),
                "小数不应被切成两句: " + sentences);
        // 句内引用 URL 提取
        assertEquals(List.of("https://a.com/x"), sentences.get(0).citationUrls());
        assertEquals(List.of("https://b.com/y"), sentences.get(1).citationUrls());
    }

    @Test
    void sentenceWithoutCitationHasEmptyUrls() {
        List<ReportText.Sentence> sentences =
                ReportText.sentences("这是一句没有任何引用的纯观点陈述。", 10);
        assertEquals(1, sentences.size());
        assertTrue(sentences.get(0).citationUrls().isEmpty());
    }

    @Test
    void extractInlineUrlsHandlesParenthesizedPath() {
        List<String> urls = ReportText.extractInlineUrls(
                "维基百科（[条目](https://zh.wikipedia.org/zh-cn/长江_(河流))）说明。");
        assertEquals(List.of("https://zh.wikipedia.org/zh-cn/长江_(河流)"), urls,
                "URL 内部括号应保留、markdown 闭合括号应截断");
    }

    @Test
    void containsNormalizedIgnoresPunctuationCase() {
        assertTrue(ReportText.containsNormalized(
                "长江是中国最长的河流。", "长江是中国最长的河流"));
        assertTrue(ReportText.containsNormalized(
                "The capital is Canberra, Australia.", "canberra australia"));
        assertFalse(ReportText.containsNormalized("报告内容", "完全不相关的内容"));
        assertTrue(ReportText.containsNormalized("任意报告", ""), "空 evidence 视为通过");
    }

    // ------------------------------------------------------------------
    // 链接感知切句（句读后紧邻链接回流 / 首句孤立前向吸收 / URL 句点不切）
    // ------------------------------------------------------------------

    @Test
    void cnLinkAfterPunctMergesIntoPreviousSentence() {
        // 句读后紧跟带外层括号的链接：纯链接残片必须回流进前句引用
        String text = "这是一个已经验证过的事实结论。([来源](https://e.com/u))";
        List<ReportText.Sentence> sentences = ReportText.sentences(text, 10);
        assertEquals(1, sentences.size(), "纯链接残片不得自成一句: " + sentences);
        assertTrue(sentences.get(0).text().contains("已经验证过的事实结论"));
        assertEquals(List.of("https://e.com/u"), sentences.get(0).citationUrls(),
                "句读后紧邻链接回流到前句");
    }

    @Test
    void englishLinkAfterPunctMergesIntoPreviousSentence() {
        String text = "This is a verified factual statement. [Source](https://e.com/s)";
        List<ReportText.Sentence> sentences = ReportText.sentences(text, 10);
        assertEquals(1, sentences.size());
        assertEquals(List.of("https://e.com/s"), sentences.get(0).citationUrls());
        assertTrue(sentences.get(0).text().startsWith("This is a verified factual statement"),
                "回流句文本为论断句本身: " + sentences.get(0).text());
    }

    @Test
    void urlPathVersionDotsDoNotSplitSentence() {
        String text = "方案详情见 ([文档](https://s.com/docs/v1.0/guide))。"
                + "后续内容继续展开并给出完整结论性说明。";
        List<ReportText.Sentence> sentences = ReportText.sentences(text, 10);
        assertEquals(2, sentences.size(), "链接内 /v1.0/ 不得触发句界: " + sentences);
        assertEquals(List.of("https://s.com/docs/v1.0/guide"), sentences.get(0).citationUrls());
        assertTrue(sentences.get(0).text().contains("v1.0"),
                "URL 原文必须完整保留在句文本中");
    }

    @Test
    void orphanLeadingLinkIsAbsorbedForward() {
        // 报告首 token 即链接且紧跟句读（无前句可回流）→ 前向吸收进首个正文句，不抛异常
        String text = "[Source](https://e.com/o)。这是正文第一句话,内容足够长超过十二个字符。";
        List<ReportText.Sentence> sentences = ReportText.sentences(text, 10);
        assertEquals(1, sentences.size());
        assertEquals(List.of("https://e.com/o"), sentences.get(0).citationUrls(),
                "首句孤立链接并入其后的正文句引用");
    }

    @Test
    void linkOnlyReportBecomesSingleSentence() {
        // 极端：全文只有一个孤立链接（无正文可吸收）→ 单独成句而非崩溃/丢失
        String text = "([Source](https://e.com/only))";
        List<ReportText.Sentence> sentences = ReportText.sentences(text, 10);
        assertEquals(1, sentences.size());
        assertEquals(List.of("https://e.com/only"), sentences.get(0).citationUrls());
    }

    @Test
    void midSentenceLinkStaysInSameSentence() {
        String text = "该协议由 ([规范](https://e.com/spec)) 定义。后续无引用句独立成句保留统计。";
        List<ReportText.Sentence> sentences = ReportText.sentences(text, 10);
        assertEquals(2, sentences.size());
        assertEquals(List.of("https://e.com/spec"), sentences.get(0).citationUrls());
        assertTrue(sentences.get(1).citationUrls().isEmpty());
    }

    // ------------------------------------------------------------------
    // 编号化报告：正文只剩 [n]，须经参考文献编号表反解才能取到逐句来源
    // ------------------------------------------------------------------

    @Test
    void numberedMarkersResolveToUrls() {
        String text = "论断甲已有证据 [1](#ref-1)。论断乙另有一源 [2](#ref-2)。";
        List<ReportText.Sentence> sentences = ReportText.sentences(text, 10,
                java.util.Map.of(1, "https://a.com/x", 2, "https://b.com/y"));
        assertEquals(2, sentences.size());
        assertEquals(List.of("https://a.com/x"), sentences.get(0).citationUrls());
        assertEquals(List.of("https://b.com/y"), sentences.get(1).citationUrls());
    }

    @Test
    void numberedMarkersWithoutIndexYieldNoUrls() {
        List<ReportText.Sentence> sentences =
                ReportText.sentences("论断甲已有证据 [1](#ref-1)。", 10);
        assertEquals(1, sentences.size());
        assertTrue(sentences.get(0).citationUrls().isEmpty(),
                "没有编号表时不猜来源: " + sentences);
    }

    @Test
    void danglingNumberContributesNoUrl() {
        List<ReportText.Sentence> sentences = ReportText.sentences(
                "论断甲引用了表外编号 [9](#ref-9)。", 10, java.util.Map.of(1, "https://a.com/x"));
        assertEquals(1, sentences.size());
        assertTrue(sentences.get(0).citationUrls().isEmpty(), "表外编号不得映射到别的来源");
    }
}
