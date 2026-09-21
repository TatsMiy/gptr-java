package com.gptr.integration.client;

/**
 * 单条搜索结果。
 *
 * @param title   标题
 * @param url     链接
 * @param snippet 摘要
 * @param content 全文内容（全文型检索器返回，如 pubmed_central/custom；无则空串）
 */
public record SearchResult(String title, String url, String snippet, String content) {

    /** 兼容旧构造：无全文内容。 */
    public SearchResult(String title, String url, String snippet) {
        this(title, url, snippet, "");
    }

    public boolean hasContent() {
        return content != null && !content.isBlank();
    }
}
