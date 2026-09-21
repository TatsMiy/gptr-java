package com.gptr.benchmark.run;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.integration.storage.LocalReportStorage;
import com.gptr.integration.storage.MinioReportStorage;
import com.gptr.integration.storage.ReportStorage;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * 经产品真实路径跑一次研究：POST /api/v1/tasks → 轮询终态 → 读报告（resultRef）。
 * 测的是"真实用户路径"（队列/预算/抓取/写作全链路），而非引擎直调。
 */
public class TaskRunner {

    /** flat/deep 的引擎 config（fetchFullPage 默认开，测真实抓取）。 */
    public static final String CONFIG_FLAT = "{\"maxSubQueries\":2}";
    public static final String CONFIG_DEEP = "{\"mode\":\"deep_research\",\"breadth\":2,\"depth\":1}";

    private final String apiBase;
    private final ReportStorage storage;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 默认本地存储读取（ref = local://<abs path>，与本地评测一致）。 */
    public TaskRunner(String apiBase) {
        this(apiBase, new LocalReportStorage(Path.of(".")));
    }

    /** @param storage 报告读取抽象（②：MinIO 部署下 ref 为 minio://…，统一走 storage）。 */
    public TaskRunner(String apiBase, ReportStorage storage) {
        this.apiBase = apiBase;
        this.storage = storage;
    }

    /** 评测 CLI 存储选择：--storage local(默认)|minio（MinIO 参数可经 CLI 或
     *  MINIO_* 环境变量覆盖，默认与 worker ReportStorageConfig 一致）。 */
    public static ReportStorage storageFrom(Map<String, String> opt) {
        String mode = opt.getOrDefault("storage", "local");
        if ("minio".equalsIgnoreCase(mode)) {
            String endpoint = opt.getOrDefault("minio-endpoint",
                    envOr("MINIO_ENDPOINT", "http://localhost:9000"));
            String accessKey = opt.getOrDefault("minio-access-key",
                    envOr("MINIO_ACCESS_KEY", "gptr"));
            String secretKey = opt.getOrDefault("minio-secret-key",
                    envOr("MINIO_SECRET_KEY", "gptr12345"));
            String bucket = opt.getOrDefault("minio-bucket",
                    envOr("MINIO_BUCKET", "gptr-reports"));
            return new MinioReportStorage(endpoint, accessKey, secretKey, bucket);
        }
        return new LocalReportStorage(Path.of("."));
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** 单次研究结果：报告文本、成本（USD）、耗时秒、状态。 */
    public record ResearchOutcome(String taskId, String report, double costUsd, long elapsedSec,
                                  boolean succeeded, String error) {
    }

    public ResearchOutcome run(String query, String mode, String clientKey, long timeoutSec) {
        String config = "flat".equalsIgnoreCase(mode) ? CONFIG_FLAT : CONFIG_DEEP;
        return runWithConfig(query, config, clientKey, timeoutSec);
    }

    /** A/B 盲判：用任意引擎 config JSON 跑一次研究（CONFIG_* 常量之外的自定义对比）。 */
    public ResearchOutcome runWithConfig(String query, String configJson, String clientKey,
                                         long timeoutSec) {
        String taskId;
        long started = System.currentTimeMillis();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("query", query);
            body.put("config", configJson);
            body.put("clientKey", clientKey);
            String resp = post("/api/v1/tasks", body);
            taskId = mapper.readTree(resp).path("taskId").asText();
        } catch (Exception e) {
            return new ResearchOutcome(null, "", 0, 0, false, "create failed: " + e.getMessage());
        }
        long deadline = System.currentTimeMillis() + timeoutSec * 1000;
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(5000);
                String view = get("/api/v1/tasks/" + taskId);
                JsonNode node = mapper.readTree(view);
                String status = node.path("status").asText();
                if ("SUCCEEDED".equals(status)) {
                    String ref = node.path("resultRef").asText("");
                    String report = readReport(ref);
                    double cost = node.path("costSpentUsd").asDouble(0);
                    return new ResearchOutcome(taskId, report, cost,
                            (System.currentTimeMillis() - started) / 1000, true, null);
                }
                if ("FAILED".equals(status) || "BUDGET_STEP_EXCEEDED".equals(status)
                        || "BUDGET_TIME_EXCEEDED".equals(status) || "BUDGET_COST_EXCEEDED".equals(status)
                        || "CANCELLED".equals(status)) {
                    // 失败任务也已消耗成本：读回 costSpentUsd（总成本不再系统性低估）
                    double cost = node.path("costSpentUsd").asDouble(0);
                    // errorDetail 并入错误文本——失败原因可诊断（黑盒修复，不再靠猜）
                    String why = "task " + status + ": " + node.path("errorCode").asText("");
                    String detail = node.path("errorDetail").asText("");
                    if (!detail.isBlank()) {
                        why += " | detail: " + detail;
                    }
                    return new ResearchOutcome(taskId, "", cost,
                            (System.currentTimeMillis() - started) / 1000, false, why);
                }
            }
            return new ResearchOutcome(taskId, "", 0, timeoutSec, false, "timeout");
        } catch (Exception e) {
            return new ResearchOutcome(taskId, "", 0,
                    (System.currentTimeMillis() - started) / 1000, false, "poll failed: " + e.getMessage());
        }
    }

    /** 读报告：resultRef 经存储抽象统一读取（local:// 或 minio:// 均支持）。
     *  读取失败抛异常（调用方按失败任务处理，与本地直读语义一致）。 */
    private String readReport(String ref) throws IOException {
        if (ref == null || ref.isBlank()) {
            return "";
        }
        try {
            return storage.get(ref);
        } catch (RuntimeException e) {
            throw new IOException("read report via storage failed: " + e.getMessage(), e);
        }
    }

    private String post(String path, ObjectNode body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("POST " + path + " -> " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private String get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("GET " + path + " -> " + resp.statusCode());
        }
        return resp.body();
    }
}
