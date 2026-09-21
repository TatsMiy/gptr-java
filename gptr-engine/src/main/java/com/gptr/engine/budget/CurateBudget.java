package com.gptr.engine.budget;

/** 来源精选阶段预算（{@code curate_sources}）+ 证据库每 URL 名额。
 *
 *  <p>本载体是「截断/预算常量按域集中化」的**精选域**：
 *  <b>4 个原常量 → 3 个字段</b>——{@code CurateNode.CURATE_TOTAL_MAX_CHARS} 与
 *  {@code ResearchEngineImpl.FLAT_CURATE_TOTAL_MAX_CHARS} 是同语义的两份声明（同值 6000），
 *  合并为 {@code totalMaxChars}。
 *
 *  @param entryMaxChars 原 {@code CurateNode.CURATE_ENTRY_MAX_CHARS} = 1200
 *  @param totalMaxChars 原 {@code CURATE_TOTAL_MAX_CHARS} / {@code FLAT_CURATE_TOTAL_MAX_CHARS} = 6000
 *  @param maxPerUrl     原 {@code ContextManager.MAX_PER_URL} = 3（改为构造注入）
 */
public record CurateBudget(int entryMaxChars,
                           int totalMaxChars,
                           int maxPerUrl) {
}
