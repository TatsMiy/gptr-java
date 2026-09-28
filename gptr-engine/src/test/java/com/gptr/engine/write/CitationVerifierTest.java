package com.gptr.engine.write;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CitationVerifier URL 规范化单测：变体 URL（尾斜杠/大小写/跟踪参数/
 * fragment/默认端口）应与授权来源 canonical 匹配，不再误判为幻觉引用。
 */
class CitationVerifierTest {

    @Test
    void canonicalizeNormalizesVariants() {
        assertEquals("https://example.com/a",
                CitationVerifier.canonicalize("https://Example.COM/a/"));
        assertEquals("https://example.com/a",
                CitationVerifier.canonicalize("HTTPS://example.com:443/a"));
        // http + 443 是显式非默认端口：保留
        assertEquals("http://example.com:443/a",
                CitationVerifier.canonicalize("HTTP://example.com:443/a"));
        assertEquals("http://example.com/a",
                CitationVerifier.canonicalize("http://example.com:80/a"));
        assertEquals("https://example.com/a",
                CitationVerifier.canonicalize("https://example.com/a#section"));
        assertEquals("https://example.com/a?x=1",
                CitationVerifier.canonicalize("https://example.com/a?utm_source=news&x=1&utm_medium=email"));
        assertEquals("https://example.com",
                CitationVerifier.canonicalize("https://example.com/"));
        // 非 http(s) 与解析失败 → null
        assertNull(CitationVerifier.canonicalize("ftp://example.com/a"));
        assertNull(CitationVerifier.canonicalize("not a url"));
        assertNull(CitationVerifier.canonicalize(null));
    }

    @Test
    void canonicalizeKeepsParenthesizedPath() {
        // Wikipedia 等 URL 路径含括号：必须保留（正则不再截断）
        assertEquals("https://en.wikipedia.org/wiki/Transformer_(deep_learning)",
                CitationVerifier.canonicalize(
                        "https://en.wikipedia.org/wiki/Transformer_(deep_learning)"));
    }

    @Test
    void variantCitationIsVerifiedAgainstAuthorized() {
        CitationVerifier verifier = new CitationVerifier(List.of(
                "https://en.wikipedia.org/wiki/Transformer_(deep_learning)",
                "https://www.codecademy.com/article/transformer-architecture-self-attention-mechanism"));
        String report = "参见 ([Wikipedia](https://en.wikipedia.org/wiki/Transformer_(deep_learning)/)) 与 "
                + "([Codecademy](https://www.codecademy.com/article/transformer-architecture-self-attention-mechanism?utm_source=report))。";
        assertFalse(verifier.hasUnauthorizedCitations(report),
                "URL 变体（尾斜杠/跟踪参数/路径括号）不应误判为未授权");
        assertEquals(2, verifier.verify(report).size(), "两个变体引用都应核验通过");
    }

    @Test
    void realFabricatedCitationStillDetected() {
        CitationVerifier verifier = new CitationVerifier(List.of("https://a.com/page"));
        assertTrue(verifier.hasUnauthorizedCitations(
                "claim ([a](https://a.com/page)) and ([fake](https://b.com/other))"),
                "真幻觉引用必须仍被检出");
    }

    @Test
    void verifiedReturnsReportOriginalUrls() {
        CitationVerifier verifier = new CitationVerifier(List.of("https://example.com/"));
        List<String> verified = verifier.verify("cite https://EXAMPLE.com and https://example.com/x");
        assertEquals(1, verified.size(), "只有 canonical 匹配的引用被核验通过");
        assertEquals("https://EXAMPLE.com", verified.get(0), "返回报告原文而非授权原文");
    }

    @Test
    void chinesePunctuationAfterUrlIsStripped() {
        // 中文报告正文 URL 后常紧跟全角标点：不应破坏 canonical 匹配
        CitationVerifier verifier = new CitationVerifier(List.of(
                "https://zh.wikipedia.org/zh-cn/长江",
                "https://www.bang.cn/top10/8215.html"));
        String report = "长江是中国最长的河流（[长江 - 维基百科](https://zh.wikipedia.org/zh-cn/长江)）。"
                + "也有资料记载为6397公里（[中国最长的河流](https://www.bang.cn/top10/8215.html)。";
        assertFalse(verifier.hasUnauthorizedCitations(report),
                "中文全角标点后的 URL 不应误判未授权");
        assertEquals(2, verifier.verify(report).size());
    }

    @Test
    void blankReportPasses() {
        CitationVerifier verifier = new CitationVerifier(List.of("https://a.com"));
        assertFalse(verifier.hasUnauthorizedCitations(""));
        assertEquals(0, verifier.citedUrls(null).size());
    }

    // ------------------------------------------------------------------
    // 边界补修（实证 7 类误判）
    // ------------------------------------------------------------------

    @Test
    void percentEncodedUnreservedDecodedBothSides() {
        // %E9%95%BF 与原始中文必须归一（两侧任意形态）
        assertEquals(CitationVerifier.canonicalize("https://zh.wikipedia.org/zh-cn/长江"),
                CitationVerifier.canonicalize("https://zh.wikipedia.org/zh-cn/%E9%95%BF%E6%B1%9F"));
        // 多字节 UTF-8 编码解码归一（与原始汉字等价）
        assertEquals("https://example.com/中文",
                CitationVerifier.canonicalize("https://example.com/%E4%B8%AD%E6%96%87"));
        // 保留字符编码不还原（%2F 不应变成 /）
        assertEquals("https://example.com/a%2Fb",
                CitationVerifier.canonicalize("https://example.com/a%2Fb"));
        CitationVerifier verifier = new CitationVerifier(List.of(
                "https://zh.wikipedia.org/zh-cn/%E9%95%BF%E6%B1%9F"));
        assertFalse(verifier.hasUnauthorizedCitations("见[长江](https://zh.wikipedia.org/zh-cn/长江)。"));
    }

    @Test
    void queryParameterOrderInsensitive() {
        assertEquals(CitationVerifier.canonicalize("https://example.com/search?x=1&y=2"),
                CitationVerifier.canonicalize("https://example.com/search?y=2&x=1"));
        CitationVerifier verifier = new CitationVerifier(List.of("https://example.com/search?x=1&y=2"));
        assertFalse(verifier.hasUnauthorizedCitations("见[链接](https://example.com/search?y=2&x=1)。"));
    }

    @Test
    void ipv6CanonicalKeepsBrackets() {
        assertEquals("http://[::1]:8080/x",
                CitationVerifier.canonicalize("http://[::1]:8080/x"),
                "IPv6 canonical 必须回包方括号（否则产出非法 URL）");
        assertEquals("https://[2001:db8::1]/x",
                CitationVerifier.canonicalize("https://[2001:DB8::1]:443/x"));
    }

    @Test
    void backtickTerminatesUrl() {
        CitationVerifier verifier = new CitationVerifier(List.of("https://example.com/a"));
        List<String> urls = verifier.citedUrls("见 `https://example.com/a` 文档");
        assertEquals(1, urls.size(), "反引号应终止 URL");
        assertFalse(verifier.hasUnauthorizedCitations("见 `https://example.com/a`"));
    }

    @Test
    void semicolonInPathIsKept() {
        // RFC 3986 pchar 允许 ; ——扫描器不应截断
        assertEquals("https://example.com/a;param",
                CitationVerifier.canonicalize("https://example.com/a;param"));
        CitationVerifier verifier = new CitationVerifier(List.of("https://example.com/a;param"));
        assertFalse(verifier.hasUnauthorizedCitations("见[页](https://example.com/a;param)。"));
    }

    @Test
    void trailingPunctuationStrippedOnBothSides() {
        // 授权 URL 与报告引用同时剥尾标点（原缺陷：只有报告侧剥 → ?q=hello! 误判）
        assertEquals("https://example.com/?q=hello",
                CitationVerifier.canonicalize("https://example.com/?q=hello!"));
        CitationVerifier verifier = new CitationVerifier(List.of("https://example.com/?q=hello!"));
        assertFalse(verifier.hasUnauthorizedCitations("见[页](https://example.com/?q=hello!)。"));
    }

    // ------------------------------------------------------------------
    // 参考文献段切分与编号表反解
    // ------------------------------------------------------------------

    @Test
    void splitReferencesTakesLastHeading() {
        CitationVerifier.Split none = CitationVerifier.splitReferences("正文没有来源段");
        assertEquals("正文没有来源段", none.body());
        assertEquals("", none.references());

        CitationVerifier.Split s = CitationVerifier.splitReferences(
                "# 标题\n\n正文。\n\n## References\n\n1. [甲](https://a.com)\n");
        assertTrue(s.body().startsWith("# 标题"), "body 到参考文献标题为止: " + s.body());
        assertFalse(s.body().contains("https://"), "body 不得含来源段内容");
        assertTrue(s.references().contains("https://a.com"));

        // 正文里出现同名小标题时，取最后一个（真正的来源段在文末）
        CitationVerifier.Split twice = CitationVerifier.splitReferences(
                "## References\n\n讨论段。\n\n## References\n\n1. [甲](https://a.com)\n");
        assertTrue(twice.body().contains("讨论段"), "最后一个标题之前的都算正文");
    }

    @Test
    void referenceIndexParsesNumberedEntries() {
        String refs = "## References\n\n"
                + "1. <a id=\"ref-1\"></a>[甲](https://a.com/x)\n"
                + "2. <a id=\"ref-2\"></a>[乙](https://b.com/y)\n"
                + "3. 没有链接的坏行\n"
                + "4. [丙](https://c.com/z)\n";
        var index = CitationVerifier.referenceIndex(refs);
        assertEquals(3, index.size(), "坏行跳过: " + index);
        assertEquals("https://a.com/x", index.get(1));
        assertEquals("https://b.com/y", index.get(2));
        assertEquals("https://c.com/z", index.get(4));
        assertFalse(index.containsKey(3), "无链接的条目不入表");
        assertTrue(CitationVerifier.referenceIndex(null).isEmpty());
    }

    @Test
    void referenceIndexKeepsFirstOnDuplicateNumber() {
        String refs = "## References\n\n1. [甲](https://a.com)\n1. [乙](https://b.com)\n";
        assertEquals("https://a.com", CitationVerifier.referenceIndex(refs).get(1));
    }

    @Test
    void referenceEntriesKeepDescriptionDropsTerminalUrl() {
        // 编号化产物形态：锚点 + 正文 label
        String numbered = "## References\n\n1. <a id=\"ref-1\"></a>[CSDN](https://a.com/x)\n";
        assertEquals("CSDN", CitationVerifier.referenceEntries(numbered).get("https://a.com/x"));

        // 学术式条目：尾部“以 URL 当 label”的链接整条删除，正文描述保留
        String academic = "## References\n\n"
                + "1. Zhao, Y., Yuan, B. (2026). AMA-Bench: A Benchmark. arXiv. "
                + "[https://arxiv.org/abs/2602.22769](https://arxiv.org/abs/2602.22769)\n";
        String label = CitationVerifier.referenceEntries(academic)
                .get("https://arxiv.org/abs/2602.22769");
        assertTrue(label.contains("AMA-Bench"), "描述文字保留: " + label);
        assertFalse(label.contains("https://"), "尾部 URL 必须去掉: " + label);
    }
}
