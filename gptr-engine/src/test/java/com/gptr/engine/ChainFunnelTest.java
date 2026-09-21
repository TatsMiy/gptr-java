package com.gptr.engine;

import com.gptr.engine.write.CitationVerifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路漏斗的两处纯函数单测（2026-09-13）：
 * 引用统计（<b>次数 vs 源数</b>）与"<b>未读来源</b>"集合差集。
 *
  * <p>这两个数字分别用来区分
 * "信源单一的自引刷屏 vs 多源交叉验证"与"归因到未抓页面的 note"。
 */
class ChainFunnelTest {

    // ---------------- 引用统计：次数 vs 源数 ----------------

    @Test
    void citationStatsSeparatesCountFromSources() {
        // 同一 URL 引 3 次 + 另一 URL 引 1 次 → 次数 4、源数 2
        String report = "A [x](https://a.com/p1) B [x](https://a.com/p1) "
                + "C [y](https://b.com/p2) D [x](https://a.com/p1)";
        CitationVerifier.CitationStats s = CitationVerifier.citationStats(report);
        assertEquals(4, s.total(), "总引证次数：同一 URL 重复计入");
        assertEquals(2, s.distinctRaw());
        assertEquals(2, s.distinctCanonical());
    }

    @Test
    void citationStatsMergesCanonicalVariants() {
        // 同一页面的尾斜杠/跟踪参数变体：原文 3 个字符串、canonical 1 个来源
        String report = "https://a.com/p1 https://a.com/p1/ https://a.com/p1?utm_source=x";
        CitationVerifier.CitationStats s = CitationVerifier.citationStats(report);
        assertEquals(3, s.total());
        assertEquals(3, s.distinctRaw(), "按原文是 3 个不同串");
        assertEquals(1, s.distinctCanonical(), "canonical 归并为 1 个来源");
    }

    @Test
    void citationStatsHandlesBlankInput() {
        assertEquals(0, CitationVerifier.citationStats(null).total());
        assertEquals(0, CitationVerifier.citationStats("   ").distinctCanonical());
        assertEquals(0, CitationVerifier.citationStats("无链接的正文").total());
    }

    @Test
    void citedUrlsKeepsExistingDedupeSemantics() {
        // 回归保护：citedUrls 仍是"按原文去重"的列表（授权核验依赖该语义，不可被本次改动影响）
        String report = "https://a.com/p1 https://a.com/p1 https://b.com/p2";
        CitationVerifier v = new CitationVerifier(List.of("https://a.com/p1", "https://b.com/p2"));
        assertEquals(2, v.citedUrls(report).size());
        assertEquals(2, v.verify(report).size());
    }

    // ---------------- 未读来源：集合差集 ----------------

    @Test
    void unreadSourcesIsSetDifference() {
        Set<String> notes = new LinkedHashSet<>(List.of(
                "https://a.com/1", "https://b.com/2", "https://c.com/3"));
        Set<String> fetched = new LinkedHashSet<>(List.of("https://a.com/1"));
        assertEquals(Set.of("https://b.com/2", "https://c.com/3"),
                ResearchEngineImpl.unreadNoteSources(notes, fetched));
    }

    @Test
    void unreadSourcesAllReadOrAllUnread() {
        Set<String> notes = Set.of("https://a.com/1", "https://b.com/2");
        assertTrue(ResearchEngineImpl.unreadNoteSources(notes, notes).isEmpty(), "全部已读 → 空集");
        assertEquals(2, ResearchEngineImpl.unreadNoteSources(notes, Set.of()).size(), "一个没读 → 全集");
    }

    @Test
    void unreadSourcesToleratesNull() {
        assertTrue(ResearchEngineImpl.unreadNoteSources(null, null).isEmpty());
        assertEquals(1, ResearchEngineImpl.unreadNoteSources(Set.of("https://a.com/1"), null).size());
        assertTrue(ResearchEngineImpl.unreadNoteSources(Set.of(), Set.of("https://a.com/1")).isEmpty());
    }

    @Test
    void unreadSourcesRequiresCanonicalizedInputs() {
        // 口径纪律：两侧必须先 canonical 化，否则尾斜杠差异会把"已抓"误判成"未读"
        assertFalse(ResearchEngineImpl.unreadNoteSources(
                        Set.of("https://a.com/p1"), Set.of("https://a.com/p1/")).isEmpty(),
                "未归一化 → 误判为未读");
        assertTrue(ResearchEngineImpl.unreadNoteSources(
                        Set.of(CitationVerifier.canonicalize("https://a.com/p1")),
                        Set.of(CitationVerifier.canonicalize("https://a.com/p1/"))).isEmpty(),
                "归一化后应判为已读");
    }
}
