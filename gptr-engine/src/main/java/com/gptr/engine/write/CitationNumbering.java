package com.gptr.engine.write;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 正文引用编号化：把 in-text markdown 链接 {@code [label](url)} 折叠为编号 {@code [n]}，
 * 并在文末生成编号参考文献表。
 *
 * <p><b>为什么需要</b>：写作 prompt 要求"claim 后紧跟链接"，于是正文里每条论断后面都挂着
 * 一整串 URL（实测占正文 16.2% 字符），可读性极差。
 *
 * <p><b>编号规则</b>：按 {@link CitationVerifier#canonicalize} 去重、跨节<b>全局连续</b>、
 * 顺序 = 首次出现顺序。逐节写作会对同一来源反复引用（实测同一 URL 最多被引 8.6 次），
 * 若按出现次数发号，参考文献表会退化成出现次数表。
 *
 * <p><b>无 LLM 成本</b>：纯字符串后处理，不额外调用模型，因而不改变报告内容、只改变显示形态。
 */
public final class CitationNumbering {

    /** 编号参考文献条目：label/url 取自该来源首次出现处。 */
    public record Ref(int number, String label, String url) {
    }

    /** 编号化结果：occurrences = 折叠的链接总数（含复用），reused = 其中复用既有编号的次数。 */
    public record Result(String markdown, List<Ref> refs, int occurrences, int reused) {
    }

    /**
     * 折叠整篇报告的 in-text 链接并追加编号参考文献表。
     *
     * <p>入参若已含参考文献段（如模型自己写的 {@code ## References}），<b>先剥掉再重建</b>
     * ——否则会出现两个参考文献段。
     *
     * <p>正文里没有任何 {@code [label](url)} 时<b>原样返回</b>，不追加空表。
     */
    public static Result apply(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new Result(markdown == null ? "" : markdown, List.of(), 0, 0);
        }
        CitationVerifier.Split split = CitationVerifier.splitReferences(markdown);
        // 原参考文献段的条目描述优先沿用（模型自己写的条目常带作者/年份/标题，
        // 正文行内 label 往往只是域名）；没有原条目时才退回正文 label。
        Numbering numbering = new Numbering(CitationVerifier.referenceEntries(split.references()));
        String numbered = numbering.numberize(split.body());
        if (numbering.refs.isEmpty()) {
            return new Result(markdown, List.of(), 0, 0);
        }
        String out = numbered.stripTrailing() + "\n\n" + renderReferences(numbering.refs);
        return new Result(out, List.copyOf(numbering.refs), numbering.occurrences, numbering.reused);
    }

    /** 编号参考文献表（含标题与条目间换行）。 */
    static String renderReferences(List<Ref> refs) {
        StringBuilder sb = new StringBuilder("## References\n\n");
        for (Ref r : refs) {
            sb.append(r.number()).append(". ").append(anchor(r.number()))
                    .append('[').append(r.label()).append("](").append(r.url()).append(")\n");
        }
        return sb.toString();
    }

    /**
     * 跨节累积的编号状态：{@code merge} 逐节调用 {@link #numberize}，编号因此在节间连续。
     */
    static final class Numbering {

        private final Map<String, Integer> numByUrl = new LinkedHashMap<>();
        private final List<Ref> refs = new ArrayList<>();
        private final Map<String, String> preferredLabels;
        private int occurrences;
        private int reused;

        Numbering() {
            this(Map.of());
        }

        /** {@code preferredLabels}：canonical URL → 优先沿用的条目描述（可为空）。 */
        Numbering(Map<String, String> preferredLabels) {
            this.preferredLabels = preferredLabels;
        }

        /** 已分配的编号条目（首次出现顺序）。 */
        List<Ref> refs() {
            return refs;
        }

        /**
         * 把一段正文里的链接替换为 {@code [n](#ref-n)}。
         *
         * <p>替换只动链接本体，唯有一个例外：写作模板要求的 {@code ([label](url))}
         * 外层圆括号与链接一起吃掉——否则正文残留 {@code ([1])}。
         */
        String numberize(String markdown) {
            List<CitationVerifier.LinkSpan> spans = CitationVerifier.scanMarkdownLinks(markdown);
            if (spans.isEmpty()) {
                return markdown;
            }
            StringBuilder out = new StringBuilder(markdown.length());
            int last = 0;
            for (CitationVerifier.LinkSpan s : spans) {
                occurrences++;
                String canonical = CitationVerifier.canonicalize(s.url());
                Integer n = numByUrl.get(canonical);
                if (n == null) {
                    n = refs.size() + 1;
                    numByUrl.put(canonical, n);
                    refs.add(new Ref(n, labelFor(canonical, s.label()), s.url()));
                } else {
                    reused++;
                }
                int from = s.start();
                int to = s.end();
                if (from > last && markdown.charAt(from - 1) == '(' && to < markdown.length()
                        && markdown.charAt(to) == ')') {
                    from--;
                    to++;
                }
                out.append(markdown, last, from)
                        .append('[').append(n).append("](#ref-").append(n).append(')');
                last = to;
            }
            out.append(markdown, last, markdown.length());
            return out.toString();
        }

        /** 条目描述：原参考文献条目优先，其次正文 label。 */
        private String labelFor(String canonical, String bodyLabel) {
            String preferred = preferredLabels.get(canonical);
            return preferred == null || preferred.isBlank() ? bodyLabel : preferred;
        }
    }

    /** 参考文献条目的跳转锚点（内联元素，不打断有序列表解析）。 */
    private static String anchor(int n) {
        return "<a id=\"ref-" + n + "\"></a>";
    }

    private CitationNumbering() {
    }
}
