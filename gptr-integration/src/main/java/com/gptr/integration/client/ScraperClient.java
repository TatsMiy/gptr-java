package com.gptr.integration.client;

import java.util.List;

/**
 * 抓取客户端接口：批量抓取 URL 并返回清洗后正文。
 *
 * <p>实现按错误类型抛 {@code TransientApiException / QuotaApiException /
 * PermanentApiException}；调用方经 Resilience4j 重试 + 熔断保护。
 */
public interface ScraperClient {

    String name();

    /** 批量抓取，返回清洗后内容（单条失败单独标记，不整体失败）。 */
    List<ScrapedContent> scrape(List<String> urls);

    /**
     * 带正文上限的抓取（sourceDistill 模式用：传更大上限让后端返回长正文供 LLM
     * 提炼覆盖全文；默认实现忽略上限）。实现侧：maxCharsPerUrl &lt;= 0 = 后端默认。
     */
    default List<ScrapedContent> scrape(List<String> urls, int maxCharsPerUrl) {
        return scrape(urls);
    }
}
