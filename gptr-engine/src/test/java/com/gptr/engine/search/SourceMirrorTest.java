package com.gptr.engine.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 同源镜像判定单测。用例全部取自 q08 实测的真实检索结果（2026-09-13），
 * 既覆盖"应当合并"，也覆盖"绝不能误合并"的方向。
 *
 * <p>断言统一用 {@link SourceMirror#mirrorKey} 表达"是否同源"——生产侧只暴露去重键，
 * 不单独提供 {@code isMirror}（无人调用的 API 是维护负担，已移除）。
 * 两个私有 helper 让断言仍能读成"是/不是镜像"。
 */
class SourceMirrorTest {

    /** 同一篇文章的多个入口：去重键必须相等（相等才会在取名时只占一个配额）。 */
    private static void assertMirror(String a, String b) {
        assertEquals(SourceMirror.mirrorKey(a), SourceMirror.mirrorKey(b),
                "应判为同一篇文章的镜像入口: " + a + " vs " + b);
    }

    /** 不同来源：去重键必须不等 —— 误合并会让抓取配额少抓真实来源。 */
    private static void assertNotMirror(String a, String b) {
        assertNotEquals(SourceMirror.mirrorKey(a), SourceMirror.mirrorKey(b),
                "不得判为同一篇: " + a + " vs " + b);
    }

    @Test
    void mergesSameArticleAcrossSubdomains() {
        // q08 组0 的三条命中实为同一篇 163 文章（共享文号 KN6E4VRB0511AQHO）
        String a = "https://c.m.163.com/news/a/KN6E4VRB0511AQHO.html";
        String b = "https://www.163.com/dy/article/KN6E4VRB0511AQHO.html";
        String c = "https://m.163.com/dy/article/KN6E4VRB0511AQHO.html";
        assertMirror(a, b);
        assertMirror(a, c);
    }

    @Test
    void mergesCsdnDesktopAndMobileByArticleId() {
        assertMirror("https://blog.csdn.net/weixin_55357163/article/details/161971743",
                "https://m.blog.csdn.net/weixin_55357163/article/details/161971743");
    }

    @Test
    void mergesSamePaperAcrossViews() {
        // 同一篇 BAAI 论文的 trends 页与 paper 页：判为同源（省配额），取舍见类注释
        assertMirror("https://hub.baai.ac.cn/trends/ccf87ec2-2fb7-4b6f-97b2-8cf34a915251",
                "https://hub.baai.ac.cn/paper/ccf87ec2-2fb7-4b6f-97b2-8cf34a915251");
    }

    @Test
    void keepsDifferentArticlesApart() {
        // 同一站点的不同文章：ID 不同 → 不得合并
        assertNotMirror("https://juejin.cn/post/7650719111397261354",
                "https://juejin.cn/post/7682190370524725258");
        assertNotMirror("https://blog.csdn.net/df2209/article/details/162977994",
                "https://blog.csdn.net/2401_84149564/article/details/154013549");
        assertNotMirror("https://arxiv.org/abs/2602.04449",
                "https://arxiv.org/abs/2602.02619");
    }

    @Test
    void doesNotMergeAcrossSites() {
        assertNotMirror("https://news.qq.com/rain/a/20260304A05I4H00",
                "https://c.m.163.com/news/a/KN6E4VRB0511AQHO.html");
    }

    @Test
    void bodyOfHandlesMultiLevelSuffix() {
        assertEquals("baai.ac.cn",
                SourceMirror.bodyOf("https://hub.baai.ac.cn/paper/cf5113a3-31b1-480e-8c38-8186c0feda55"));
        assertEquals("163.com",
                SourceMirror.bodyOf("https://c.m.163.com/news/a/KN6E4VRB0511AQHO.html"));
        assertEquals("csdn.net",
                SourceMirror.bodyOf("https://m.blog.csdn.net/x/article/details/161971743"));
        assertEquals("juejin.cn",
                SourceMirror.bodyOf("https://juejin.cn/post/7650719111397261354"));
    }

    @Test
    void pureAlphabeticPathWordIsNotAnId() {
        // /ai-models/llm-benchmark-tests/35 里的 "benchmark" 恰为 9 个字母：不得当 ID，
        // 否则同站不同编号的页面会被误合并（这两条是 q08 的真实命中）
        String a = "https://www.datalearner.com/ai-models/llm-benchmark-tests/35";
        String b = "https://www.datalearner.com/ai-models/llm-benchmark-tests/36";
        assertNotMirror(a, b);
        assertEquals(a, SourceMirror.mirrorKey(a)); // 无 ID → 退化为 URL 自身
    }

    @Test
    void toleratesMalformedInput() {
        assertEquals("", SourceMirror.mirrorKey(null));
        assertEquals("", SourceMirror.mirrorKey(""));
        assertEquals("not a url", SourceMirror.mirrorKey("not a url"));
        // null 与正常 URL 不同源；同一 URL 视为自身镜像（键相同）
        assertNotMirror(null, "https://a.com/x");
        assertMirror("https://a.com/x", "https://a.com/x");
    }
}
