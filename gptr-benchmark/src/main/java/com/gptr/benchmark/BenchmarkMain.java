package com.gptr.benchmark;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.benchmark.dataset.BenchmarkItem;
import com.gptr.benchmark.dataset.DatasetLoader;
import com.gptr.benchmark.dims.AnswerAccuracyJudge;
import com.gptr.benchmark.dims.CitationConsistency;
import com.gptr.benchmark.dims.HallucinationJudge;
import com.gptr.benchmark.dims.KeypointCoverage;
import com.gptr.benchmark.dims.MicroGold;
import com.gptr.benchmark.dims.ReportText;
import com.gptr.benchmark.llm.JudgeClient;
import com.gptr.benchmark.report.ResultWriter;
import com.gptr.benchmark.run.TaskRunner;
import com.gptr.benchmark.stats.Wilson;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1 评测器入口（研究质量评测：三维度 + KAE-lite 引用覆盖）。
 *
 * <p>用法（先起 api/worker/crawler）：
 * <pre>
 * mvn -pl gptr-benchmark exec:java \
 *   -Dexec.args="--api http://localhost:8080 --crawler http://localhost:8000 \
 *   --judge-key \${DEEPSEEK_API_KEY} --limit 5"
 * </pre>
 * 默认键：judge-base=https://api.deepseek.com, judge-model=deepseek-chat,
 * judge-key 取环境 DEEPSEEK_API_KEY。
 */
public final class BenchmarkMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** H2 A/B 基线：直接作答 prompt（无研究；不得提示"近期/检索"，保持公平基线）。 */
    private static final String DIRECT_SYSTEM = """
            你是知识问答助手。直接回答下面的问题，给出你认为正确的答案与关键事实/数值，
            简洁作答（2-5 句）。不要描述过程，不要提及检索或研究。
            """;

    /** --config 附加引擎配置（每题 base config 之上合并，如 {"sourceDistill":true}
     *  用于引擎参数 on/off 的评测对比）。main 开头设置，此后只读。 */
    private static String extraConfigJson = "";

    /** --no-microgold：跳过 judge 自检探针（L1/L2 冒烟档用，省 ~1min/趟）。 */
    private static boolean skipMicroGold = false;

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        String api = opt.getOrDefault("api", "http://localhost:8080");
        String crawler = opt.getOrDefault("crawler", "http://localhost:8000");
        String judgeBase = opt.getOrDefault("judge-base", "https://api.deepseek.com");
        String judgeModel = opt.getOrDefault("judge-model", "deepseek-chat");
        String judgeKey = opt.getOrDefault("judge-key",
                System.getenv("DEEPSEEK_API_KEY") == null ? "" : System.getenv("DEEPSEEK_API_KEY"));
        String datasetLoc = opt.getOrDefault("dataset", "classpath:datasets/benchmark.jsonl");
        // limit 默认 1（**不默认跑完整题集**）。
        // 原因：零参数执行 `mvn -pl gptr-benchmark exec:java` 会直接向真实 API 提交
        // 全部 47 题（--api 有默认值）—— 误触一次就是全量调用。默认只跑 1 题把代价压到最小；
        // 全量运行请显式传 --limit 0，并配合 --set/--dataset 指定题集。
        int limit = Integer.parseInt(opt.getOrDefault("limit", "1"));
        extraConfigJson = opt.getOrDefault("config", "");
        skipMicroGold = "1".equals(opt.getOrDefault("no-microgold", "0"))
                || "true".equalsIgnoreCase(opt.getOrDefault("no-microgold", ""));
        String runId = "run_" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outBase = opt.getOrDefault("out", "results");

        List<BenchmarkItem> items = DatasetLoader.load(datasetLoc);
        int totalItems = items.size();
        if (limit > 0 && items.size() > limit) {
            items = items.subList(0, limit);
        }
        // 启动即声明"本次要跑多少"：误触时第一眼就能看见代价
        System.out.printf("[benchmark] 本次将跑 %d / %d 题（--limit 控制，0=全部；--dataset 换题集）%n",
                items.size(), totalItems);
        // ⑤：排除低区分度题（--exclude-prefix id 前缀逗号分隔，--exclude-cat cat 逗号分隔），
        // 例：--exclude-prefix zh-fact,en-fact 剔除纯常识题，让大盘聚焦检索/时效区分度
        List<String> excludePrefixes = Arrays.stream(
                        opt.getOrDefault("exclude-prefix", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> excludeCats = Arrays.stream(
                        opt.getOrDefault("exclude-cat", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> excludedIds = new ArrayList<>();
        if (!excludePrefixes.isEmpty() || !excludeCats.isEmpty()) {
            items = items.stream()
                    .filter(it -> keepItem(it, excludePrefixes, excludeCats, excludedIds))
                    .toList();
            System.out.printf("排除 %d 题（%s）%n", excludedIds.size(), String.join(",", excludedIds));
        }
        final List<BenchmarkItem> finalItems = items; // lambda 捕获需 effectively final
        System.out.printf("评测开始：%d 题 | api=%s | crawler=%s | judge=%s/%s%n",
                finalItems.size(), api, crawler, judgeBase, judgeModel);
        if (judgeKey.isBlank()) {
            System.out.println("警告：--judge-key 未提供（默认读 DEEPSEEK_API_KEY），LLM judge 维度将跳过");
        }

        ResultWriter writer = new ResultWriter(Path.of(outBase, runId));
        // ②：报告读取走存储抽象（--storage local|minio；默认与 worker 本地一致）
        TaskRunner taskRunner = new TaskRunner(api, TaskRunner.storageFrom(opt));
        HallucinationJudge hallucination = new HallucinationJudge(crawler);
        JudgeClient judge = judgeKey.isBlank() ? null
                : new JudgeClient(judgeBase, judgeModel, judgeKey);

        long ts = Instant.now().toEpochMilli();
        long totalStart = System.currentTimeMillis();
        double totalCost = 0;
        long[] totalJudgeTokensRef = {0};
        int succeeded = 0;
        int failed = 0;

        // 聚合桶
        Map<String, double[]> accBuckets = new LinkedHashMap<>(); // mode|lang|all → {n, correct}
        Map<String, double[]> staleBuckets = new LinkedHashMap<>();   // H3 wrong_stale（管线答旧值）
        Map<String, double[]> directBuckets = new LinkedHashMap<>();  // H2 A/B 基线（直接作答）
        Map<String, double[]> directStaleBuckets = new LinkedHashMap<>();
        double d1Sum = 0;
        int d1Count = 0;
        int uncitedRefsSum = 0;
        int danglingSum = 0;
        int danglingItems = 0;
        int hallClaims = 0;
        int hallUnsupported = 0;
        int hallInconclusive = 0;
        int hallNoCitation = 0;
        int hallUnverifiable = 0;
        int hallJudgedItems = 0;
        // 泄漏计数（报告引用 blocked 来源）
        int leakItems = 0;
        int leakExcludedCorrect = 0;
        // KAE-lite（引用覆盖）聚合
        int kaeCount = 0;
        double ksrSum = 0;
        double kcrSum = 0;
        double korSum = 0;
        double kaeEffSum = 0;
        // H1：latency/cost 分位数（对标 py run_eval 的 p50/p95）
        List<Double> latencySec = new ArrayList<>();
        List<Double> costUsdList = new ArrayList<>();

        KeypointCoverage keypointCoverage = new KeypointCoverage(crawler);

        // ③：--concurrency N（默认 1=原串行语义）。worker 侧已 4 并发（SKIP LOCKED），
        // 评测器多题并行提交才能把队列打满；虚拟线程限制并发跑题，输出仍按原顺序聚合落盘。
        int concurrency = Math.max(1, Integer.parseInt(opt.getOrDefault("concurrency", "1")));
        if (concurrency > 1) {
            System.out.println("并发评测：" + concurrency + "（需 worker.concurrency ≥ 该值才有效提速）");
        }
        ObjectNode[] rows = new ObjectNode[finalItems.size()];
        AtomicLong judgeTokens = new AtomicLong();
        AtomicInteger retriedItemsRef = new AtomicInteger();
        AtomicInteger itemErrorsRef = new AtomicInteger();

        // 阶段 1：并行提交与轮询（每题独立 row，无共享可变状态）
        try (ExecutorService pool = Executors.newFixedThreadPool(
                concurrency, Thread.ofVirtual().factory())) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            ItemRunContext itemCtx = new ItemRunContext(finalItems, rows, taskRunner,
                    hallucination, keypointCoverage, judge, ts, judgeTokens,
                    retriedItemsRef, itemErrorsRef);
            for (int i = 0; i < finalItems.size(); i++) {
                final int idx = i;
                final BenchmarkItem item = finalItems.get(i);
                futures.add(CompletableFuture.runAsync(
                        () -> runOneItem(idx, item, itemCtx), pool));
            }
            for (CompletableFuture<Void> f : futures) {
                f.join();
            }
        }
        int judgeFailures = itemErrorsRef.get();
        int retriedItems = retriedItemsRef.get();

        // 阶段 2：按原顺序聚合与落盘（多线程 append 会损坏 jsonl，统一主线程写）
        for (int i = 0; i < finalItems.size(); i++) {
            ObjectNode row = rows[i];
            if (!"SUCCEEDED".equals(row.path("status").asText())) {
                // 失败明细打印（含 errorDetail）——失败原因可诊断，不再黑盒靠猜
                System.out.println("    ✗ " + row.path("id").asText() + " " + row.path("status").asText()
                        + (row.has("error") ? " | " + row.path("error").asText("") : ""));
            }
            if ("SUCCEEDED".equals(row.path("status").asText())) {
                succeeded++;
            } else {
                failed++;
            }
            totalCost += row.path("researchCostUsd").asDouble(0);
            if (row.has("researchElapsedSec")) {
                latencySec.add(row.path("researchElapsedSec").asDouble());
            }
            costUsdList.add(row.path("researchCostUsd").asDouble(0));
            totalJudgeTokensRef[0] = judgeTokens.get();
            // 聚合
            if (row.has("citationConsistency") && row.path("inTextCitations").asInt(0) > 0) {
                d1Sum += row.path("citationConsistency").asDouble();
                d1Count++;
            }
            uncitedRefsSum += row.path("uncitedRefs").asInt(0);
            danglingSum += row.path("danglingCitations").asInt(0);
            if (row.path("danglingCitations").asInt(0) > 0) {
                danglingItems++;
            }
            if (row.path("leak").asBoolean(false)) {
                leakItems++;
                if (row.path("leakExcluded").asBoolean(false)) {
                    leakExcludedCorrect++;
                }
            }
            if (row.has("kaeKeypoints")) {
                int n = row.path("kaeKeypoints").asInt(0);
                if (n > 0 && row.has("ksr")) {
                    kaeCount++;
                    ksrSum += row.path("ksr").asDouble();
                    kcrSum += row.path("kcr").asDouble();
                    korSum += row.path("kor").asDouble();
                    kaeEffSum += row.path("efficiency").asDouble(Double.NaN);
                }
            }
            if (row.has("accuracy") && row.path("accuracy").isBoolean()
                    && !row.path("leakExcluded").asBoolean(false)) {
                boolean correct = row.path("accuracy").asBoolean();
                bucket(accBuckets, "all", correct);
                bucket(accBuckets, row.path("mode").asText(), correct);
                bucket(accBuckets, row.path("lang").asText(), correct);
                bucket(accBuckets, "cat:" + row.path("cat").asText("general"), correct);
            }
            // 全样本意图作答口径桶（失败任务显式入分母，防条件准确率被断章）
            if (judge != null) {
                aggregateStrict(row, accBuckets);
            }
            // H3：管线答"过时值"统计（对标 py wrong_stale）
            if (row.has("verdict") && "stale".equals(row.path("verdict").asText())) {
                bucket(staleBuckets, "all", true);
                bucket(staleBuckets, "cat:" + row.path("cat").asText("general"), true);
            }
            // H2：直接作答基线（correct/stale 分别聚）
            if (row.has("directAccuracy") && row.path("directAccuracy").isBoolean()) {
                boolean dc = row.path("directAccuracy").asBoolean();
                bucket(directBuckets, "all", dc);
                bucket(directBuckets, "cat:" + row.path("cat").asText("general"), dc);
                bucket(directBuckets, row.path("mode").asText(), dc);
                if ("stale".equals(row.path("directVerdict").asText())) {
                    bucket(directStaleBuckets, "all", true);
                    bucket(directStaleBuckets, "cat:" + row.path("cat").asText("general"), true);
                }
            }
            if (row.has("hallucinationClaims")) {
                int claims = row.path("hallucinationClaims").asInt(0);
                if (claims > 0) {
                    hallClaims += claims;
                    hallUnsupported += row.path("hallucinationUnsupported").asInt(0);
                    hallJudgedItems++;
                }
                hallInconclusive += row.path("hallucinationInconclusive").asInt(0);
                hallNoCitation += row.path("hallucinationNoCitation").asInt(0);
                hallUnverifiable += row.path("hallucinationUnverifiable").asInt(0);
            }
            writer.appendItem(row);
        }

        ObjectNode metrics = MAPPER.createObjectNode();
        metrics.put("runId", runId);
        metrics.put("items", finalItems.size());
        metrics.put("excludedItems", excludedIds.size());
        metrics.put("excludedIds", String.join(",", excludedIds));
        metrics.put("succeeded", succeeded);
        metrics.put("failed", failed);
        metrics.put("retriedItems", retriedItems);
        metrics.put("judgeFailures", judgeFailures);
        metrics.put("totalCostUsd", round(totalCost));
        metrics.put("totalElapsedSec", (System.currentTimeMillis() - totalStart) / 1000);
        // H1：每题研究延迟/成本的分位数（对标 py SimpleQA 线的 p50/p95）
        metrics.put("researchLatencySecP50", round(percentile(latencySec, 0.50)));
        metrics.put("researchLatencySecP95", round(percentile(latencySec, 0.95)));
        metrics.put("researchCostUsdP50", round(percentile(costUsdList, 0.50)));
        metrics.put("researchCostUsdP95", round(percentile(costUsdList, 0.95)));
        metrics.put("judgeTokens", totalJudgeTokensRef[0]);
        metrics.put("avgCitationConsistency",
                round(d1Count == 0 ? Double.NaN : d1Sum / d1Count));
        // D1 的两个方向各自成数：dangling = 正文引用了表外编号的题数；
        // uncitedRefs = 各题"参考文献从未被正文引用"条数之和（凑数/灌水信号）。
        metrics.put("danglingItems", danglingItems);
        metrics.put("danglingCitations", danglingSum);
        metrics.put("uncitedRefs", uncitedRefsSum);

        ArrayNode acc = metrics.putArray("accuracyBy");
        writeBuckets(acc, accBuckets);
        // 口径注释（accuracyBy[all] = 成功样本条件准确率；all-strict = 意图作答
        // 口径，失败/未完成计 incorrect）
        metrics.put("accuracyNote",
                "accuracyBy[all]=conditional on succeeded; all-strict=intent-to-answer "
                        + "(failed tasks counted as incorrect)");
        // H2：A/B 增量——direct 基线正确率（研究增量 = accuracyBy[all] - directBy[all]）
        ArrayNode direct = metrics.putArray("directBy");
        writeBuckets(direct, directBuckets);
        // H3：wrong_stale——管线/直接作答答"过时值"的题数
        ArrayNode stale = metrics.putArray("staleBy");
        writeBuckets(stale, staleBuckets);
        ArrayNode directStale = metrics.putArray("directStaleBy");
        writeBuckets(directStale, directStaleBuckets);
        // D2 加权聚合（按 judged 句数加权，不再条目等权）。指标口径：
        // hallucinationUnsupported = 确认矛盾（contradictory），inconclusive 单列；
        // weightedHallucinationRate = 保守下限（矛盾/可判），uncertainRate = 含"无法证实"上限
        metrics.put("hallucinationClaims", hallClaims);
        metrics.put("hallucinationUnsupported", hallUnsupported);
        metrics.put("hallucinationInconclusive", hallInconclusive);
        metrics.put("weightedHallucinationRate",
                round(hallClaims == 0 ? -1 : (double) hallUnsupported / hallClaims));
        metrics.put("weightedUncertainRate",
                round(hallClaims == 0 ? -1 : (double) (hallUnsupported + hallInconclusive) / hallClaims));
        metrics.put("hallucinationNoCitationClaims", hallNoCitation);
        metrics.put("hallucinationUnverifiableClaims", hallUnverifiable);
        metrics.put("hallucinationJudgedItems", hallJudgedItems);
        // KAE-lite 聚合（有 keypoints 的开放题均值；Efficiency 分母为 0 的题不计均值）
        metrics.put("kaeItems", kaeCount);
        metrics.put("kaeKsrAvg", round(kaeCount == 0 ? Double.NaN : ksrSum / kaeCount));
        metrics.put("kaeKcrAvg", round(kaeCount == 0 ? Double.NaN : kcrSum / kaeCount));
        metrics.put("kaeKorAvg", round(kaeCount == 0 ? Double.NaN : korSum / kaeCount));
        metrics.put("kaeEfficiencyAvg", round(kaeCount == 0 ? Double.NaN : kaeEffSum / kaeCount));
        // 泄漏上报（Bench II）：引用 blocked 源的题数/被剔除的"答对"题数
        metrics.put("leakItems", leakItems);
        metrics.put("leakRate", round(items.isEmpty() ? Double.NaN : (double) leakItems / items.size()));
        metrics.put("leakExcludedCorrect", leakExcludedCorrect);
        // micro-gold judge 自检（本次评测判官的可信度；DeepFact：先测 judge 再信分）。
        // 节奏纪律：--no-microgold 供 L1/L2 冒烟档跳过（省 ~1min/趟），L3 质量档必带。
        if (judge != null && !skipMicroGold) {
            try {
                MicroGold.Report mg = MicroGold.run(judge, 5);
                ObjectNode gold = metrics.putObject("microGold");
                gold.put("judgeAccuracy", round(mg.accuracy()));
                gold.put("supported", mg.supportedCaught() + "/" + mg.supportedExpected());
                gold.put("contradictoryCaught", mg.contradictionCaught() + "/" + mg.contradictionExpected());
                gold.put("inconclusiveCaught", mg.inconclusiveCaught() + "/" + mg.inconclusiveExpected());
                gold.put("hallucinationMissRate", round(mg.hallucinationMissRate()));
                gold.put("dropped", mg.dropped());
                gold.put("batchesFailed", mg.batchesFailed());
                // D3（AnswerAccuracyJudge）自检——否定/转折陷阱检出
                try {
                    MicroGold.D3Report d3 = MicroGold.runD3(judge);
                    gold.put("d3JudgeAccuracy", round(d3.accuracy()));
                    gold.put("d3Correct", d3.correct() + "/" + d3.total());
                    gold.put("d3Dropped", d3.dropped());
                    gold.put("d3BatchesFailed", d3.batchesFailed());
                } catch (Exception e3) {
                    gold.put("d3", "judge-probe-failed: " + e3.getMessage());
                }
            } catch (Exception e) {
                metrics.put("microGold", "judge-probe-failed: " + e.getMessage());
            }
        }

        writer.writeMetrics(metrics);
        System.out.println("聚合指标（" + writer.dir() + "）：");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metrics));
    }

    /** 单条处理（异常隔离在调用方）。tokenRef 为并发安全的 judge token 计数。 */
    /** 排除判定（原 filter lambda 体逐字搬入）；命中则把 id 记入 excludedIds 供日志汇总。 */
    private static boolean keepItem(BenchmarkItem it, List<String> excludePrefixes,
                                    List<String> excludeCats, List<String> excludedIds) {
        boolean drop = excludePrefixes.stream().anyMatch(it.id()::startsWith)
                || excludeCats.contains(it.cat());
        if (drop) {
            excludedIds.add(it.id());
        }
        return !drop;
    }

    /** 单题评测的共享依赖（原 runAsync lambda 闭包捕获的变量打包，避免 12 参数方法）。 */
    private record ItemRunContext(List<BenchmarkItem> allItems, ObjectNode[] rows,
                                  TaskRunner taskRunner, HallucinationJudge hallucination,
                                  KeypointCoverage keypointCoverage, JudgeClient judge,
                                  long ts, AtomicLong judgeTokens,
                                  AtomicInteger retriedItems, AtomicInteger itemErrors) {
    }

    /** 评测单题并写入 rows[idx]（原 runAsync lambda 体逐字搬入）。
     *  TRANSIENT 失败自动重试一次；条目异常隔离在本题内，不中断整轮。 */
    private static void runOneItem(int idx, BenchmarkItem item, ItemRunContext ctx) {
        System.out.printf("[%d/%d] %s (%s/%s) %s%n", idx + 1, ctx.allItems().size(),
                item.id(), item.mode(), item.lang(), item.query());
        ObjectNode row = MAPPER.createObjectNode();
        ctx.rows()[idx] = row;
        row.put("id", item.id());
        row.put("query", item.query());
        row.put("mode", item.mode());
        row.put("lang", item.lang());
        row.put("cat", item.cat());
        row.put("hasGold", item.hasGold());
        try {
            processItem(item, row, ctx, ctx.ts() + idx);
            // 失败自动重试一次（瞬时错误按题聚集，重试即恢复）
            if ("FAILED".equals(row.path("status").asText())
                    && row.path("error").asText("").contains("TRANSIENT")) {
                System.out.printf("    → %s TRANSIENT 失败，自动重试一次%n", item.id());
                ctx.retriedItems().incrementAndGet();
                processItem(item, row, ctx, ctx.ts() + idx + 1000L);
            }
        } catch (Exception e) {
            ctx.itemErrors().incrementAndGet();
            System.out.println("    → 条目异常（已隔离，不中断整轮）: " + e.getMessage());
            row.put("status", "ITEM_ERROR");
            row.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 评测单题并写入 {@code row}。依赖经 {@code ctx} 传入（{@link ItemRunContext} 本为此而建）；
     *  {@code ts} **保留为显式参数**——它是"本题已偏移的 clientKey 时间戳"
     *  （首跑 {@code ctx.ts()+idx}、重试再 {@code +1000}），非 {@code ctx.ts()} 本身。 */
    private static void processItem(BenchmarkItem item, ObjectNode row, ItemRunContext ctx, long ts) {
        String clientKey = "bench-" + ts + "-" + item.id();
        // 任务 config 注入 blockedUrls → 产品检索/抓取层直接屏蔽（防"引用源文答题"泄漏）
        TaskRunner.ResearchOutcome r = ctx.taskRunner().runWithConfig(item.query(), configFor(item),
                clientKey, 300);
        row.put("taskId", r.taskId() == null ? "" : r.taskId());
        row.put("researchCostUsd", r.costUsd());
        row.put("researchElapsedSec", r.elapsedSec());

        if (!r.succeeded() || r.report().isBlank()) {
            row.put("status", "FAILED");
            row.put("error", r.error() == null ? "empty report" : r.error());
            return;
        }
        row.put("status", "SUCCEEDED");

        // D1 引用一致性（零 LLM）：consistency 量编号闭环，uncitedRefs 量表内多余条目
        CitationConsistency.Result d1 = CitationConsistency.calculate(r.report());
        row.put("inTextCitations", d1.inTextCitations());
        row.put("danglingCitations", d1.dangling());
        row.put("uncitedRefs", d1.uncitedRefs());
        row.put("citationConsistency", round(d1.consistency()));

        // 泄漏检测（Bench II）——报告文本出现 blocked 条目（URL 前缀/域名即命中；
        // 检索层已屏蔽，出现即穿透信号）
        if (item.hasBlocked()) {
            List<String> hit = new ArrayList<>();
            String reportLower = r.report().toLowerCase();
            for (String b : item.blocked()) {
                if (reportLower.contains(b.toLowerCase())) {
                    hit.add(b);
                }
            }
            if (!hit.isEmpty()) {
                row.put("leak", true);
                row.put("leakedBlocks", MAPPER.valueToTree(hit).toString());
            }
        }

        if (item.hasGold() && ctx.judge() != null) {
            // D3 客观题（两步判定 + H3 三分类 verdict：correct/stale/incorrect）
            AnswerAccuracyJudge.Verdict v = AnswerAccuracyJudge.judge(item, r.report(), ctx.judge());
            ctx.judgeTokens().addAndGet(ctx.judge().lastTokens());
            if (v == null) {
                row.put("accuracy", "judge-unparsable");
            } else {
                row.put("verdict", v.verdict());
                row.put("accuracy", v.isCorrect());
                row.put("answerable", v.answerable());
                row.put("reportedAnswer", ReportText.truncate(v.reportedAnswer(), 300));
                row.put("reportedInReport", v.reportedInReport());
                // 泄漏剔除：引用被禁来源且答对 → 判定不可信，不进入准确率（Bench II）
                if (row.path("leak").asBoolean(false) && v.isCorrect()) {
                    row.put("leakExcluded", true);
                }
            }
            // H2：A/B 增量基线——同题不研究直接作答（同模型同 harness，对标 py benchmark.py）
            try {
                String directAnswer = ctx.judge().chat(DIRECT_SYSTEM, item.query());
                ctx.judgeTokens().addAndGet(ctx.judge().lastTokens());
                AnswerAccuracyJudge.Verdict dv =
                        AnswerAccuracyJudge.judgeDirect(item, directAnswer, ctx.judge());
                if (dv == null) {
                    row.put("directVerdict", "judge-unparsable");
                } else {
                    row.put("directVerdict", dv.verdict());
                    row.put("directAccuracy", dv.isCorrect());
                    row.put("directAnswer", ReportText.truncate(dv.reportedAnswer(), 200));
                }
            } catch (Exception e) {
                row.put("directVerdict", "direct-failed");
            }
        } else if (!item.hasGold() && ctx.judge() != null) {
            // D2 幻觉率（开放题；句子-引用源绑定 + 三态反证判定）
            HallucinationJudge.Verdict v = ctx.hallucination().judge(item, r.report(), ctx.judge());
            ctx.judgeTokens().addAndGet(ctx.judge().lastTokens());
            if (v == null) {
                row.put("hallucination", "no-sentences");
            } else {
                row.put("hallucinationClaims", v.judged());
                row.put("hallucinationUnsupported", v.contradictory());
                row.put("hallucinationInconclusive", v.inconclusive());
                row.put("hallucinationNoCitation", v.noCitation());
                row.put("hallucinationUnverifiable", v.unverifiable());
                row.put("judgeDropped", v.dropped());
                row.put("judgeBatchesFailed", v.batchesFailed());
                if (v.hallucinationRate() >= 0) {
                    row.put("hallucination", round(v.hallucinationRate()));
                } else {
                    row.put("hallucination", "no-judgable-claims");
                }
                if (v.uncertainRate() >= 0) {
                    row.put("uncertainRate", round(v.uncertainRate()));
                }
            }
            // KAE-lite 引用覆盖（引了是否写全/写对；与 D2 互补）
            try {
                KeypointCoverage.Result kae = ctx.keypointCoverage().calculate(r.report(), ctx.judge());
                if (kae == null) {
                    row.put("kae", "no-sources-or-keypoints");
                } else {
                    row.put("kaeKeypoints", kae.keypointsDeduped());
                    row.put("kaeSources", kae.sourcesFetched());
                    row.put("kaeFetchFailed", kae.fetchFailed());
                    row.put("ksr", round(kae.ksr()));
                    row.put("kcr", round(kae.kcr()));
                    row.put("kor", round(kae.kor()));
                    row.put("efficiency", Double.isNaN(kae.efficiency()) ? -1 : round(kae.efficiency()));
                    row.put("kaeAbstain", kae.abstain());
                    row.put("kaeDropped", kae.dropped());
                }
            } catch (Exception e) {
                row.put("kae", "kae-error: " + e.getMessage());
            }
        } else if (item.hasGold()) {
            row.put("accuracy", "judge-not-configured");
        } else {
            row.put("hallucination", "judge-not-configured");
        }
    }

    private static void bucket(Map<String, double[]> buckets, String key, boolean correct) {
        double[] b = buckets.computeIfAbsent(key, k -> new double[]{0, 0});
        b[0]++;
        if (correct) {
            b[1]++;
        }
    }

    /**
     * 全样本"意图作答"口径（桶键 {@code all-strict}，与条件桶并列输出）。
     *
     * <p>语义：hasGold 题中——任务失败/未完成（FAILED/ITEM_ERROR）= 未作答 = 计 incorrect
     * 显式入分母；SUCCEEDED 但 judge 未产出布尔判定（judge-unparsable/not-configured）
     * 与条件桶同规则剔除（无判定能力不算错也不算对）；leakExcluded（引用被禁源且答对）
     * 剔除（作答不可信，与条件桶一致）。
     */
    static void aggregateStrict(JsonNode row, Map<String, double[]> buckets) {
        if (!row.path("hasGold").asBoolean(false)) {
            return;
        }
        if (row.path("leakExcluded").asBoolean(false)) {
            return;
        }
        boolean succeeded = "SUCCEEDED".equals(row.path("status").asText());
        if (succeeded && !row.path("accuracy").isBoolean()) {
            return; // judge 判定缺失：剔除（口径同条件桶）
        }
        boolean correct = succeeded && row.path("accuracy").asBoolean();
        bucket(buckets, "all-strict", correct);
    }

    /** 桶 → accuracyBy 结构（n/accuracy/Wilson CI）。stale 桶的"correct"计数即 stale 数。 */
    private static void writeBuckets(ArrayNode out, Map<String, double[]> buckets) {
        for (Map.Entry<String, double[]> e : buckets.entrySet()) {
            int n = (int) e.getValue()[0];
            int correct = (int) e.getValue()[1];
            double[] ci = Wilson.ci95(n, correct);
            ObjectNode o = out.addObject();
            o.put("bucket", e.getKey());
            o.put("n", n);
            o.put("accuracy", round((double) correct / n));
            o.put("ci95Low", round(ci[0]));
            o.put("ci95High", round(ci[1]));
        }
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** 题目基础引擎配置 + --config 附加合并 + blockedUrls 注入（泄漏防护走产品全链路）。 */
    private static String configFor(BenchmarkItem item) {
        String base = "flat".equalsIgnoreCase(item.mode())
                ? TaskRunner.CONFIG_FLAT : TaskRunner.CONFIG_DEEP;
        try {
            ObjectNode cfg = (ObjectNode) MAPPER.readTree(base);
            if (extraConfigJson != null && !extraConfigJson.isBlank()) {
                JsonNode extra = MAPPER.readTree(extraConfigJson);
                if (extra.isObject()) {
                    extra.fields().forEachRemaining(e -> cfg.set(e.getKey(), e.getValue()));
                }
            }
            if (item.hasBlocked()) {
                ArrayNode arr = cfg.putArray("blockedUrls");
                item.blocked().forEach(arr::add);
            }
            return cfg.toString();
        } catch (Exception e) {
            // 配置构造失败 → 回退基线 config（评测不因单题配置异常中断）
            return base;
        }
    }

    /** 线性插值分位数（H1；空列表返回 NaN）。 */
    public static double percentile(List<Double> values, double q) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (q <= 0) {
            return sorted.get(0);
        }
        if (q >= 1) {
            return sorted.get(n - 1);
        }
        double pos = q * (n - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return sorted.get(lo);
        }
        return sorted.get(lo) + (pos - lo) * (sorted.get(hi) - sorted.get(lo));
    }

    /**
     * 解析 {@code --key value} 与无值开关 {@code --flag}（置为 {@code "true"}）。
     *
     * <p>对评测入口而言，"参数被静默忽略"意味着**实际跑的与以为跑的不是一回事**，
     * 故本实现**按位扫描且不静默**：非 {@code --} 开头直接报错，
     * 下一参数不以 {@code --} 开头时作为取值，否则视作无值开关。
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
