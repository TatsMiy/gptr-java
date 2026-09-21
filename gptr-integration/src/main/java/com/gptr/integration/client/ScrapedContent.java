package com.gptr.integration.client;

/** 单条抓取结果（清洗后正文）。 */
public record ScrapedContent(String url, String title, String content) {
}
