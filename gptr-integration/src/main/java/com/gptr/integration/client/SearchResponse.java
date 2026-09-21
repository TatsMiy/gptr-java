package com.gptr.integration.client;

import java.util.List;

/**
 * 检索响应——结果 + **实际命中源**（评审 #2 修订的诚实边界）。
 *
 * <p>取代裸 {@code List<SearchResult>} 返回：隐式上下文（ThreadLocal）在
 * "检索跑子虚拟线程、读取在父线程"下不可靠且不可审计；命中源必须随结果
 * 显式返回。单源客户端 sourceUsed=自身 {@code name()}；worker 降级链组合
 * 客户端 = 链中首个成功步骤的源名；{@code BlockedSearchClient} 等透明包装
 * 透传底层源。
 *
 * @param results    检索结果（可空列表，不返回 null）
 * @param sourceUsed 实际产出这批结果的源名（空串 = 失败兜底空结果）
 */
public record SearchResponse(List<SearchResult> results, String sourceUsed) {

    /** 失败兜底：空结果 + 空源（调用方按空结果跳过）。 */
    public static SearchResponse empty() {
        return new SearchResponse(List.of(), "");
    }
}
