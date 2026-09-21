package com.gptr.engine;

/**
 * HTTP 客户端的公共默认值。
 *
 * <p>本包有 4 个外呼客户端（检索 ×2 / 抓取 / LLM），其中**连接超时**是同一个决策——
 * "TCP 建连允许多久"与下游服务特性无关，故收敛到此处，避免四处各写一份。
 *
 * <p><b>请求超时不在此处</b>：检索 30s / 抓取 60s / LLM 120s 各自取决于下游服务特性，
 * 是不同的决策，故由各客户端自持命名常量（值相同 ≠ 决策相同）。
 */
public final class HttpDefaults {

    /** TCP 建连超时（秒）。 */
    public static final int CONNECT_TIMEOUT_SECONDS = 10;

    /** 非 2xx 响应体写进异常/日志时的截断长度（防止整个 HTML 错误页进日志）。 */
    public static final int ERROR_BODY_MAX_CHARS = 300;

    private HttpDefaults() {
    }
}
