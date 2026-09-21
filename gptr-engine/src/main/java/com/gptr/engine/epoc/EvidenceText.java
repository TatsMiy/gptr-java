package com.gptr.engine.epoc;

import com.gptr.engine.write.CitationVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 证据文本工具：蒸馏块协议、授权判定、URL 提取——写作与提炼链路上的纯函数集合。
 *
 * <p><b>本类分块地图</b>（按方法名定位——行号会腐烂，故本注释刻意不写行号）：
 * <ol>
 *   <li><b>蒸馏块协议</b> —— {@code DISTILLED_MARKER}（块前缀，生产者与解析器共用）、
 *       {@code parseDistilledBlock}（块 → Raw note 候选）、{@code sentencesIn}（块内句数统计）。</li>
 *   <li><b>保真前检</b> —— {@code containsNormalized}（归一化包含，判"逐字原句 vs 改写"）。</li>
 *   <li><b>授权</b> —— {@code isAuthorized}（URL ∈ 授权集，canonical 变体回退）、
 *       {@code stripUnauthorizedSource}（剥除未授权来源锚与连带 quote）。</li>
 *   <li><b>文本与 URL</b> —— {@code extractUrls} / {@code truncateQueryText}。</li>
 * </ol>
 *
 * <p>纯函数、无状态：不读配置、不碰 state、不调 LLM。
 * <b>注意</b>本类与 benchmark 侧 {@code ReportText} 存在<b>同名但语义不同</b>的方法（见
 * {@link #containsNormalized} 注释），<b>不可合并</b>。
 */
final class EvidenceText {

    private EvidenceText() {
    }

    /** [DISTILLED] 协议块前缀（extract/直通解析/评测统计共用）。
     *  <p><b>生产者与消费者分居两处</b>：拼块头在 {@code DeepResearchGraph.distillPageSentences}，
     *  解析在本类 {@link #parseDistilledBlock}，中间判定在 {@code action}/{@code realExtractPerQuery}。 */
    static final String DISTILLED_MARKER = "[DISTILLED] URL: ";

    /** 蒸馏句的**最短长度门槛**：短于此的句子在两端都被剔除——
     *  ① {@code ScrapeNode} 蒸馏产出时的质量闸（计入 {@code tooShort}）；
     *  ② 本类 {@link #parseDistilledBlock} 的 Y 臂直通解析。
     *  <p><b>两端必须一致</b>：否则会出现"蒸馏判为合格、直通又丢掉"。 */
    static final int DISTILL_MIN_SENTENCE_CHARS = 20;

    private static final Pattern URL_IN_TEXT =
            Pattern.compile("https?://[^\\s\\]\\[\\>\"'，。；、,;]+");

    private static final Pattern SOURCE_SUFFIX =
            Pattern.compile("\\s*\\[source:\\s*(https?://[^\\]]+)\\]\\s*$");

    /** 归一化包含检查（去空白与标点后小写 contains；判蒸馏是否"逐字摘原句"）。
     *
     *  <p><b>注意 (?U)</b>：{@code \p{Punct}}/{@code \s} 必须 Unicode 感知——网页原文常含
     *  弯引号/弯撇号（U+2018-201D、U+2019 等），LLM 输出常转 ASCII——ASCII-only 标点归一化会把
     *  "逐字原句"误判为改写（实测蒸馏 12/12 页 rewritten=0 kept 全灭即此因）。
     *
     *  <p><b>与 benchmark 侧 {@code ReportText.containsNormalized} 不等价，勿合并</b>——三处差异：
     *  ① null/blank needle：本类返回 {@code false}，彼返回 {@code true}（"空 evidence 视为通过"）；
     *  ② 本类 Unicode 感知，彼为 ASCII-only（{@code (?U)} 是上述实测驱动加的）；
     *  ③ needle 归一化后为空时：本类返回 false，彼 {@code contains("")} 返回 true。
     *  engine 不依赖 benchmark 模块，故本地实现。 */
    static boolean containsNormalized(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isBlank()) {
            return false;
        }
        String h = haystack.replaceAll("(?U)[\\s\\p{Punct}。，、；：！？「」『』（）【】*#]", "").toLowerCase();
        String n = needle.replaceAll("(?U)[\\s\\p{Punct}。，、；：！？「」『』（）【】*#]", "").toLowerCase();
        return !n.isEmpty() && h.contains(n);
    }

    /** [DISTILLED] 块中句数（蒸馏统计用；块格式 "- 句" 行）。 */
    static long sentencesIn(String block) {
        return block.lines().filter(l -> l.startsWith("- ")).count();
    }

    /** 解析 [DISTILLED] 块 → Raw note 候选（Y 臂直通）：insight=原句、quote 空、
     *  sourceUrl=块头 URL（Java 程序化注入，不经 LLM）。句长/内容已由蒸馏前检保证。 */
    static List<EvidenceNote.Raw> parseDistilledBlock(String block) {
        List<EvidenceNote.Raw> out = new ArrayList<>();
        String url = "";
        String[] lines = block.split("\n");
        for (String line : lines) {
            if (line.startsWith(DISTILLED_MARKER)) {
                url = line.substring(DISTILLED_MARKER.length()).trim();
                break;
            }
        }
        if (url.isBlank()) {
            return out; // 无 URL 锚的块 → 不直通（防孤儿）
        }
        for (String line : lines) {
            if (line.startsWith("- ") && line.length() > 2) {
                String s = line.substring(2).trim();
                if (s.length() >= DISTILL_MIN_SENTENCE_CHARS) {
                    out.add(new EvidenceNote.Raw(s, "", url, true)); // direct：直通去重适用
                }
            }
        }
        return out;
    }

    /** queryText 存储截断（写作期归节匹配用）。上限原为 {@code EvidenceNote.QUERY_TEXT_STORE_MAX}
          *  =400，现由 {@code ExtractionBudget#queryTextStoreMax} 经参数传入。 */
    static String truncateQueryText(String q, int max) {
        if (q == null || q.length() <= max) {
            return q == null ? "" : q;
        }
        return q.substring(0, max).trim() + "…";
    }

    /** 授权判定（extract/round 共用；canonical 变体回退匹配）。
     *  <p>只校验 URL ∈ 授权集，<b>不</b>要求该页被真的抓过——故漏斗的"未读来源"数可能
     *  大于 0（正确性信号，见 {@code ResearchEngineImpl.logChainFunnel}）。 */
    static boolean isAuthorized(String url, List<String> authorized) {
        if (url == null || url.isBlank() || authorized == null || authorized.isEmpty()) {
            return false;
        }
        if (authorized.contains(url)) {
            return true;
        }
        String canonical = CitationVerifier.canonicalize(url);
        if (canonical == null) {
            return false;
        }
        for (String a : authorized) {
            String ac = CitationVerifier.canonicalize(a);
            if (ac != null && ac.equals(canonical)) {
                return true;
            }
        }
        return false;
    }

    /** insight 尾部 "[quote: …] [source: url]"（quote 可选）若 source 不在授权集 →
     *  quote 与 source 一并剥除（无 URL 锚的孤儿证据不可审计，不留残渣）。 */
    static String stripUnauthorizedSource(String insight, List<String> authorized) {
        if (insight == null || insight.isEmpty()) {
            return insight;
        }
        Matcher m = SOURCE_SUFFIX.matcher(insight);
        if (!m.find()) {
            return insight;
        }
        String url = m.group(1);
        if (isAuthorized(url, authorized)) {
            return insight;
        }
        // 未授权：若 source 前有 [quote: …]，从 quote 起点一并剥除
        int quoteStart = insight.lastIndexOf(" [quote: ", m.start());
        int cut = quoteStart >= 0 ? quoteStart : m.start();
        return insight.substring(0, cut).trim();
    }

    /** 从文本提取 URL（去重；供抓取候选与诊断用）。<b>包级可见</b>：{@code action} 的两条
     *  取名分支都要用它，故不能随其余私有辅助一起 private。
     *  <p><b>与 {@code CitationVerifier.extractUrls} 不等价</b>：彼为手写扫描器（括号配对 +
     *  markdown 结构解析），本类为正则。两处对 URL 边界的判定可能不同——本类不用于引用核验。 */
    static List<String> extractUrls(String text) {
        List<String> urls = new ArrayList<>();
        if (text == null) {
            return urls;
        }
        Matcher m = URL_IN_TEXT.matcher(text);
        while (m.find()) {
            String url = m.group().trim();
            if (!urls.contains(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    /** 把全文条目追加进所有引用 {@code url} 的条目组（per-query 证据归位：组内任一条目含该 URL 即命中）。
     *  <p>⚠️ <b>就地修改</b>传入的 {@code items}——调用方须传可变列表（deep 路径为此对条目组做了深拷贝，
     *  否则会 mutate 到 checkpoint 内部的不可变结构）。 */
    static void appendToGroupsReferencing(List<List<String>> items, String url, String fullBlock) {
        if (items == null || url == null) {
            return;
        }
        for (List<String> group : items) {
            boolean refs = false;
            for (String item : group) {
                if (item != null && item.contains(url)) {
                    refs = true;
                    break;
                }
            }
            if (refs) {
                group.add(fullBlock);
            }
        }
    }
}
