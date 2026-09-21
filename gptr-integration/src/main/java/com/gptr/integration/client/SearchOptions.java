package com.gptr.integration.client;

/**
 * 单次搜索的可选参数（透传引擎选源与结果数上限）。
 *
 * <p>{@code retriever} 仅对 python-crawler 客户端有意义（gpt-researcher 检索器名，
 * 如 duckduckgo/arxiv/bocha…）；{@code maxResults} 为每查询结果数上限
 * （null = 客户端默认）。
 */
public record SearchOptions(String retriever, Integer maxResults) {

    /** 默认：走客户端自身默认（retriever=duckduckgo，maxResults=客户端默认）。 */
    public static final SearchOptions DEFAULT = new SearchOptions(null, null);

    public static SearchOptions of(String retriever, Integer maxResults) {
        return new SearchOptions(retriever, maxResults);
    }

    /** 合并：本对象为 null 的字段用 defaults 补位（defaults 为 null 则保持 null）。 */
    public SearchOptions withDefaults(SearchOptions defaults) {
        String r = retriever != null ? retriever : (defaults == null ? null : defaults.retriever());
        Integer m = maxResults != null ? maxResults : (defaults == null ? null : defaults.maxResults());
        return new SearchOptions(r, m);
    }
}
