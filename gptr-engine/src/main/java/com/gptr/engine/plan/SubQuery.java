package com.gptr.engine.plan;

/** 子查询：由 PLANNING 阶段从用户问题拆解（对应原版 generate_search_queries）。 */
public record SubQuery(String query, String researchGoal) {
}
