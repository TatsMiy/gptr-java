package com.gptr.benchmark.dims;

import com.gptr.engine.write.CitationVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报告文本工具：切 References 段 / 切句（保留句内引用 URL）。
 *
 * <p>句子切分改进：ASCII {@code .} 后跟数字（小数/缩写）不切句——只在句读标点
 * 或 {@code .} 后随空白/中文/引号处切。
 */
public final class ReportText {

    /** 正文的编号引用标记：{@code [n]} 或 {@code [n](#ref-n)}（包可见：D1 口径共用）。 */
    static final Pattern NUMBERED_MARKER = Pattern.compile(
            "\\[(\\d{1,3})\\](?:\\(#ref-\\d{1,3}\\))?");

    /** 句界：中文句读标点后；ASCII .!? 后随空白/行尾/中文/引号（排除小数与缩写内切）。 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile(
            "(?<=[。！？!?；;])\\s*|(?<=[.!?])(?=[\\s\\n\\u4e00-\\u9fff“\"‘（(【])");

    /** 裸 URL 终止中文句读（与 extractInlineUrls 一致；真实 URL 不含未编码全角标点）。 */
    private static final String CJK_PUNCT = "。，；：！？、」』》】）";

    private ReportText() {
    }

    /** 单句：原文（供 judge 回显校验）+ 句内 markdown 链接 URL。 */
    public record Sentence(String text, List<String> citationUrls) {
    }

    /** 报告 → {body 正文, references 来源段}（无 References 段则整篇为 body）。 */
    public record Split(String body, String references) {
    }

    /**
     * 切出参考文献段：委托 {@link CitationVerifier#splitReferences}
     * ——参考文献段的标题识别全仓只有一套，避免两处正则各自演化。
     */
    public static Split split(String report) {
        CitationVerifier.Split s = CitationVerifier.splitReferences(report);
        return new Split(s.body(), s.references());
    }

    /** 切句并提取句内引用 URL（不反解编号标记）。 */
    public static List<Sentence> sentences(String text, int cap) {
        return sentences(text, cap, Map.of());
    }

    /**
     * 切句并提取句内引用来源：含 URL 的 markdown 链接、裸 URL，以及编号标记 {@code [n]}
     * （经 {@code refIndex} 反解为 URL）。
     *
     * <p>编号化报告的正文只剩 {@code [n]}——没有 {@code refIndex} 就取不到逐句取证依据。
     *
     * <p>链接感知切句：先把 markdown 链接/裸 URL 整体替换为不可切哨兵，再切纯文本；
     * 句读后紧邻链接的"纯链接残片"回流并入前句（引用支撑前句主张）；首句孤立链接
     * （无前句）前向吸收进下一正文句。URL 内句点/括号绝不参与句界。
     */
    public static List<Sentence> sentences(String text, int cap, Map<Integer, String> refIndex) {
        List<Sentence> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        List<Protect> units = new ArrayList<>();
        String guarded = protectLinks(text, units);

        List<MutableSentence> pending = new ArrayList<>(); // 正文句累积
        List<String> orphanUrls = new ArrayList<>();       // 首句孤立链接（等下一正文句）
        for (String part : SENTENCE_SPLIT.split(guarded)) {
            if (part.isBlank()) {
                continue;
            }
            List<String> partUrls = urlsOf(part, units);
            if (isLinkOnlyFragment(part, units)) {
                // 纯链接残片（句读后紧邻链接被切出）：回流并入前句
                if (!pending.isEmpty()) {
                    pending.get(pending.size() - 1).urls().addAll(partUrls);
                } else {
                    orphanUrls.addAll(partUrls); // 首句孤立：前向吸收
                }
                continue;
            }
            String restored = restore(part, units);
            String s = restored.trim();
            if (s.length() < 12 || s.length() > 500) {
                continue;
            }
            List<String> urls = new ArrayList<>(partUrls);
            addNumberedUrls(s, refIndex, urls);
            if (!orphanUrls.isEmpty()) {
                urls.addAll(0, orphanUrls); // 首句孤立链接前向吸收进首个正文句
                orphanUrls.clear();
            }
            pending.add(new MutableSentence(s, urls));
            if (pending.size() >= cap) {
                break;
            }
        }
        // 尾部孤立链接（全文只有链接/链接后无正文）：单独成句（保留审计可见性）
        if (!orphanUrls.isEmpty() && out.isEmpty() && pending.isEmpty()) {
            String linkText = units.stream()
                    .filter(u -> orphanUrls.contains(u.url()))
                    .map(Protect::original)
                    .reduce("", (a, b) -> a + b);
            if (!linkText.isBlank() && linkText.length() >= 12) {
                pending.add(new MutableSentence(linkText, new ArrayList<>(orphanUrls)));
            }
        }
        for (MutableSentence m : pending) {
            out.add(new Sentence(m.text(), m.urls()));
        }
        return out;
    }

    /** 可变句子（链接回流阶段用；record 不可变所以先累积后转）。 */
    private record MutableSentence(String text, List<String> urls) {
    }

    /** 保护单元：哨兵 ↔ 原文（链接/裸 URL）↔ URL。 */
    private record Protect(String sentinel, String original, String url) {
    }

    /** 把链接与裸 URL 替换为哨兵（URL 内句点/括号不再参与句界判定）。 */
    private static String protectLinks(String text, List<Protect> units) {
        StringBuilder sb = new StringBuilder(text.length());
        int n = text.length();
        for (int i = 0; i < n; ) {
            int hit = -1;
            String url = null;
            String original = null;
            // 1) markdown 链接 [label](http(s)://...)
            if (text.charAt(i) == '[') {
                int close = text.indexOf(']', i + 1);
                if (close >= 0 && close + 1 < n && text.charAt(close + 1) == '(') {
                    int j = close + 2;
                    int open = 0;
                    while (j < n) {
                        char c = text.charAt(j);
                        if (c == '(') {
                            open++;
                        } else if (c == ')') {
                            if (open == 0) {
                                break;
                            }
                            open--;
                        } else if (c == ']' || c == '>' || c == '"' || c == '\'' || c == '`'
                                || Character.isWhitespace(c)) {
                            break;
                        }
                        j++;
                    }
                    String u = text.substring(close + 2, j).trim();
                    if (isHttp(u)) {
                        hit = j;
                        url = u;
                        original = text.substring(i, j + 1);
                    }
                }
            }
            // 2) 裸 URL
            if (hit < 0 && (text.regionMatches(true, i, "https://", 0, 8)
                    || text.regionMatches(true, i, "http://", 0, 7))) {
                int j = i;
                int open = 0;
                while (j < n) {
                    char c = text.charAt(j);
                    if (c == '(') {
                        open++;
                    } else if (c == ')') {
                        if (open == 0) {
                            break;
                        }
                        open--;
                    } else if (c == ']' || c == '>' || c == '"' || c == '\'' || c == '`'
                            || Character.isWhitespace(c) || CJK_PUNCT.indexOf(c) >= 0) {
                        break;
                    }
                    j++;
                }
                String u = text.substring(i, j).trim();
                if (!u.isEmpty()) {
                    hit = j;
                    url = u;
                    original = text.substring(i, j);
                }
            }
            if (hit > i && url != null) {
                Protect unit = new Protect("@U" + units.size() + "@", original, url);
                units.add(unit);
                sb.append(unit.sentinel());
                i = hit;
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean isHttp(String u) {
        return u != null && (u.regionMatches(true, 0, "https://", 0, 8)
                || u.regionMatches(true, 0, "http://", 0, 7));
    }

    /** 把句内编号标记 {@code [n]} 反解为 URL 并追加（保序、去重；n 不在表中则跳过）。 */
    private static void addNumberedUrls(String text, Map<Integer, String> refIndex, List<String> urls) {
        if (refIndex == null || refIndex.isEmpty()) {
            return;
        }
        Matcher m = NUMBERED_MARKER.matcher(text);
        while (m.find()) {
            String url = refIndex.get(Integer.parseInt(m.group(1)));
            if (url != null && !urls.contains(url)) {
                urls.add(url);
            }
        }
    }

    /** 片段中的哨兵 → 提取 URL（保序）。 */
    private static List<String> urlsOf(String part, List<Protect> units) {
        List<String> urls = new ArrayList<>();
        for (Protect unit : units) {
            if (part.contains(unit.sentinel()) && !urls.contains(unit.url())) {
                urls.add(unit.url());
            }
        }
        return urls;
    }

    /** 片段是否"纯链接残片"：去掉哨兵与标点/空白后无正文内容。 */
    private static boolean isLinkOnlyFragment(String part, List<Protect> units) {
        if (!part.contains("@U")) {
            return false;
        }
        for (int i = 0; i < part.length(); ) {
            if (part.startsWith("@U", i)) {
                int end = part.indexOf('@', i + 2);
                if (end < 0) {
                    return false;
                }
                String tag = part.substring(i, end + 1);
                boolean known = units.stream().anyMatch(u -> u.sentinel().equals(tag));
                if (!known) {
                    return false;
                }
                i = end + 1;
                continue;
            }
            char c = part.charAt(i);
            boolean punct = Character.isWhitespace(c) || !Character.isLetterOrDigit(c);
            if (!punct) {
                return false;
            }
            i++;
        }
        return true;
    }

    /** 哨兵替换回原文。 */
    private static String restore(String part, List<Protect> units) {
        String s = part;
        for (Protect unit : units) {
            s = s.replace(unit.sentinel(), unit.original());
        }
        return s;
    }

    /**
     * 提取句内 markdown 链接 URL：{@code [text](https://…)} 与裸 URL。
     * 括号配对处理（URL 内含括号时取到配平的 {@code )}）。
     */
    public static List<String> extractInlineUrls(String text) {
        List<String> urls = new ArrayList<>();
        if (text == null) {
            return urls;
        }
        int n = text.length();
        for (int i = 0; i < n; ) {
            int start;
            if (text.regionMatches(true, i, "https://", 0, 8)) {
                start = i;
            } else if (text.regionMatches(true, i, "http://", 0, 7)) {
                start = i;
            } else {
                i++;
                continue;
            }
            int j = start;
            int open = 0;
            while (j < n) {
                char c = text.charAt(j);
                if (c == '(') {
                    open++;
                } else if (c == ')') {
                    if (open == 0) {
                        break;
                    }
                    open--;
                } else if (c == ']' || c == '>' || c == '"' || c == '\'' || Character.isWhitespace(c)
                        || "。，；：！？、」』》】）".indexOf(c) >= 0) {
                    break;
                }
                j++;
            }
            String url = text.substring(start, j).trim();
            if (!url.isEmpty() && !urls.contains(url)) {
                urls.add(url);
            }
            i = Math.max(j, start + 8);
        }
        return urls;
    }

    /** 截断到 maxChars（尾部省略标记）。 */
    public static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars) + "\n...[truncated]";
    }

    /** C3：归一化后判断 needle 是否含于 haystack（evidence 真实性校验用）。 */
    public static boolean containsNormalized(String haystack, String needle) {
        if (needle == null || needle.isBlank()) {
            return true; // 空 evidence 视为通过（judge 未给出依据时另行标记）
        }
        if (haystack == null || haystack.isBlank()) {
            return false;
        }
        return normalize(haystack).contains(normalize(needle));
    }

    private static String normalize(String s) {
        return s.replaceAll("[\\s\\p{Punct}。，、；：！？「」『』（）【】*#]", "").toLowerCase();
    }
}
