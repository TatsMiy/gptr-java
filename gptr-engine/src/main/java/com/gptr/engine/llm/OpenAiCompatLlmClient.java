package com.gptr.engine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gptr.engine.HttpDefaults;
import com.gptr.integration.client.LlmClient;
import com.gptr.integration.exception.PermanentApiException;
import com.gptr.integration.exception.QuotaApiException;
import com.gptr.integration.exception.TransientApiException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI 兼容 LLM 客户端（默认 DeepSeek）。
 *
 * <p>{@code baseUrl} / {@code apiKey} / {@code model} 可配置，切任何 OpenAI 兼容服务
 * （DeepSeek / OpenAI / 通义 / Kimi 等）。非流式 chat completions：
 * <ul>
 *   <li>{@link #chat} 普通补全；{@link #chatJson} 附加 {@code response_format: json_object}</li>
 *   <li>错误分类：429 → {@link QuotaApiException}；5xx → {@link TransientApiException}；
 *       其他 4xx → {@link PermanentApiException}</li>
 *   <li>{@link #lastCallCostUsd} 按 usage tokens × 单价估算</li>
 * </ul>
 */
public class OpenAiCompatLlmClient implements LlmClient {

    /** chat completions 单请求超时（秒）——LLM 生成最慢，故显著长于检索与抓取。 */
    private static final int LLM_REQUEST_TIMEOUT_SECONDS = 120;

    private final String name;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double inputPricePerMTok;
    private final double outputPricePerMTok;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(HttpDefaults.CONNECT_TIMEOUT_SECONDS)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile double lastCallCostUsd;

    public OpenAiCompatLlmClient(String name, String baseUrl, String apiKey, String model) {
        this(name, baseUrl, apiKey, model, 0.27, 1.10); // DeepSeek-chat 近似单价（$/M tokens）
    }

    public OpenAiCompatLlmClient(String name, String baseUrl, String apiKey, String model,
                                 double inputPricePerMTok, double outputPricePerMTok) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.inputPricePerMTok = inputPricePerMTok;
        this.outputPricePerMTok = outputPricePerMTok;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return call(systemPrompt, userPrompt, false);
    }

    @Override
    public String chatJson(String systemPrompt, String userPrompt) {
        return call(systemPrompt, userPrompt, true);
    }

    @Override
    public double lastCallCostUsd() {
        return lastCallCostUsd;
    }

    private String call(String systemPrompt, String userPrompt, boolean jsonMode) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);
            if (jsonMode) {
                body.putObject("response_format").put("type", "json_object");
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(LLM_REQUEST_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 429) {
                throw new QuotaApiException(name, "rate limited: " + status);
            }
            if (status >= 500) {
                throw new TransientApiException(name, "server error: " + status);
            }
            if (status >= 400) {
                throw new PermanentApiException(name, "http " + status + ": " + truncate(response.body()));
            }

            JsonNode root = mapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null) {
                throw new TransientApiException(name, "empty completion in response");
            }
            // 成本核算（usage tokens × 单价）
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                long promptTokens = usage.path("prompt_tokens").asLong(0);
                long completionTokens = usage.path("completion_tokens").asLong(0);
                lastCallCostUsd = promptTokens * inputPricePerMTok / 1_000_000.0
                        + completionTokens * outputPricePerMTok / 1_000_000.0;
            }
            return content;
        } catch (PermanentApiException | TransientApiException e) {
            throw e;
        } catch (Exception e) {
            throw new TransientApiException(name, "llm call failed: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        return s == null ? ""
                : s.substring(0, Math.min(HttpDefaults.ERROR_BODY_MAX_CHARS, s.length()));
    }
}
