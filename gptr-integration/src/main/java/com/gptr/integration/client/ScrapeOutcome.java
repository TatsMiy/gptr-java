package com.gptr.integration.client;

/**
 * 单个 URL 的抓取交代（后端 {@code /scrape} 的 {@code outcomes[]}）。
 *
 * <p>只承载**调用方要用的判定依据**，不照抄后端的全部字段：后端每条的字段有十余个
 * （字节数、耗时、语言占比、HTTP 状态等），照抄会让本 record 的分量数越过代码纪律的
 * 上限；其余字段由调用方按需自行读取，本类不解析。
 *
 * <p>语义：{@code reason} 回答"为什么没拿到可用正文"；{@code degraded} 回答"链路降级了
 * 但仍然成功" —— 两者是**不同的字段**，不得互相折叠。
 */
public record ScrapeOutcome(String url, String reason, String pageKind,
                            boolean truncated, boolean degraded) {

    /** 该 URL 是否拿到了可用正文。 */
    public boolean usable() {
        return "ok".equals(reason);
    }
}
