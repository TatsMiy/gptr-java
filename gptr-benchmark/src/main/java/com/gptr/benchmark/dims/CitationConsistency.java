package com.gptr.benchmark.dims;

import com.gptr.engine.write.CitationVerifier;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * D1 引用一致性（零 LLM）：正文引用与参考文献表是否自洽。
 *
 * <p>两种报告形态都支持：
 * <ul>
 *   <li><b>编号化</b>：正文只有 {@code [n]} 标记，URL 集中在参考文献表 ⇒ 经编号表反解；</li>
 *   <li><b>行内</b>：正文直接内嵌 URL。</li>
 * </ul>
 *
 * <p>两个方向各自成数，互不掩盖：
 * <ul>
 *   <li>{@code dangling} —— 正文引用了参考文献表里<b>不存在</b>的编号（自洽性缺陷）；</li>
 *   <li>{@code uncitedRefs} —— 参考文献表里<b>从未被正文引用</b>的来源（凑数/灌水信号）；
 *       按 canonical URL 比对，故编号化与行内两种形态都算得出来。</li>
 * </ul>
 * 只有"正文引用了表外的编号"这一件事进 {@code consistency}：它衡量的是<b>编号闭环</b>，
 * 与"表里有多余条目"是两回事，混进一个比率会让两个独立缺陷互相掩盖。
 */
public final class CitationConsistency {

    /** D1 结果。 */
    public record Result(int inTextCitations, int dangling, int uncitedRefs, double consistency) {
    }

    private CitationConsistency() {
    }

    public static Result calculate(String report) {
        if (report == null || report.isBlank()) {
            return new Result(0, 0, 0, 1.0);
        }
        ReportText.Split split = ReportText.split(report);
        Map<Integer, String> refIndex = CitationVerifier.referenceIndex(split.references());

        Set<String> bodyCanonical = new LinkedHashSet<>();
        int dangling = 0;
        int markers = 0;
        Matcher m = ReportText.NUMBERED_MARKER.matcher(split.body());
        while (m.find()) {
            markers++;
            String url = refIndex.get(Integer.parseInt(m.group(1)));
            if (url == null) {
                dangling++;
            } else {
                addCanonical(bodyCanonical, url);
            }
        }
        // 行内形态：正文直嵌的 URL（编号化报告的正文里一个都没有，两项互不重叠）
        for (String url : inlineUrls(split.body())) {
            addCanonical(bodyCanonical, url);
        }

        Set<String> refCanonical = new LinkedHashSet<>();
        for (String url : inlineUrls(split.references())) {
            addCanonical(refCanonical, url);
        }
        int uncitedRefs = 0;
        for (String canonical : refCanonical) {
            if (!bodyCanonical.contains(canonical)) {
                uncitedRefs++;
            }
        }

        int inText = markers + inlineCount(split.body());
        double consistency = inText == 0 ? 1.0 : 1.0 - (double) dangling / inText;
        return new Result(inText, dangling, uncitedRefs, consistency);
    }

    /** 文本里出现过的 URL（保序、去重）。 */
    private static List<String> inlineUrls(String text) {
        return new CitationVerifier(List.of()).citedUrls(text);
    }

    /** 文本里 URL 的出现次数。 */
    private static int inlineCount(String text) {
        return CitationVerifier.citationStats(text).total();
    }

    private static void addCanonical(Set<String> out, String url) {
        String canonical = CitationVerifier.canonicalize(url);
        if (canonical != null) {
            out.add(canonical);
        }
    }
}
