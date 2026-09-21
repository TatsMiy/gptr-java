package com.gptr.benchmark.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LLM judge 客户端（与被测 LLM 分离）：OpenAI 兼容 /chat/completions，
 * response_format=json_object，temperature=0。judge 模型/端点/密钥可独立配置。
 */
public class JudgeClient {

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private long lastCostUsdEstimate = 0; // 仅按 token 粗估（judge 成本不计入被测任务）

    public JudgeClient(String baseUrl, String model, String apiKey) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
    }

    /** 调用 judge 并解析 JSON 响应（content 可能带 markdown 围栏，剥除后解析）。
     *  瞬态失败（429/5xx/连接类）指数退避重试 3 次；4xx 语义错误直接抛。 */
    public JsonNode chatJson(String system, String user) {
        return doCall(system, user, true);
    }

    /** H2（A/B 增量基线）：纯文本回答（无 response_format 约束）——"被测直接作答"通道。 */
    public String chat(String system, String user) {
        JsonNode root = doCall(system, user, false);
        if (root == null) {
            return "";
        }
        return root.path("choices").path(0).path("message").path("content").asText("");
    }
    private JsonNode doCall(String system, String user, boolean jsonMode) {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return doCallOnce(system, user, jsonMode);
            } catch (RetryableJudgeException e) {
                last = e;
                try {
                    Thread.sleep(1000L * (attempt + 1)); // 1s / 2s 退避
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("judge call interrupted", ie);
                }
            }
        }
        throw new IllegalStateException("judge failed after 3 attempts: " + last, last);
    }

    /** 瞬态失败（内部标记，可重试）。 */
    private static final class RetryableJudgeException extends Exception {
        RetryableJudgeException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    private JsonNode doCallOnce(String system, String user, boolean jsonMode) throws RetryableJudgeException {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0);
            if (jsonMode) {
                ObjectNode fmt = body.putObject("response_format");
                fmt.put("type", "json_object");
            }
            ArrayNode messages = body.putArray("messages");
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", system);
            ObjectNode usr = messages.addObject();
            usr.put("role", "user");
            usr.put("content", user);

            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status == 429 || status >= 500) {
                throw new RetryableJudgeException("judge http " + status, null);
            }
            if (status >= 400) {
                throw new IllegalStateException("judge http " + status + ": "
                        + resp.body().substring(0, Math.min(200, resp.body().length())));
            }
            JsonNode root = mapper.readTree(resp.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            // usage（DeepSeek 等返回时粗记，仅日志）
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                lastCostUsdEstimate = usage.path("prompt_tokens").asLong(0)
                        + usage.path("completion_tokens").asLong(0);
            }
            // JSON 模式：返回解析后的 content（剥围栏）；纯文本模式：直接返回响应体 root
            if (jsonMode) {
                return parseJsonContent(content);
            }
            return root;
        } catch (RetryableJudgeException e) {
            throw e;
        } catch (Exception e) {
            throw new RetryableJudgeException("judge call failed: " + e.getMessage(), e);
        }
    }

    public long lastTokens() {
        return lastCostUsdEstimate;
    }

    /** 剥 markdown 代码围栏后解析 JSON；失败返回 null（调用方跳过该条）。 */
    public static JsonNode parseJsonContent(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            int first = text.indexOf('\n');
            int last = text.lastIndexOf("```");
            if (first >= 0 && last > first) {
                text = text.substring(first + 1, last).trim();
            }
        }
        try {
            return new ObjectMapper().readTree(text);
        } catch (Exception e) {
            // 判官输出非合法 JSON → 返回 null（调用方记 judge-unparsable，不中断整批）
            return null;
        }
    }
}
