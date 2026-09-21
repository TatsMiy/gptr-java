package com.gptr.engine.epoc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 终局对照（B 臂诊断）：Java containsNormalized 对【真实页面全文 + 真实 LLM 摘句】的匹配率。
 * 数据文件由 scripts/diag_repro_save.py 生成（真实 crawler 抓取 + 真实 DeepSeek 调用）。
 * 本地诊断用：mvn test -Dtest=RealWorldMatchDiagTest -Ddiag.real=1
 */
class RealWorldMatchDiagTest {

    @Test
    @EnabledIfSystemProperty(named = "diag.real", matches = "1")
    void realSentencesAllMatchRealSource() throws Exception {
        String raw = Files.readString(Path.of("C:/dshWork/compare-set/openr1-raw.txt"));
        String sents = Files.readString(Path.of("C:/dshWork/compare-set/openr1-sents.json"));
        JsonNode arr = new ObjectMapper().readTree(sents);
        int ok = 0;
        StringBuilder report = new StringBuilder();
        for (JsonNode n : arr) {
            String s = n.asText("");
            String single = s.trim().replaceAll("\\s+", " ");
            boolean m = EvidenceText.containsNormalized(raw, single);
            if (m) {
                ok++;
            } else {
                report.append("\nFAIL: ").append(single.substring(0, Math.min(140, single.length())));
                // 定位失配点：normalize 后 raw 中最近上下文
                String ns = norm(raw);
                String nn = norm(single);
                int pos = ns.indexOf(nn.substring(0, Math.min(40, nn.length())));
                report.append("\n  norm-needle-head in norm-raw at: ").append(pos);
                if (pos < 0) {
                    report.append("  (not found anywhere)");
                } else {
                    report.append("  raw ctx: ").append(raw.substring(Math.max(0, pos - 60), Math.min(raw.length(), pos + 120)).replace("\n", "\\n"));
                }
            }
        }
        System.out.println("MATCH " + ok + "/" + arr.size() + report);
        assertTrue(ok >= arr.size() - 1, "匹配率应 ≥ (n-1)/n：" + report);
    }

    private static String norm(String s) {
        return s.replaceAll("(?U)[\\s\\p{Punct}。，、；：！？「」『』（）【】*#]", "").toLowerCase();
    }
}
