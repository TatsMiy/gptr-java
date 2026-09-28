package com.gptr.integration.client;

import java.util.List;

/**
 * 一次批量抓取的两份产出：可用正文，以及**每个请求 URL 的交代**。
 *
 * <p>两份都返回，是因为它们回答不同的问题：{@code contents} 是"能用的素材"，
 * {@code outcomes} 是"每个 URL 到底怎么了"。只保留前者就会丢掉失败原因 —— 而
 * "抓不到的 URL 去了哪"正是要靠后者才能回答。
 */
public record ScrapeBatch(List<ScrapedContent> contents, List<ScrapeOutcome> outcomes) {

    public static ScrapeBatch contentsOnly(List<ScrapedContent> contents) {
        return new ScrapeBatch(contents, List.of());
    }
}
