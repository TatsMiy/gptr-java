package com.gptr.benchmark;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.benchmark.dataset.BenchmarkItem;
import com.gptr.benchmark.dims.ABJudge;
import com.gptr.benchmark.dims.CitationConsistency;
import com.gptr.benchmark.dims.HallucinationJudge;
import com.gptr.benchmark.dims.MicroGold;
import com.gptr.benchmark.llm.JudgeClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
  * 跨系统报告对比（offline 模式）。
 *
 * <p>吃"题目 + 两份已生成报告 md"（如 {id}-java.md vs {id}-py.md），跑：
 * L-A 写作盲判（{@link ABJudge#WRITING_DIMENSIONS} 六维，swap 双跑一致性聚合）；
 * L-B 硬维护栏：D1 引用一致性（零 LLM）+ D2 三态幻觉（句-引用源绑定，需 crawler）
 * + 报告统计；micro-gold 判官自检同轮。
 *
 * <p>用法（先起 docker 基础设施供 D2 抓引用源；judge 默认 DeepSeek）：
 * <pre>
 * mvn -pl gptr-benchmark exec:java -Dexec.args="--set compare-set.jsonl --dir reports \
 *   --crawler http://localhost:8000 --out compare-out"
 * </pre>
 * 题集行：{"id": "q01", "query": "...", "lang": "zh|en"}；报告文件命名 {id}-java.md / {id}-py.md。
 */
public final class CompareMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        String setLoc = opt.getOrDefault("set", "compare-set.jsonl");
        String dir = opt.getOrDefault("dir", "reports");
        String crawler = opt.getOrDefault("crawler", "http://localhost:8000");
        String judgeBase = opt.getOrDefault("judge-base", "https://api.deepseek.com");
        String judgeModel = opt.getOrDefault("judge-model", "deepseek-chat");
        String judgeKey = opt.getOrDefault("judge-key",
                System.getenv("DEEPSEEK_API_KEY") == null ? "" : System.getenv("DEEPSEEK_API_KEY"));
        boolean noMicroGold = "1".equals(opt.getOrDefault("no-microgold", "0"))
                || "true".equalsIgnoreCase(opt.getOrDefault("no-microgold", ""));
        String outDir = opt.getOrDefault("out", "compare-out");
        Path out = Path.of(outDir);
        Files.createDirectories(out);

        List<JsonNode> items = loadQuestions(setLoc);
        JudgeClient judge = judgeKey.isBlank() ? null
                : new JudgeClient(judgeBase, judgeModel, judgeKey);
        HallucinationJudge hallucination = new HallucinationJudge(crawler);
        long[] judgeTokensRef = {0};

        Path perItem = out.resolve("per-item.jsonl");
        List<JsonNode> rows = new ArrayList<>();
        System.out.printf("对比评测：%d 题 | dir=%s | judge=%s%n", items.size(), dir, judge != null);
        for (JsonNode it : items) {
            String id = it.path("id").asText();
            String query = it.path("query").asText();
            String lang = it.path("lang").asText("zh");
            ObjectNode row = MAPPER.createObjectNode();
            row.put("id", id);
            row.put("query", query);
            row.put("lang", lang);

            String reportA = readReport(dir, id, "java");
            String reportB = readReport(dir, id, "py");
            if (reportA == null || reportB == null) {
                row.put("status", "MISSING_REPORT");
                rows.add(row);
                continue;
            }
            row.put("status", "OK");
            row.put("charsJava", reportA.length());
            row.put("charsPy", reportB.length());
            row.put("headingsJava", countHeadings(reportA));
            row.put("headingsPy", countHeadings(reportB));

            // L-B 硬维（D1 零 LLM；D2 需 judge+crawler）
            d1(row, "java", reportA);
            d1(row, "py", reportB);
            if (judge != null) {
                BenchmarkItem item = new BenchmarkItem(id, query, "deep", lang, "compare",
                        null, null, List.of());
                d2(row, "java", reportA, item, hallucination, judge);
                d2(row, "py", reportB, item, hallucination, judge);
                judgeTokensRef[0] += judge.lastTokens();
            }

            // L-A 写作盲判：swap 双跑一致性聚合
            if (judge != null) {
                ABJudge.Result run1 = ABJudge.judgeWriting(query, reportA, reportB, false, judge);
                judgeTokensRef[0] += judge.lastTokens();
                ABJudge.Result run2 = ABJudge.judgeWriting(query, reportA, reportB, true, judge);
                judgeTokensRef[0] += judge.lastTokens();
                if (run1 == null || run2 == null) {
                    row.put("blind", "judge-unparsable");
                } else {
                    ObjectNode b = row.putObject("blind");
                    // 聚合胜者（双跑一致才采信；不一致 → inconsistent）
                    ObjectNode w = b.putObject("winners");
                    ArrayNode runs = b.putArray("runs");
                    for (ABJudge.Result r : List.of(run1, run2)) {
                        ObjectNode run = runs.addObject();
                        run.put("swap", r == run2);
                        ObjectNode rw = run.putObject("winners");
                        for (String d : ABJudge.WRITING_DIMENSIONS) {
                            rw.put(d, r.winners().get(d));
                        }
                        ObjectNode rr = run.putObject("reasons");
                        for (String d : ABJudge.WRITING_DIMENSIONS) {
                            rr.put(d, r.reasons().getOrDefault(d, ""));
                        }
                    }
                    ObjectNode reasons = b.putObject("reasons");
                    for (String dim : ABJudge.WRITING_DIMENSIONS) {
                        String v1 = run1.winners().get(dim);
                        String v2 = run2.winners().get(dim);
                        w.put(dim, v1.equals(v2) ? v1 : "inconsistent");
                        // 理由附注各跑 swap 状态：两跑中 report1/2 指代相反，
                        // 引用理由时按同侧（java/py）对齐而非按编号
                        reasons.put(dim, "(run1 swap=false) " + run1.reasons().getOrDefault(dim, "")
                                + " || (run2 swap=true) " + run2.reasons().getOrDefault(dim, ""));
                    }
                }
            }
            rows.add(row);
            try (var fw = Files.newBufferedWriter(perItem,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                fw.write(MAPPER.writeValueAsString(row));
                fw.newLine();
            }
            System.out.println("  " + id + " done (chars " + reportA.length() + "/" + reportB.length() + ")");
        }

        // 汇总
        ObjectNode metrics = MAPPER.createObjectNode();
        metrics.put("items", rows.size());
        metrics.put("judgeTokens", judgeTokensRef[0]);
        ObjectNode dimCounts = metrics.putObject("blindByDim");
        for (String dim : ABJudge.WRITING_DIMENSIONS) {
            int a = 0;
            int b = 0;
            int tie = 0;
            int incon = 0;
            int noJudge = 0;
            for (JsonNode r : rows) {
                JsonNode w = r.path("blind").path("winners").path(dim);
                if (w.isMissingNode()) {
                    noJudge++;
                } else {
                    switch (w.asText()) {
                        case "A" -> a++;
                        case "B" -> b++;
                        case "tie" -> tie++;
                        default -> incon++;
                    }
                }
            }
            ObjectNode c = dimCounts.putObject(dim);
            c.put("A(java)", a);
            c.put("B(py)", b);
            c.put("tie", tie);
            c.put("inconsistent", incon);
            c.put("noJudge", noJudge);
        }
        metrics.put("d2JavaClaims", sum(rows, "d2Java", "claims"));
        metrics.put("d2JavaContradictory", sum(rows, "d2Java", "contradictory"));
        metrics.put("d2JavaInconclusive", sum(rows, "d2Java", "inconclusive"));
        metrics.put("d2PyClaims", sum(rows, "d2Py", "claims"));
        metrics.put("d2PyContradictory", sum(rows, "d2Py", "contradictory"));
        metrics.put("d2PyInconclusive", sum(rows, "d2Py", "inconclusive"));
        metrics.put("d1JavaAvg", avgConsistency(rows, "d1Java"));
        metrics.put("d1PyAvg", avgConsistency(rows, "d1Py"));

        if (judge != null && !noMicroGold) {
            try {
                MicroGold.Report mg = MicroGold.run(judge, 5);
                MicroGold.D3Report d3 = MicroGold.runD3(judge);
                ObjectNode gold = metrics.putObject("microGold");
                gold.put("d2JudgeAccuracy", Math.round(mg.accuracy() * 10000.0) / 10000.0);
                gold.put("hallucinationMissRate", Math.round(mg.hallucinationMissRate() * 10000.0) / 10000.0);
                gold.put("d3JudgeAccuracy", Math.round(d3.accuracy() * 10000.0) / 10000.0);
            } catch (Exception e) {
                metrics.put("microGold", "probe-failed: " + e.getMessage());
            }
        }

        Files.writeString(out.resolve("metrics.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metrics));
        System.out.println("聚合（" + out + "）：");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metrics));
    }

    private static void d1(ObjectNode row, String side, String report) {
        CitationConsistency.Result r = CitationConsistency.calculate(report);
        ObjectNode o = row.putObject("d1" + cap(side));
        o.put("inText", r.inTextCitations());
        o.put("unreferenced", r.unreferencedInText());
        o.put("consistency", Math.round(r.consistency() * 10000.0) / 10000.0);
    }

    private static void d2(ObjectNode row, String side, String report, BenchmarkItem item,
                           HallucinationJudge judge, JudgeClient llm) {
        HallucinationJudge.Verdict v = judge.judge(item, report, llm);
        ObjectNode o = row.putObject("d2" + cap(side));
        if (v == null) {
            o.put("claims", 0);
            return;
        }
        o.put("claims", v.judged());
        o.put("contradictory", v.contradictory());
        o.put("inconclusive", v.inconclusive());
        o.put("noCitation", v.noCitation());
        o.put("unverifiable", v.unverifiable());
        o.put("rate", v.hallucinationRate() < 0 ? -1 : Math.round(v.hallucinationRate() * 10000.0) / 10000.0);
    }

    private static int sum(List<JsonNode> rows, String key, String field) {
        int s = 0;
        for (JsonNode r : rows) {
            s += r.path(key).path(field).asInt(0);
        }
        return s;
    }

    private static double avgConsistency(List<JsonNode> rows, String key) {
        double s = 0;
        int n = 0;
        for (JsonNode r : rows) {
            if (r.path(key).has("consistency")) {
                s += r.path(key).path("consistency").asDouble();
                n++;
            }
        }
        return n == 0 ? Double.NaN : Math.round(s / n * 10000.0) / 10000.0;
    }

    private static String readReport(String dir, String id, String side) {
        try {
            Path p = Path.of(dir, id + "-" + side + ".md");
            return Files.exists(p) ? Files.readString(p) : null;
        } catch (Exception e) {
            // 报告文件缺失/不可读 → 返回 null（调用方按"该侧无报告"跳过该题）
            return null;
        }
    }

    private static int countHeadings(String report) {
        int n = 0;
        for (String line : report.split("\n")) {
            if (line.startsWith("#")) {
                n++;
            }
        }
        return n;
    }

    private static List<JsonNode> loadQuestions(String loc) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        Path p = Path.of(loc);
        if (!Files.exists(p)) {
            throw new IllegalArgumentException("question set not found: " + loc);
        }
        for (String line : Files.readAllLines(p)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n = MAPPER.readTree(line);
            if (n.path("query").isTextual() && !n.path("query").asText().isBlank()) {
                out.add(n);
            }
        }
        return out;
    }

    private static String cap(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    /**
     * 解析 {@code --key value} 与无值开关 {@code --flag}（置为 {@code "true"}）。
     *
     * <p>【2026-09-14 修复】原实现 {@code for (i = 0; i < args.length - 1; i += 2)} 有两个缺陷：
     * <ul>
     *   <li><b>静默丢弃末尾孤立参数</b>：末位参数永远进不了循环 → 例如
     *       {@code run_compare.ps1} 追加在最后的 {@code --no-microgold} 从未生效（micro-gold
     *       自检一直照跑）；</li>
     *   <li><b>无值开关会把下一个参数吞成值</b>：{@code --no-microgold --set x} 会解析成
     *       {@code no-microgold="--set"}。</li>
     * </ul>
     * 现改为按位扫描：非 {@code --} 开头即报错（不再静默忽略），下一参数不以 {@code --}
     * 开头时作为取值、否则视作无值开关。宁可启动即失败，也不要带着未被识别的参数跑真实任务。
     */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--") || a.length() <= 2) {
                throw new IllegalArgumentException(
                        "无法识别的参数（应为 --key value 或 --flag）: " + a);
            }
            String key = a.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                m.put(key, args[++i]);
            } else {
                m.put(key, "true");
            }
        }
        return m;
    }
}
