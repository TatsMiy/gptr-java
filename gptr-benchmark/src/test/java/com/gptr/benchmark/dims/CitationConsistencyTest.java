package com.gptr.benchmark.dims;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D1 口径单测：编号闭环（dangling）与表内多余条目（uncitedRefs）各自成数，
 * 且对"正文行内链接、参考文献带编号"的形态不得把全部条目误判为未引用。
 */
class CitationConsistencyTest {

    private static final String NUMBERED_REFS = "## References\n\n"
            + "1. <a id=\"ref-1\"></a>[甲](https://a.com/x)\n"
            + "2. <a id=\"ref-2\"></a>[乙](https://b.com/y)\n"
            + "3. <a id=\"ref-3\"></a>[丙](https://c.com/z)\n";

    @Test
    void numberedClosureWithOneUncitedRefEntry() {
        String report = "论断甲 [1](#ref-1)。论断乙 [2](#ref-2)。\n\n" + NUMBERED_REFS;
        CitationConsistency.Result r = CitationConsistency.calculate(report);
        assertEquals(2, r.inTextCitations(), "两处编号引用");
        assertEquals(0, r.dangling());
        assertEquals(1, r.uncitedRefs(), "第 3 条从未被正文引用");
        assertEquals(1.0, r.consistency());
    }

    @Test
    void danglingNumberBreaksConsistency() {
        String report = "论断甲 [1](#ref-1)。论断乙引了表外编号 [9](#ref-9)。\n\n" + NUMBERED_REFS;
        CitationConsistency.Result r = CitationConsistency.calculate(report);
        assertEquals(2, r.inTextCitations());
        assertEquals(1, r.dangling());
        assertEquals(0.5, r.consistency(), "2 处里 1 处悬空");
    }

    @Test
    void inlineFormCountsUncitedRefsByUrl() {
        // 正文是行内链接（未编号形态），参考文献却带编号——不得把 3 条全判为未引用
        String report = "论断 ([甲](https://a.com/x))。\n\n" + NUMBERED_REFS;
        CitationConsistency.Result r = CitationConsistency.calculate(report);
        assertEquals(1, r.inTextCitations(), "行内引用按出现次数计");
        assertEquals(0, r.dangling(), "没有编号标记就没有悬空");
        assertEquals(2, r.uncitedRefs(), "只有被引用的那条不算未引用");
        assertEquals(1.0, r.consistency());
    }

    @Test
    void blankReportIsTriviallyConsistent() {
        CitationConsistency.Result r = CitationConsistency.calculate("  ");
        assertEquals(0, r.inTextCitations());
        assertEquals(1.0, r.consistency());
    }

    @Test
    void numberIsSharedAcrossRepeatedCitations() {
        String report = "甲 [1](#ref-1)。再次用到 [1](#ref-1)。\n\n"
                + "## References\n\n1. <a id=\"ref-1\"></a>[甲](https://a.com/x)\n";
        CitationConsistency.Result r = CitationConsistency.calculate(report);
        assertEquals(2, r.inTextCitations(), "同一编号出现两次计两处");
        assertEquals(0, r.uncitedRefs());
        assertTrue(r.consistency() == 1.0);
    }
}
