package com.gptr.integration.client;

/**
 * 检索客户端接口（搜索引擎适配器，策略模式）。
 *
 * <p>实现按错误类型抛 {@code TransientApiException / QuotaApiException /
 * PermanentApiException}；调用方经 Resilience4j 重试 + 熔断 + 降级链保护。
 *
 * <p>返回 {@link SearchResponse}——命中源随结果显式返回（不依赖
 * 隐式上下文），失败兜底用 {@link SearchResponse#empty()}。
 */
public interface SearchClient {

    /** 返回搜索结果的名称（用于日志/降级链标识，如 tavily、serper）。 */
    String name();

    /** 执行搜索（带命中源）。结果可为空列表，不返回 null。 */
    SearchResponse search(String query);

    /**
     * 带选项执行搜索。默认实现转发 {@link #search(String)}；
     * 支持引擎/结果数参数的实现（如 python-crawler）覆写此方法。
     */
    default SearchResponse search(String query, SearchOptions opts) {
        return search(query);
    }

    /**
     * 带选项与调用方关联 id 执行搜索。关联 id 供后端把日志与任务对上；
     * 默认实现忽略它并转发 {@link #search(String, SearchOptions)}。
     */
    default SearchResponse search(String query, SearchOptions opts, String requestId) {
        return search(query, opts);
    }
}
