package com.gptr.benchmark;


import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.benchmark.dims.MicroGold;
import com.gptr.benchmark.llm.JudgeClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ⑤ judge 消融矩阵（对标 DeepResearch Bench II 的 judge 消融表）。
 *
 * <p>Bench II 结论：判官模型选择对 rubric 判定质量的影响远大于批大小
 * （批 50: ACC 91.75/F1 89.57/$0.25；换判官 F1 掉到 78.28）。本工具用 {@link MicroGold}
 * 探针（有机器锚定的期望标签，无需人类标注）在 {判官模型} × {批大小} 网格上复现该结论：
 * 每个配置跑一次 judge 自检，输出 ACC / 幻觉漏检率 / judge tokens 成本估计。
 *
 * <p>用法（classpath 同 BenchmarkMain）：
 * <pre>
 * java -cp ... com.gptr.benchmark.JudgeCalibrationMain \
 *   --models deepseek-chat --batch-sizes 5,10,0 --out results/calib
 * </pre>
 * --batch-sizes 0 = 全部一次（30 条）。判官模型可并列多个（如 --models a,b）；
 * 模型需支持 response_format=json_object（deepseek-reasoner 若不支持会报错——由服务端决定）。
 */
public final class JudgeCalibrationMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        String judgeBase = opt.getOrDefault("judge-base", "https://api.deepseek.com");
        String[] models = opt.getOrDefault("models", "deepseek-chat").split(",");
        String[] batchStrs = opt.getOrDefault("batch-sizes", "5,10,0").split(",");
        String judgeKey = opt.getOrDefault("judge-key",
                System.getenv("DEEPSEEK_API_KEY") == null ? "" : System.getenv("DEEPSEEK_API_KEY"));
        String outBase = opt.getOrDefault("out", "results");
        String runId = "calib_" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        if (judgeKey.isBlank()) {
            throw new IllegalArgumentException("需要 --judge-key 或环境 DEEPSEEK_API_KEY");
        }

        Path dir = Path.of(outBase, runId);
        Files.createDirectories(dir);
        ObjectNode metrics = MAPPER.createObjectNode();
        metrics.put("runId", runId);
        metrics.put("judgeBase", judgeBase);
        metrics.put("probeCases", MicroGold.fixtures().size());
        metrics.put("probeSupportedToNon",
                "≈1:4（6 supported / 17 contradictory / 7 inconclusive）");
        ArrayNode rows = metrics.putArray("matrix");
        for (String model : models) {
            String m = model.trim();
            if (m.isEmpty()) {
                continue;
            }
            for (String bs : batchStrs) {
                int batchSize = Integer.parseInt(bs.trim());
                if (batchSize < 0) {
                    continue;
                }
                int effective = batchSize == 0 ? 1000 : batchSize;
                JudgeClient judge = new JudgeClient(judgeBase, m, judgeKey);
                MicroGold.Report r = MicroGold.run(judge, effective);
                ObjectNode row = rows.addObject();
                row.put("judgeModel", m);
                row.put("batchSize", batchSize == 0 ? "all" : String.valueOf(batchSize));
                row.put("accuracy", Math.round(r.accuracy() * 10000.0) / 10000.0);
                row.put("contradictionCaught",
                        r.contradictionCaught() + "/" + r.contradictionExpected());
                row.put("hallucinationMissRate",
                        Math.round(r.hallucinationMissRate() * 10000.0) / 10000.0);
                row.put("supportedCaught", r.supportedCaught() + "/" + r.supportedExpected());
                row.put("inconclusiveCaught", r.inconclusiveCaught() + "/" + r.inconclusiveExpected());
                row.put("dropped", r.dropped());
                row.put("batchesFailed", r.batchesFailed());
                System.out.printf("judge=%s batch=%s acc=%.4f missRate=%.4f (S %d/%d, C %d/%d, I %d/%d)%n",
                        m, batchSize == 0 ? "all" : String.valueOf(batchSize), r.accuracy(),
                        r.hallucinationMissRate(), r.supportedCaught(), r.supportedExpected(),
                        r.contradictionCaught(), r.contradictionExpected(),
                        r.inconclusiveCaught(), r.inconclusiveExpected());
            }
        }
        Files.writeString(dir.resolve("metrics.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metrics),
                StandardCharsets.UTF_8);
        System.out.println("消融矩阵： " + dir);
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
