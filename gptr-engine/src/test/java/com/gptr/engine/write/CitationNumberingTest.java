package com.gptr.engine.write;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CitationNumbering 单测：编号折叠、复用计数、旧参考文献段剥离。
 */
class CitationNumberingTest {

    @Test
    void foldsInlineLinkAndAppendsNumberedTable() {
        CitationNumbering.Result r = CitationNumbering.apply(
                "## 甲\n\n论断 ([来源](https://a.com/x)) 成立。\n");
        assertTrue(r.markdown().contains("论断 [1](#ref-1) 成立。"),
                "外层括号随链接一起折叠: " + r.markdown());
        assertTrue(r.markdown().contains("1. <a id=\"ref-1\"></a>[来源](https://a.com/x)"),
                "参考文献条目: " + r.markdown());
        assertFalse(r.markdown().substring(0, r.markdown().indexOf("## References"))
                .contains("https://"), "正文不得残留 URL");
        assertEquals(1, r.occurrences());
        assertEquals(0, r.reused());
    }

    @Test
    void repeatedSourceSharesOneNumber() {
        CitationNumbering.Result r = CitationNumbering.apply(
                "甲 ([源](https://a.com/x)) 与乙 ([源](https://a.com/x/)) 同源。\n");
        assertEquals(1, r.refs().size(), "同一来源（尾斜杠归一后）只占一条: " + r.refs());
        assertEquals(2, r.occurrences());
        assertEquals(1, r.reused(), "第二次是复用");
    }

    @Test
    void existingReferencesSectionIsReplacedNotDuplicated() {
        String withOldRefs = "## 甲\n\n论断 ([来源](https://a.com/x)) 成立。\n\n"
                + "## References\n\n- [模型自己写的旧条目](https://old.com/1)\n";
        CitationNumbering.Result r = CitationNumbering.apply(withOldRefs);
        assertEquals(1, countOccurrences(r.markdown(), "## References"), "只能有一段: " + r.markdown());
        assertFalse(r.markdown().contains("old.com"), "旧表内容不得残留: " + r.markdown());
        assertEquals(1, r.refs().size());
    }

    @Test
    void noLinkKeepsInputUnchanged() {
        String plain = "## 甲\n\n这一段没有任何引用。\n";
        CitationNumbering.Result r = CitationNumbering.apply(plain);
        assertEquals(plain, r.markdown());
        assertTrue(r.refs().isEmpty());
        assertEquals(0, r.occurrences());
    }

    @Test
    void existingReferenceEntryTextIsPreserved() {
        String pyStyle = "## Abstract\n\n发现 ([Zhao et al., 2026](https://arxiv.org/abs/2602.22769)) 成立。\n\n"
                + "## References\n\n"
                + "1. Zhao, Y., Yuan, B. (2026). AMA-Bench: Evaluating Long-Horizon Memory. arXiv. "
                + "[https://arxiv.org/abs/2602.22769](https://arxiv.org/abs/2602.22769)\n";
        CitationNumbering.Result r = CitationNumbering.apply(pyStyle);
        assertTrue(r.markdown().contains("AMA-Bench"), "原条目描述应被沿用: " + r.markdown());
        assertFalse(r.markdown().contains("[Zhao et al., 2026](https://arxiv.org"),
                "不得退回正文 label: " + r.markdown());
        assertEquals(1, r.refs().size());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }
}
