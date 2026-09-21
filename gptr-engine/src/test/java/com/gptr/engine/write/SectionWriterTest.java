package com.gptr.engine.write;

import com.gptr.engine.epoc.EvidenceNote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SectionWriter 纯逻辑单测：LCS 归节匹配、覆盖校验、节级引用闸门、机械 References。
 * 审计批（2026-09）：F9 括号 URL 保真、F10 空证据节占位。
 */
class SectionWriterTest {

    @Test
    void longestCommonSubstringBasics() {
        assertEquals(0, SectionWriter.longestCommonSubstring("abc", "xyz"));
        assertEquals(3, SectionWriter.longestCommonSubstring("abcdef", "zabcz"));
        assertEquals(8, SectionWriter.longestCommonSubstring("量子计算纠错路线对比", "量子计算纠错路线"));
        assertEquals(0, SectionWriter.longestCommonSubstring("", "a"));
        assertEquals(0, SectionWriter.longestCommonSubstring(null, "a"));
    }

    @Test
    void assignNotesByBestQueryMatch() {
        List<SectionWriter.Section> sections = List.of(
                new SectionWriter.Section("s0", "g", List.of("量子纠错容错路线开销与最新进展")),
                new SectionWriter.Section("s1", "g", List.of("量子退火在组合优化中的工业应用实践")));
        List<EvidenceNote> notes = List.of(
                new EvidenceNote(0, 1, 1, 0, "量子纠错容错路线开销与最新进展调研", "i1", "", "https://a"),
                new EvidenceNote(1, 1, 1, 1, "量子退火在组合优化中的工业应用实践报告", "i2", "", "https://b"),
                new EvidenceNote(2, 1, 1, 2, "深海热泉生态系统物种多样性", "i3", "", "https://c"));
        int[] assigned = new SectionWriter(null, 6000, false).assignNotes(notes, sections);
        assertEquals(0, assigned[0], "q0 note → 节0（LCS ≥8）");
        assertEquals(1, assigned[1], "q1 note → 节1");
        assertEquals(-1, assigned[2], "无匹配 → 未归组");
    }

    // ------------------------------------------------------------------
    // subQueryIdx 硬锚优先 + 越界/基序防御 + 英文模板词不串配
    // ------------------------------------------------------------------

    @Test
    void subQueryIdxAnchorOverridesFuzzyMatch() {
        // 两节 query 共享大量模板文本——若走弱锚会错配；硬锚直归正确节
        List<SectionWriter.Section> sections = List.of(
                new SectionWriter.Section("s0", "g", List.of("What is the role of X"),
                        List.of(0)),
                new SectionWriter.Section("s1", "g", List.of("What is the role of Y"),
                        List.of(1)));
        List<EvidenceNote> notes = List.of(
                new EvidenceNote(0, 1, 1, 0, "What is the role of X in systems", "i0", "", "https://a"),
                new EvidenceNote(1, 1, 1, 1, "What is the role of Y in markets", "i1", "", "https://b"));
        int[] assigned = new SectionWriter(null, 6000, false).assignNotes(notes, sections);
        assertEquals(0, assigned[0], "note.queryIdx=0 → 节0（硬锚）");
        assertEquals(1, assigned[1], "note.queryIdx=1 → 节1（硬锚）");
    }

    @Test
    void outOfRangeOrOneBasedAnchorFallsBackSafely() {
        // 越界锚（3 个子查询却传 5）与 1-based 锚（应为 0 却传 1）必须静默回退，不抛异常
        List<SectionWriter.Section> sections = List.of(
                new SectionWriter.Section("s0", "g", List.of("canberra capital of australia"),
                        List.of(5)),   // 越界锚 → 丢弃
                new SectionWriter.Section("s1", "g", List.of("berlin capital of germany"),
                        List.of(1)));  // 1-based 污染（queryIdx=0 应配节0 但锚说 1）→ 无效锚
        List<EvidenceNote> notes = List.of(
                new EvidenceNote(0, 1, 1, 0,
                        "canberra is the capital city of australia", "i0", "", "https://a"));
        int[] assigned = new SectionWriter(null, 6000, false).assignNotes(notes, sections);
        // queryIdx=0：节0 的锚 5 无效、节1 的锚 1 不匹配 0 → 硬锚全弃 → 弱锚（归一后
        // 与节0 共享 canberra/australia/capital 长公共串）→ 节0
        assertEquals(0, assigned[0], "无效锚回退弱锚且不抛异常: " + java.util.Arrays.toString(assigned));
    }

    @Test
    void englishTemplateWordsNoLongerCauseCrossSectionMismatch() {
        // 英文模板 "What is the " 在归一化（去停用词）后不参与 LCS——两节各自内容词区分
        List<SectionWriter.Section> sections = List.of(
                new SectionWriter.Section("s0", "g",
                        List.of("What is the capital of australia")),
                new SectionWriter.Section("s1", "g",
                        List.of("What is the gdp of germany")));
        List<EvidenceNote> notes = List.of(
                new EvidenceNote(0, 1, 1, 0,
                        "what is the capital city of australia canberra", "i0", "", "https://a"),
                new EvidenceNote(1, 1, 1, 1,
                        "what is the gross domestic product gdp of germany", "i1", "", "https://b"));
        int[] assigned = new SectionWriter(null, 6000, false).assignNotes(notes, sections);
        assertEquals(0, assigned[0], "模板虚词剔除后 note0 归节0");
        assertEquals(1, assigned[1], "note1 归节1（不得因共享 'what is the ' 串配）");
    }

    @Test
    void uncoveredSubQueriesDetected() {
        var writer = new SectionWriter(null, 6000, false);
        List<String> sub = List.of("问题A子查询内容描述", "问题B完全不同的话题");
        List<SectionWriter.Section> sections = List.of(
                new SectionWriter.Section("s", "g", List.of("问题A子查询内容描述")));
        List<String> uncovered = writer.uncoveredSubQueries(sub, sections);
        assertEquals(1, uncovered.size());
        assertTrue(uncovered.get(0).contains("问题B"), "未覆盖子查询应被检出: " + uncovered);
    }

    @Test
    void mechanicalReferencesFromSections() {
        var writer = new SectionWriter(null, 6000, false);
        List<String> mds = List.of(
                "## 甲\n\n论断甲 ([源A](https://a.com/doc)) 与 ([源B](https://b.com)) 引用。\n\n",
                "## 乙\n\n再次引用 ([源A](https://a.com/doc)) 同一链接。\n\n");
        String merged = writer.merge("标题", mds, "- 要点1");
        assertTrue(merged.startsWith("# 标题"), "标题在首");
        assertTrue(merged.contains("- 要点1"), "takeaways 在场");
        int refCount = countOccurrences(merged, "- [");
        assertEquals(2, refCount, "References 机械去重（a.com/doc 与 b.com 各一次）: " + merged);
        int refSection = merged.indexOf("## References");
        assertTrue(refSection > merged.indexOf("## 乙"), "References 在正文后");
        assertTrue(merged.substring(refSection).contains("https://a.com/doc"));
        assertTrue(merged.substring(refSection).contains("https://b.com"));
    }

    @Test
    void sectionGateRejectsForeignUrl() {
        var writer = new SectionWriter(null, 6000, false);
        String text = "论断 ([本节的来源](https://sec-a.com)) 与跨节引用 ([别节的来源](https://sec-b.com))。";
        int unauthorized = writer.checkSection(text, List.of("https://sec-a.com"));
        assertEquals(1, unauthorized, "节外 URL 应被判 unauthorized=1");
        int clean = writer.checkSection("只引用 ([本节的来源](https://sec-a.com))。",
                List.of("https://sec-a.com", "https://sec-b.com"));
        assertEquals(0, clean);
    }

    // ------------------------------------------------------------------
    // 括号 URL 不再被 References 机械生成截断
    // ------------------------------------------------------------------

    @Test
    void extractMarkdownLinksPairsParenthesesInUrls() {
        List<SectionWriter.Link> links = SectionWriter.extractMarkdownLinks(
                "见 ([条目](https://en.wikipedia.org/wiki/Transformer_(deep_learning)))。"
                        + "另有 ([B](https://b.com/x,y)) 说明。");
        assertEquals(2, links.size());
        assertEquals("条目", links.get(0).label());
        assertEquals("https://en.wikipedia.org/wiki/Transformer_(deep_learning)",
                links.get(0).url(), "URL 内括号必须配对保留到完整闭合");
        assertEquals("https://b.com/x,y", links.get(1).url(), "路径逗号是合法字符");
    }

    @Test
    void parenthesizedUrlSurvivesMechanicalReferences() {
        var writer = new SectionWriter(null, 6000, false);
        String md = "## 甲\n\nTransformer 架构演进见 ([条目]"
                + "(https://en.wikipedia.org/wiki/Transformer_(deep_learning)))。\n\n";
        String merged = writer.merge("标题", List.of(md), "- 要点");
        String refs = merged.substring(merged.indexOf("## References"));
        assertTrue(refs.contains("https://en.wikipedia.org/wiki/Transformer_(deep_learning)"),
                "References 必须保留 URL 完整括号: " + refs);
        assertFalse(refs.contains("Transformer_(deep_learning)）"),
                "不得出现括号被截断的畸形串");
    }

    // ------------------------------------------------------------------
    // 0：空证据节不调 LLM（null llm 即哨兵：被调用必 NPE）
    // ------------------------------------------------------------------

    @Test
    void emptyEvidenceSectionWritesHonestPlaceholderWithoutLlm() {
        var writer = new SectionWriter(null, 6000, false);
        SectionWriter.SectionOutcome so = writer.writeSection(
                new SectionWriter.Section("某前沿方向对比", "goal", List.of("q1")),
                List.of(), List.of());
        assertTrue(so.markdown().startsWith("## 某前沿方向对比"),
                "占位节标题层级必须为 ##（与 report-section.user 约定一致）: " + so.markdown());
        assertTrue(so.markdown().contains("本节从略"));
        assertEquals(0, so.unauthorized());
        assertFalse(so.retried());
    }

    @Test
    void emptyEvidenceWithBlankTitleStillSafe() {
        var writer = new SectionWriter(null, 6000, false);
        SectionWriter.SectionOutcome so = writer.writeSection(
                new SectionWriter.Section("", "", List.of()), List.of(), List.of());
        assertTrue(so.markdown().startsWith("## "), "空标题占位也不抛异常");
        assertTrue(so.markdown().contains("本节从略"));
    }

    // ------------------------------------------------------------------
    // 审计修正：writeOutline 契约解析（此前零单测——集成曾被 mock 兜底假绿）
    // ------------------------------------------------------------------

    private static SectionWriter fixedOutlineLlm(String response) {
        return new SectionWriter(new com.gptr.integration.client.LlmClient() {
            @Override
            public String name() {
                return "fixed-outline";
            }

            @Override
            public String chat(String systemPrompt, String userPrompt) {
                return response;
            }

            @Override
            public String chatJson(String systemPrompt, String userPrompt) {
                return response;
            }
        }, 6000, false);
    }

    @Test
    void writeOutlineParsesSectionsWithSubQueryIdx() {
        String out = "{\"title\":\"T\",\"sections\":["
                + "{\"title\":\"s0\",\"goal\":\"g0\",\"queries\":[\"q0a\"],\"subQueryIdx\":[0,2]},"
                + "{\"title\":\"s1\",\"goal\":\"g1\",\"queries\":[\"q1a\"],\"subQueryIdx\":[\"1\"]}]}";
        SectionWriter.OutlineResult r = fixedOutlineLlm(out).writeOutline(
                "q", "state", List.of("q0", "q0a", "q1a"));
        assertTrue(r != null, "合法大纲必须解析成功");
        assertEquals("T", r.title());
        assertEquals(2, r.sections().size());
        assertEquals(List.of(0, 2), r.sections().get(0).subQueryIdx(), "数字 idx 解析");
        assertEquals(List.of(1), r.sections().get(1).subQueryIdx(), "字符串数字 idx 解析");
    }

    @Test
    void writeOutlineRejectsMissingSectionsOrEmpty() {
        // learnings 形态（旧 mock 兜底曾致集成假绿）→ 必须返回 null（调用方回退，测试层显式可见）
        assertTrue(fixedOutlineLlm("{\"learnings\":[{\"insight\":\"x\"}]}")
                .writeOutline("q", "s", List.of("a")) == null,
                "无 sections 的响应 → null（不许误解析）");
        assertTrue(fixedOutlineLlm("{\"title\":\"T\",\"sections\":[]}")
                .writeOutline("q", "s", List.of("a")) == null, "空 sections → null");
        assertTrue(fixedOutlineLlm("纯文本坏输出").writeOutline("q", "s", List.of("a")) == null,
                "非 JSON → null");
        // 超 6 节截断
        StringBuilder many = new StringBuilder("{\"title\":\"T\",\"sections\":[");
        for (int i = 0; i < 9; i++) {
            if (i > 0) {
                many.append(",");
            }
            many.append("{\"title\":\"s").append(i).append("\",\"goal\":\"g\",")
                    .append("\"queries\":[\"q\"],\"subQueryIdx\":[0]}");
        }
        many.append("]}");
        SectionWriter.OutlineResult capped = fixedOutlineLlm(many.toString())
                .writeOutline("q", "s", List.of("q"));
        assertEquals(6, capped.sections().size(), "大纲节数上限 6");
    }

    @Test
    void writeOutlineSkipsBlankTitlesAndKeepsIdx() {
        String out = "{\"title\":\"T\",\"sections\":["
                + "{\"title\":\"\",\"goal\":\"g\",\"queries\":[\"q\"]},"
                + "{\"title\":\"ok\",\"goal\":\"g\",\"queries\":[\"q1\"],\"subQueryIdx\":[7]}]}";
        // 越界 idx 在解析层保留、消费层（assignNotes）静默丢弃——解析不抛异常
        SectionWriter.OutlineResult r = fixedOutlineLlm(out).writeOutline(
                "q", "s", List.of("q", "q1"));
        assertEquals(1, r.sections().size(), "空标题节被跳过");
        assertEquals(List.of(7), r.sections().get(0).subQueryIdx(), "越界 idx 解析层不拦截");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
