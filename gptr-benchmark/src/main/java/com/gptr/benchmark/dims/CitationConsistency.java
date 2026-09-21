package com.gptr.benchmark.dims;

import com.gptr.engine.write.CitationVerifier;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * D1 引用一致性（零 LLM）：报告正文 in-text 链接 URL 是否都能在 References 段
 * 找到（canonical 匹配，复用 CitationVerifier）。衡量"报告内部引用自洽"。
 */
public final class CitationConsistency {

    /** D1 结果。 */
    public record Result(int inTextCitations, int unreferencedInText, double consistency) {
    }

    private CitationConsistency() {
    }

    public static Result calculate(String report) {
        if (report == null || report.isBlank()) {
            return new Result(0, 0, 1.0);
        }
        ReportText.Split split = ReportText.split(report);
        List<String> bodyUrls = new CitationVerifier(List.of()).citedUrls(split.body());
        List<String> refUrls = new CitationVerifier(List.of()).citedUrls(split.references());

        Set<String> refCanonical = new LinkedHashSet<>();
        for (String u : refUrls) {
            String c = CitationVerifier.canonicalize(u);
            if (c != null) {
                refCanonical.add(c);
            }
        }
        int unreferenced = 0;
        for (String u : bodyUrls) {
            String c = CitationVerifier.canonicalize(u);
            if (c == null || !refCanonical.contains(c)) {
                unreferenced++;
            }
        }
        int inText = bodyUrls.size();
        double consistency = inText == 0 ? 1.0 : 1.0 - (double) unreferenced / inText;
        return new Result(inText, unreferenced, consistency);
    }
}
