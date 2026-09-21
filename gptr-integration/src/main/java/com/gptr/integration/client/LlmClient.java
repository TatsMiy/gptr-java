package com.gptr.integration.client;

/**
 * LLM 客户端接口（chat 补全）。
 *
 * <p>实现按错误类型抛 {@code TransientApiException / QuotaApiException /
 * PermanentApiException}；调用方经 Resilience4j 重试 + 熔断保护。
 */
public interface LlmClient {

    String name();

    /** 一次 chat 补全，返回回复文本。 */
    String chat(String systemPrompt, String userPrompt);

    /** 结构化输出变体：实现可附加 response_format（json_object）约束；默认同 chat。 */
    default String chatJson(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt);
    }

    /** 最近一次调用的估算成本（美元）；mock 实现默认 0，真实实现按 usage 计算。 */
    default double lastCallCostUsd() {
        return 0.0;
    }
}
