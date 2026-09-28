package com.gptr.integration.client;

import java.util.List;

/**
 * 抓取客户端接口：批量抓取 URL 并返回清洗后正文。
 *
 * <p>实现按错误类型抛 {@code TransientApiException / QuotaApiException /
 * PermanentApiException}；调用方经 Resilience4j 重试 + 熔断保护。
 *
 * <p><b>唯一的抽象抓取方法是 {@link #scrapeDetailed}</b>：它同时给出可用正文与每个 URL 的
 * 交代。其余 {@code scrape(...)} 重载都是**转发到它**的默认方法，只取正文。
 * 这样安排的理由是"漏掉交代"必须**在编译期就不可能**：若把带交代的方法做成默认方法，
 * 包装层忘记覆写时它会静默降级成"没有交代"，而调用方看不出任何差别。
 */
public interface ScraperClient {

    String name();

    /**
     * 批量抓取，同时返回**每个请求 URL 的交代**（成功与失败都在）。实现者必须提供。
     *
     * @param urls           待抓 URL（实现侧可去重）
     * @param maxCharsPerUrl 每 URL 正文上限；&lt;= 0 = 后端默认
     * @param requestId      调用方关联 id；空串 = 不带
     */
    ScrapeBatch scrapeDetailed(List<String> urls, int maxCharsPerUrl, String requestId);

    /** 批量抓取，只取清洗后内容（单条失败单独标记，不整体失败）。 */
    default List<ScrapedContent> scrape(List<String> urls) {
        return scrapeDetailed(urls, 0, "").contents();
    }

    /**
     * 带正文上限的抓取（sourceDistill 模式用：传更大上限让后端返回长正文供 LLM
     * 提炼覆盖全文）。实现侧：maxCharsPerUrl &lt;= 0 = 后端默认。
     */
    default List<ScrapedContent> scrape(List<String> urls, int maxCharsPerUrl) {
        return scrapeDetailed(urls, maxCharsPerUrl, "").contents();
    }

    /**
     * 带正文上限与调用方关联 id 的抓取。关联 id 供后端把日志与任务对上。
     */
    default List<ScrapedContent> scrape(List<String> urls, int maxCharsPerUrl, String requestId) {
        return scrapeDetailed(urls, maxCharsPerUrl, requestId).contents();
    }
}
