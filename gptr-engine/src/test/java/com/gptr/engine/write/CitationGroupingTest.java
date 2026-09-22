package com.gptr.engine.write;

import com.gptr.engine.budget.Budgets;
import com.gptr.engine.epoc.EvidenceNote;
import com.gptr.integration.client.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
  * 证据目录 / 直引解析 / 多对多归节。
 *
 * <p>这三处是"错位型空证据节占位"的根治点：证据目录让大纲**看见**证据，直引让归节有明确依据，
 * 未引者入兜底节保证证据零丢失。
 */
class CitationGroupingTest {

    // ---------------------------------------------------------------
    // A. 证据目录
    // ---------------------------------------------------------------

    private static EvidenceNote note(int idx, String queryText, String insight) {
        return new EvidenceNote(idx, 1, 1, 0, queryText, insight, "", "https://e.com/" + idx);
    }

    @Test
    void buildEvidenceIndexGroupsByQueryAndRoundRobins() {
        List<EvidenceNote> notes = new ArrayList<>();
        notes.add(note(0, "方向A", "A 的第一条证据，内容足够长以便被摘要截断逻辑处理。"));
        notes.add(note(1, "方向A", "A 的第二条证据。"));
        notes.add(note(2, "方向A", "A 的第三条证据。"));
        notes.add(note(3, "方向B", "B 的唯一一条证据。"));
        String idx = SectionWriter.buildEvidenceIndex(notes, 12000);
        assertTrue(idx.contains("[0]"), "含 idx 编号：" + idx);
        assertTrue(idx.contains("[3]"));
        assertTrue(idx.contains("方向A") && idx.contains("方向B"), "按查询方向分组");
        // 轮转：方向B 的唯一一条应紧跟方向A 的第一条之后（而不是排在 A 的三条之后）
        int posA0 = idx.indexOf("[0]");
        int posB0 = idx.indexOf("[3]");
        int posA1 = idx.indexOf("[1]");
        assertTrue(posB0 < posA1, "轮转取用：B 组证据先于 A 组的第二条出现\n" + idx);
        assertTrue(posA0 < posB0);
    }

    @Test
    void buildEvidenceIndexRespectsBudgetAndReportsOmissions() {
        List<EvidenceNote> notes = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            notes.add(note(i, "方向", "这是一条用于填充预算的证据文本，长度适中不短也不长。"));
        }
        String idx = SectionWriter.buildEvidenceIndex(notes, 600);
        assertTrue(idx.length() < 900, "预算内（含省略提示），实际 " + idx.length());
        assertTrue(idx.contains("more evidence omitted"), "尾部注明省略条数：" + idx);
        assertFalse(idx.contains("[49]"), "远尾部的证据不该出现");
    }

    @Test
    void buildEvidenceIndexHandlesEmptyAndBlank() {
        assertEquals("(no evidence collected)", SectionWriter.buildEvidenceIndex(List.of(), 12000));
        assertEquals("(no evidence collected)", SectionWriter.buildEvidenceIndex(null, 12000));
        List<EvidenceNote> blank = List.of(note(0, "", "insight 存在但查询文本缺失"));
        assertTrue(SectionWriter.buildEvidenceIndex(blank, 12000).contains("(unknown direction)"),
                "空 queryText → 归入 unknown 方向组");
    }

    // ---------------------------------------------------------------
    // 预算语义（上限）与统计
    // ---------------------------------------------------------------

    @Test
    void evidenceIndexStatsReportsTruncation() {
        List<EvidenceNote> small = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            small.add(note(i, "方向", "短证据 " + i));
        }
        SectionWriter.EvidenceIndexStats ok = SectionWriter.buildEvidenceIndexWithStats(small, 40000);
        assertEquals(3, ok.total());
        assertEquals(3, ok.included(), "上限充足 → 全量收下（不补齐也不截断）");
        assertEquals(0, ok.omitted(), "零截断");
        assertFalse(ok.text().contains("omitted"), "无省略提示");

        List<EvidenceNote> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(note(i, "方向", "这是一条用于填充上限的证据文本，长度适中不短也不长。"));
        }
        SectionWriter.EvidenceIndexStats capped =
                SectionWriter.buildEvidenceIndexWithStats(many, 4000);
        assertEquals(200, capped.total());
        assertTrue(capped.omitted() > 0, "超上限 → 有条目被截断，实际 omitted=" + capped.omitted());
        assertEquals(200, capped.included() + capped.omitted(), "计入 + 省略 = 总数（统计自洽）");
        assertTrue(capped.text().contains("more evidence omitted"), "尾部注明省略条数");
    }

    @Test
    void defaultBudgetFitsRealisticEvidenceVolume() {
                // 依据：实测 195 条 ≈21k 字符，12000 时截断 31%；默认 40000 应能全装。
        // 假证据长度按真实量级（~110 字符/条）构造，否则总量对不上会得出错误结论。
        String longInsight = "这是一条用来模拟真实证据摘要长度的文本，包含具体的数据与结论描述，"
                + "长度约在一百二十字符左右，与 Y 臂直通 note 的 insight 字段量级相当，"
                + "用于验证默认上限能容纳真实规模的证据集合而不发生截断。";
        List<EvidenceNote> notes = new ArrayList<>();
        for (int i = 0; i < 195; i++) {
            notes.add(note(i, "方向" + (i % 5), longInsight));
        }
        SectionWriter.EvidenceIndexStats stats =
                SectionWriter.buildEvidenceIndexWithStats(notes, 0);   // 0 → 默认上限
        assertEquals(0, stats.omitted(), "195 条证据在默认上限下零截断");
        assertTrue(stats.chars() > 12000,
                "内容确实超过旧默认 12000，证明修复必要：chars=" + stats.chars());
        assertTrue(stats.chars() < Budgets.defaults().writing().evidenceIndexMaxChars(),
                "且仍在新上限内：chars=" + stats.chars());
    }

    @Test
    void citationStatsFlagsEmptySectionsAndSharedNotes() {
        List<EvidenceNote> notes = List.of(note(0, "q", "e0"), note(1, "q", "e1"),
                note(2, "q", "e2"));
        List<SectionWriter.Section> sections = List.of(
                section("S1", List.of(0, 1)),
                section("S2", List.of(1)),      // note1 被两节共享
                section("S3", List.of()));      // 空引节 → 占位前兆
        SectionWriter.CitationStats cs = SectionWriter.groupByCitationWithStats(notes, sections);
        assertEquals(1, cs.emptySections(), "空引节计数 = 占位前兆");
        assertEquals(1, cs.sharedNotes(), "被 ≥2 节引用的证据数");
        assertEquals(1, cs.fallbackNotes(), "note2 未被引用");
        assertEquals(0, cs.droppedIdx());
        assertEquals(List.of(2), cs.grouped().get(3), "兜底组仍是最后一组");
    }

    @Test
    void indexSummaryFlattensWhitespaceAndTruncates() {
        assertEquals("a b c", SectionWriter.indexSummary("  a\n\n b\t c  "));
        String longText = "x".repeat(300);
        String s = SectionWriter.indexSummary(longText);
        assertEquals(Budgets.defaults().writing().indexSummaryChars() + 1, s.length(),
                "截断到上限 + 省略号");
        assertTrue(s.endsWith("…"));
    }

    // ---------------------------------------------------------------
    // B. 直引解析（容错）
    // ---------------------------------------------------------------

    @Test
    void parseEvidenceIdxDedupsKeepsOrderAndRejectsBadValues() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = mapper.createObjectNode();
        var arr = node.putArray("evidenceIdx");
        arr.add(3);
        arr.add(1);
        arr.add(3);          // 重复 → 去重
        arr.add(-2);         // 负值 → 剔除
        arr.add("5");        // 数字文本 → 接受
        arr.add("abc");      // 非数字文本 → 剔除
        arr.add(7.5);        // 非整数 → 剔除
        assertEquals(List.of(3, 1, 5), SectionWriter.parseEvidenceIdx(node),
                "去重保序 + 只接受非负整数");
    }

    @Test
    void parseEvidenceIdxMissingFieldIsEmpty() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertTrue(SectionWriter.parseEvidenceIdx(mapper.createObjectNode()).isEmpty(),
                "缺字段 → 空（该节走兜底，不整条拒收）");
        var wrongType = mapper.createObjectNode();
        wrongType.put("evidenceIdx", "not-an-array");
        assertTrue(SectionWriter.parseEvidenceIdx(wrongType).isEmpty());
    }

    // ---------------------------------------------------------------
    // C. 直引归节（多对多 + 兜底）
    // ---------------------------------------------------------------

    private static SectionWriter.Section section(String title, List<Integer> evidenceIdx) {
        return new SectionWriter.Section(title, "goal", List.of("q"), List.of(), evidenceIdx);
    }

    @Test
    void groupByCitationAllowsManyToMany() {
        List<EvidenceNote> notes = List.of(note(0, "q", "e0"), note(1, "q", "e1"),
                note(2, "q", "e2"));
        // 一条证据被两节同时引用（WebWeaver 实证的常见形态）
        List<SectionWriter.Section> sections = List.of(
                section("S1", List.of(0, 1)),
                section("S2", List.of(1, 2)));
        List<List<Integer>> grouped = SectionWriter.groupByCitation(notes, sections);
        assertEquals(3, grouped.size(), "节数 + 1（末尾兜底组）");
        assertEquals(List.of(0, 1), grouped.get(0));
        assertEquals(List.of(1, 2), grouped.get(1), "note1 同时归两节");
        assertTrue(grouped.get(2).isEmpty(), "全部被引用 → 兜底组为空");
    }

    @Test
    void groupByCitationOutOfRangeGoesToFallback() {
        List<EvidenceNote> notes = List.of(note(0, "q", "e0"), note(1, "q", "e1"));
        List<SectionWriter.Section> sections = List.of(
                section("S1", List.of(0)),
                section("S2", List.of(99, -1)));   // 越界/负值 → 静默丢弃
        List<List<Integer>> grouped = SectionWriter.groupByCitation(notes, sections);
        assertEquals(List.of(0), grouped.get(0));
        assertTrue(grouped.get(1).isEmpty(), "坏值不进任何节（且不抛异常）");
        assertEquals(List.of(1), grouped.get(2), "未被引用的 note 落入兜底组（证据零丢失）");
    }

    @Test
    void groupByCitationEmptyCitationPutsEverythingInFallback() {
        List<EvidenceNote> notes = List.of(note(0, "q", "e0"), note(1, "q", "e1"));
        List<SectionWriter.Section> sections = List.of(section("S1", List.of()),
                section("S2", List.of()));
        List<List<Integer>> grouped = SectionWriter.groupByCitation(notes, sections);
        assertTrue(grouped.get(0).isEmpty() && grouped.get(1).isEmpty(),
                "直引为空的节 → 空证据 → 上层走占位（不再文本匹配瞎猜）");
        assertEquals(List.of(0, 1), grouped.get(2));
    }

    // ---------------------------------------------------------------
    // D. outline prompt 注入
    // ---------------------------------------------------------------

    @Test
    void outlinePromptInjectsEvidenceIndex() {
        final String[] seen = {null};
        LlmClient spy = new LlmClient() {
            @Override
            public String name() {
                return "spy";
            }

            @Override
            public String chat(String system, String user) {
                seen[0] = user;
                return "{\"title\":\"T\",\"sections\":[{\"title\":\"S\",\"goal\":\"g\","
                        + "\"queries\":[\"q\"],\"subQueryIdx\":[0],\"evidenceIdx\":[2,5]}]}";
            }

            @Override
            public String chatJson(String system, String user) {
                return chat(system, user);
            }

            @Override
            public double lastCallCostUsd() {
                return 0;
            }
        };
        SectionWriter w = new SectionWriter(spy, 6000, false, 6);
        SectionWriter.OutlineResult r = w.writeOutline("问题", "状态", List.of("子查询"),
                "[0] 证据甲\n[2] 证据乙\n");
        assertFalse(seen[0].contains("{evidenceIndex}"), "占位符必须被替换");
        assertTrue(seen[0].contains("[0] 证据甲"), "目录正文注入 prompt");
        assertTrue(seen[0].contains("evidenceIdx"), "prompt 要求 LLM 输出直引下标");
        assertEquals(List.of(2, 5), r.sections().get(0).evidenceIdx(), "直引下标被解析进 Section");
    }

    @Test
    void outlineWithoutEvidenceIndexKeepsLegacyBehaviour() {
        final String[] seen = {null};
        LlmClient spy = new LlmClient() {
            @Override
            public String name() {
                return "spy";
            }

            @Override
            public String chat(String system, String user) {
                seen[0] = user;
                return "{\"title\":\"T\",\"sections\":[{\"title\":\"S\",\"goal\":\"g\","
                        + "\"queries\":[\"q\"],\"subQueryIdx\":[0]}]}";
            }

            @Override
            public String chatJson(String system, String user) {
                return chat(system, user);
            }

            @Override
            public double lastCallCostUsd() {
                return 0;
            }
        };
        SectionWriter w = new SectionWriter(spy, 6000, false, 6);
        SectionWriter.OutlineResult r = w.writeOutline("问题", "状态", List.of("子查询"));
        assertTrue(seen[0].contains("(no evidence index available)"), "无目录 → 明确文案");
        assertTrue(r.sections().get(0).evidenceIdx().isEmpty(), "旧 schema（无 evidenceIdx）→ 空列表");
    }
}
