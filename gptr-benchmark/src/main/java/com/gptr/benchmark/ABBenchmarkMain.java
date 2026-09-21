package com.gptr.benchmark;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.benchmark.dataset.BenchmarkItem;
import com.gptr.benchmark.dataset.DatasetLoader;
import com.gptr.benchmark.dims.ABJudge;
import com.gptr.benchmark.llm.JudgeClient;
import com.gptr.benchmark.report.ResultWriter;
import com.gptr.benchmark.run.TaskRunner;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A/B 盲判评测入口：同一问题分别用两个引擎配置（config A / config B）跑研究，
 * 匿名成对后由 LLM 盲判四维胜率（accuracy/depth/citation/structure）——
 * 引擎配置对比（开关/检索源/澄清/提炼粒度等）的正确消偏工具。
 *
 * <p>用法（先起 api/worker/crawler）：
 * <pre>
 * java -cp ... com.gptr.benchmark.ABBenchmarkMain \
 *   --api http://localhost:8080 --crawler http://localhost:8000 \
 *   --ab-a '{"mode":"deep_research","breadth":2,"depth":1}' \
 *   --ab-b '{"mode":"deep_research","breadth":2,"depth":1,"curateSources":true}' \
 *   --label-a baseline --label-b curate --limit 4 --out results
 * </pre>
 * 结果：per-item 行 + 聚合 metrics（每维 A/B/tie 计数、A 胜率、成本/延迟对比）。
 */
public final class ABBenchmarkMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        String api = opt.getOrDefault("api", "http://localhost:8080");
        String judgeBase = opt.getOrDefault("judge-base", "https://api.deepseek.com");
        String judgeModel = opt.getOrDefault("judge-model", "deepseek-chat");
        String judgeKey = opt.getOrDefault("judge-key",
                System.getenv("DEEPSEEK_API_KEY") == null ? "" : System.getenv("DEEPSEEK_API_KEY"));
        String configA = opt.get("ab-a");
        String configB = opt.get("ab-b");
        if (configA == null || configB == null) {
            throw new IllegalArgumentException("需要 --ab-a <configA json> 与 --ab-b <configB json>");
        }
        String labelA = opt.getOrDefault("label-a", "A");
        String labelB = opt.getOrDefault("label-b", "B");
        String datasetLoc = opt.getOrDefault("dataset", "classpath:datasets/benchmark.jsonl");
        int limit = Integer.parseInt(opt.getOrDefault("limit", "0"));
        String runId = "ab_" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outBase = opt.getOrDefault("out", "results");

        List<BenchmarkItem> items = DatasetLoader.load(datasetLoc);
        if (limit > 0 && items.size() > limit) {
            items = items.subList(0, limit);
        }
        System.out.printf("A/B 盲判开始：%d 题 | A=%s | B=%s%n", items.size(), labelA, labelB);
        if (judgeKey.isBlank()) {
            System.out.println("警告：--judge-key 未提供，盲判维度将跳过（只跑任务与成本对比）");
        }

        ResultWriter writer = new ResultWriter(Path.of(outBase, runId));
        TaskRunner taskRunner = new TaskRunner(api, TaskRunner.storageFrom(opt));
        JudgeClient judge = judgeKey.isBlank() ? null
                : new JudgeClient(judgeBase, judgeModel, judgeKey);

        long ts = Instant.now().toEpochMilli();
        int judgedPairs = 0;
        int failedA = 0;
        int failedB = 0;
        double totalCostA = 0;
        double totalCostB = 0;
        long totalMsA = 0;
        long totalMsB = 0;
        Map<String, int[]> dimCounts = new LinkedHashMap<>(); // dim → {A胜, B胜, tie}
        for (String dim : ABJudge.DIMENSIONS) {
            dimCounts.put(dim, new int[]{0, 0, 0});
        }
        final List<BenchmarkItem> finalItems = items; // lambda 捕获需 effectively final
        int concurrency = Math.max(1, Integer.parseInt(opt.getOrDefault("concurrency", "1")));
        if (concurrency > 1) {
            System.out.println("A/B 并发：" + concurrency);
        }
        ObjectNode[] rows = new ObjectNode[finalItems.size()];

        // 阶段 1：并发提交与盲判（每题独立 row；worker 侧并发消费队列）
        try (ExecutorService pool = Executors
                .newFixedThreadPool(concurrency, Thread.ofVirtual().factory())) {
            List<CompletableFuture<Void>> futures =
                    new ArrayList<>();
            AbRunContext abCtx = new AbRunContext(finalItems, rows, taskRunner, judge,
                    configA, configB, ts);
            for (int i = 0; i < finalItems.size(); i++) {
                final int idx = i;
                final BenchmarkItem item = finalItems.get(i);
                futures.add(CompletableFuture.runAsync(
                        () -> runOneAb(idx, item, abCtx), pool));
            }
            for (CompletableFuture<Void> f : futures) {
                f.join();
            }
        }

        // 阶段 2：按原顺序聚合与落盘（多线程 append 会损坏 jsonl，统一主线程写）
        for (int i = 0; i < rows.length; i++) {
            ObjectNode row = rows[i];
            totalCostA += row.path("costA").asDouble(0);
            totalCostB += row.path("costB").asDouble(0);
            totalMsA += row.path("elapsedSecA").asLong(0);
            totalMsB += row.path("elapsedSecB").asLong(0);
            if (!"SUCCEEDED".equals(row.path("statusA").asText())) {
                failedA++;
            }
            if (!"SUCCEEDED".equals(row.path("statusB").asText())) {
                failedB++;
            }
            if ("judged".equals(row.path("ab").asText())) {
                judgedPairs++;
                JsonNode winners = row.path("winners");
                int d = 0;
                for (String dim : ABJudge.DIMENSIONS) {
                    String w = winners.path(d).asText("tie");
                    int[] c = dimCounts.get(dim);
                    if ("A".equals(w)) {
                        c[0]++;
                    } else if ("B".equals(w)) {
                        c[1]++;
                    } else {
                        c[2]++;
                    }
                    d++;
                }
            }
            writer.appendItem(row);
        }

        ObjectNode metrics = MAPPER.createObjectNode();
        metrics.put("runId", runId);
        metrics.put("labelA", labelA);
        metrics.put("labelB", labelB);
        metrics.put("items", items.size());
        metrics.put("pairsBothSucceeded", items.size() - failedA - failedB);
        metrics.put("failedA", failedA);
        metrics.put("failedB", failedB);
        metrics.put("judgedPairs", judgedPairs);
        metrics.put("totalCostUsdA", Math.round(totalCostA * 10000.0) / 10000.0);
        metrics.put("totalCostUsdB", Math.round(totalCostB * 10000.0) / 10000.0);
        metrics.put("avgElapsedSecA", Math.round((totalMsA / (double) items.size()) * 100.0) / 100.0);
        metrics.put("avgElapsedSecB", Math.round((totalMsB / (double) items.size()) * 100.0) / 100.0);
        ArrayNode byDim = metrics.putArray("blindByDim");
        for (String dim : ABJudge.DIMENSIONS) {
            int[] c = dimCounts.get(dim);
            int decidable = c[0] + c[1];
            ObjectNode o = byDim.addObject();
            o.put("dim", dim);
            o.put("winA", c[0]);
            o.put("winB", c[1]);
            o.put("tie", c[2]);
            o.put("winRateA", Math.round((decidable == 0 ? Double.NaN
                    : (double) c[0] / decidable) * 10000.0) / 10000.0);
        }
        writer.writeMetrics(metrics);
        System.out.println("聚合指标（" + writer.dir() + "）：");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metrics));
    }

    /** 单题 A/B 评测的共享依赖（原 runAsync lambda 闭包捕获的变量打包，避免长参数表）。 */
    private record AbRunContext(List<BenchmarkItem> allItems, ObjectNode[] rows,
                                TaskRunner taskRunner, JudgeClient judge,
                                String configA, String configB, long ts) {
    }

    /** A/B 评测单题：两次 runWithConfig + 匿名化盲判，结果写入 rows[idx]
     *  （原 runAsync lambda 体逐字搬入）。 */
    private static void runOneAb(int idx, BenchmarkItem item, AbRunContext ctx) {
        System.out.printf("[%d/%d] %s: %s%n", idx + 1, ctx.allItems().size(),
                item.id(), item.query());
        ObjectNode row = MAPPER.createObjectNode();
        ctx.rows()[idx] = row;
        row.put("id", item.id());
        row.put("query", item.query());
        row.put("configA", ctx.configA());
        row.put("configB", ctx.configB());
        String key = "ab-" + ctx.ts() + "-" + item.id();

        TaskRunner.ResearchOutcome ra = ctx.taskRunner().runWithConfig(
                item.query(), ctx.configA(), key + "-a", 300);
        TaskRunner.ResearchOutcome rb = ctx.taskRunner().runWithConfig(
                item.query(), ctx.configB(), key + "-b", 300);
        row.put("taskIdA", ra.taskId() == null ? "" : ra.taskId());
        row.put("taskIdB", rb.taskId() == null ? "" : rb.taskId());
        row.put("costA", Math.round(ra.costUsd() * 10000.0) / 10000.0);
        row.put("costB", Math.round(rb.costUsd() * 10000.0) / 10000.0);
        row.put("elapsedSecA", ra.elapsedSec());
        row.put("elapsedSecB", rb.elapsedSec());
        if (!ra.succeeded() || ra.report().isBlank()) {
            row.put("statusA", "FAILED");
            row.put("errorA", ra.error());
        } else {
            row.put("statusA", "SUCCEEDED");
        }
        if (!rb.succeeded() || rb.report().isBlank()) {
            row.put("statusB", "FAILED");
            row.put("errorB", rb.error());
        } else {
            row.put("statusB", "SUCCEEDED");
        }

        if ("SUCCEEDED".equals(row.path("statusA").asText())
                && "SUCCEEDED".equals(row.path("statusB").asText())
                && ctx.judge() != null) {
            // 匿名化：每题随机决定 report1=config A 还是 B（judge 不知映射）
            boolean swap = new Random(item.id().hashCode()).nextBoolean();
            row.put("swap", swap);
            try {
                ABJudge.Result r = ABJudge.judge(item.query(), ra.report(),
                        rb.report(), swap, ctx.judge());
                if (r == null) {
                    row.put("ab", "judge-unparsable");
                } else {
                    ArrayNode wins = row.putArray("winners");
                    for (String dim : ABJudge.DIMENSIONS) {
                        wins.add(r.winners().get(dim));
                    }
                    row.put("reasons",
                            MAPPER.valueToTree(r.reasons()).toString());
                    row.put("ab", "judged");
                }
            } catch (Exception e) {
                row.put("ab", "judge-error");
                row.put("errorJudge", e.getMessage());
            }
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length - 1; i += 2) {
            if (args[i].startsWith("--")) {
                m.put(args[i].substring(2), args[i + 1]);
            }
        }
        return m;
    }
}
