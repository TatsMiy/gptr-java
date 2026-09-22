package com.gptr.worker;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 临时诊断工具（默认跳过）：直读 graph_checkpoints，打印指定 thread（= task id）的图状态关键字段。
 * 用途：验收时核对 queries / planGaps / followUpQuestions / evidenceBank 的真实内容
 * （worker 日志只记条数，不记文本）。
 *
 * <p>运行：{@code mvn -o test -pl gptr-worker -Dtest=CheckpointPeekTest -Dpeek.thread=<taskId> -Dpeek.on=true}
 * <p>线程 id 与 {@code ResearchEngineImpl} 一致 = task id。
 */
class CheckpointPeekTest {

    @Test
    void peek() throws Exception {
        String thread = System.getProperty("peek.thread", "");
        // 必须用 assumeTrue 而非 return：return 会让 `mvn test` 把本类报成
        // PASS 且断言数为 0 的"假绿"，掩盖"诊断其实没跑"这一事实。本类是 worker 模块唯一
        // 不带 @Tag("integration") 的测试，常规 mvn test 下唯一会执行的就是它 —— 假绿代价最大。
        Assumptions.assumeTrue(Boolean.getBoolean("peek.on") && !thread.isBlank(),
                "[peek] 跳过：需 -Dpeek.on=true -Dpeek.thread=<taskId>（默认不连接数据库）");
        String sql = "SELECT state_json FROM graph_checkpoints WHERE thread_id = ?";
        String outPath = System.getProperty("peek.out", "");
        var sb = new StringBuilder();
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/gptr", "gptr", "gptr");
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, thread);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no checkpoint row for thread " + thread);
                String json = rs.getString(1);
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var root = mapper.readTree(json);
                say(sb, "[peek] state fields = " + root.size());
                dump(sb, root, "queries", 12);
                dump(sb, root, "evidenceBank", 0);
                // 全量 note dump（-Dpeek.bankfull=true）：用于定位"关键事实丢在哪一层"——
                // 若素材 note 里已含某数字而报告正文没有 → 丢在写作层；note 里也没有 → 丢在提炼层。
                if (Boolean.getBoolean("peek.bankfull")) {
                    var bm = new com.fasterxml.jackson.databind.ObjectMapper();
                    say(sb, "[peek] === evidenceBank 全量（insight | quote | sourceUrl）===");
                    for (var node : root.path("evidenceBank")) {
                        String s = node.asText();
                        try {
                            var note = bm.readTree(s);
                            say(sb, String.format("  [d%s/r%s/q%s] %s | %s | %s",
                                    note.path("depth").asText(), note.path("round").asText(),
                                    note.path("queryIdx").asText(),
                                    note.path("insight").asText(""),
                                    note.path("quote").asText(""),
                                    note.path("sourceUrl").asText("")));
                        } catch (Exception ex) {
                            say(sb, "  <parse fail> " + s.substring(0, Math.min(160, s.length())));
                        }
                    }
                }
                dump(sb, root, "planGaps", 20);
                dump(sb, root, "followUpQuestions", 20);
                dump(sb, root, "researchState", 4000);
                dump(sb, root, "currentDepth", 1);
                dump(sb, root, "roundLearnings", 1);
                dump(sb, root, "collectedUrls", 0);
                dump(sb, root, "visitedUrls", 0);
                dump(sb, root, "queryItems", 0);
                // bank 的 queryText 可反推全部层/轮出现过的查询（checkpoint 只留最新态，
                // 首层 queries 只能从这里还原）
                var bank = root.path("evidenceBank");
                if (bank.isArray()) {
                    var seen = new java.util.LinkedHashMap<String, String>();
                    var perDepth = new java.util.TreeMap<Integer, Integer>();
                    var idxPerDepth = new java.util.TreeMap<Integer, java.util.TreeSet<Integer>>();
                    var countByDepthIdx = new java.util.TreeMap<String, Integer>();
                    var urlCount = new java.util.TreeMap<String, Integer>();
                    for (var node : bank) {
                        var note = mapper.readTree(node.asText());
                        int d = note.path("depth").asInt();
                        int qi = note.path("queryIdx").asInt();
                        perDepth.merge(d, 1, Integer::sum);
                        idxPerDepth.computeIfAbsent(d, k -> new java.util.TreeSet<>()).add(qi);
                        countByDepthIdx.merge(d + "/" + qi, 1, Integer::sum);
                        String u = note.path("sourceUrl").asText("");
                        if (!u.isBlank()) {
                            urlCount.merge(u, 1, Integer::sum);
                        }
                        String key = note.path("depth").asInt() + "/" + note.path("round").asInt()
                                + "/" + note.path("queryIdx").asInt();
                        seen.putIfAbsent(key, note.path("queryText").asText(""));
                    }
                    say(sb, "[peek] bank by depth (notes / distinct queryIdx):");
                    perDepth.forEach((d, n) -> say(sb, "   d" + d + ": notes=" + n
                            + " queryIdx=" + idxPerDepth.get(d)));
                    say(sb, "[peek] notes per (depth/queryIdx): " + countByDepthIdx);
                    // 单来源依赖诊断：Y 臂直通（每句一 note）不受 maxPerUrl 约束，
                    // 一篇综述型长文可产出数十条同源 note → 主导某个节的引用（实测 q08 同一
                    // URL 在一节内被引 21 次）。这里按 URL 统计 note 数并列出头部。
                    say(sb, "[peek] distinct sourceUrls = " + urlCount.size()
                            + "  (notes with url = "
                            + urlCount.values().stream().mapToInt(Integer::intValue).sum() + ")");
                    say(sb, "[peek] top URLs by note count:");
                    urlCount.entrySet().stream()
                            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                            .limit(12)
                            .forEach(e -> say(sb, "   " + e.getValue() + " 条  " + e.getKey()));
                    int maxPerUrl = urlCount.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                    say(sb, "[peek] max notes from a single URL = " + maxPerUrl
                            + "  (预算 maxPerUrl 只约束 LLM 提炼路径，直通路径不设限)");
                    // 测量：来源不均的根因判定——每个来源的 note 数 + 内容样本。
                    // 目的：区分「长文综述产出多句」/「短页只产 1 句」/「检索噪声」
                    say(sb, "[peek] per-URL: note 数 | 域名 | 首条 insight 样本（判断来源性质）:");
                    var byUrl = new java.util.LinkedHashMap<String, java.util.List<String>>();
                    for (var node : bank) {
                        var note = mapper.readTree(node.asText());
                        String u = note.path("sourceUrl").asText("");
                        if (!u.isBlank()) {
                            byUrl.computeIfAbsent(u, k -> new java.util.ArrayList<>())
                                    .add(note.path("insight").asText(""));
                        }
                    }
                    byUrl.entrySet().stream()
                            .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                            .forEach(e -> {
                                String dom = e.getKey().replaceAll("^https?://([^/]+).*$", "$1");
                                String sample = e.getValue().isEmpty() ? ""
                                        : e.getValue().get(0).replaceAll("\\s+", " ");
                                if (sample.length() > 120) {
                                    sample = sample.substring(0, 120);
                                }
                                say(sb, String.format("   %3d 条  %-26s %s",
                                        e.getValue().size(), dom, sample));
                            });
                    int single = (int) urlCount.values().stream().filter(n -> n == 1).count();
                    int top5 = urlCount.values().stream()
                            .sorted(java.util.Comparator.reverseOrder()).limit(5)
                            .mapToInt(Integer::intValue).sum();
                    int totalNotes = urlCount.values().stream().mapToInt(Integer::intValue).sum();
                    say(sb, String.format("[peek] 集中度: 来源 %d 个，其中 %d 个只贡献 1 条；"
                                    + "top5 占 %d/%d = %d%%",
                            urlCount.size(), single, top5, totalNotes,
                            Math.round(100.0 * top5 / Math.max(1, totalNotes))));
                    say(sb, "[peek] querys seen in bank (depth/round/idx -> queryText): "
                            + seen.size());
                    seen.forEach((k, v) -> say(sb, "   " + k + " -> " + v));
                }
            }
        }
        String text = sb.toString();
        System.out.println(text);
        if (!outPath.isBlank()) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(outPath), text);
        }
    }

    private static void say(StringBuilder sb, String line) {
        sb.append(line).append(System.lineSeparator());
    }

    private static void dump(StringBuilder sb, com.fasterxml.jackson.databind.JsonNode root,
                             String field, int textCap) {
        var n = root.path(field);
        if (n.isMissingNode() || n.isNull()) {
            say(sb, "[peek] " + field + " = <absent>");
            return;
        }
        if (n.isArray()) {
            say(sb, "[peek] " + field + " size=" + n.size());
            if (field.equals("queryItems")) {
                for (int i = 0; i < n.size(); i++) {
                    var g = n.get(i);
                    say(sb, "   group[" + i + "] items=" + (g.isArray() ? g.size() : -1));
                }
                return;
            }
            for (int i = 0; i < n.size(); i++) {
                String s = n.get(i).isTextual() ? n.get(i).asText() : n.get(i).toString();
                if (field.equals("evidenceBank") && i == 0) {
                    say(sb, "   [0] " + s.substring(0, Math.min(400, s.length())));
                    say(sb, "   ... (bank 明细省略)");
                    break;
                }
                say(sb, "   [" + i + "] " + cap(s, textCap));
            }
        } else {
            say(sb, "[peek] " + field + " = " + cap(n.asText(), textCap));
        }
    }

    private static String cap(String s, int n) {
        if (n <= 0 || s.length() <= n) {
            return s;
        }
        return s.substring(0, n) + " …(+" + (s.length() - n) + ")";
    }
}
