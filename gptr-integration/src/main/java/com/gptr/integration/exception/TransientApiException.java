package com.gptr.integration.exception;

/**
 * 可重试的瞬时错误（LLM/搜索/抓取的 429 速率限制、5xx、超时、网络抖动）。
 *
 * <p>被 Resilience4j Retry 重试；重试耗尽后由降级链切换到下一个源，
 * 全部失败则该异常直达任务执行层（映射为 TRANSIENT_EXHAUSTED）。
 */
public class TransientApiException extends RuntimeException {

    /** 出错的外部源名称（如 tavily、llm）。 */
    private final String source;

    public TransientApiException(String source, String message) {
        super(source + ": " + message);
        this.source = source;
    }

    public TransientApiException(String source, String message, Throwable cause) {
        super(source + ": " + message, cause);
        this.source = source;
    }

    public String getSource() {
        return source;
    }
}
