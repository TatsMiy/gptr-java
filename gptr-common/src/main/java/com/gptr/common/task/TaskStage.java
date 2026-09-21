package com.gptr.common.task;

/**
 * 研究阶段：平面流水线五阶段 + deep research 的 RESEARCH 超阶段。
 *
 * <p>{@code task_events.stage}、{@code checkpoints.stage} 的取值即此枚举，
 * checkpoint 粒度与阶段一一对应。
 */
public enum TaskStage {

    /** 规划：生成子查询 / 研究大纲 */
    PLANNING,

    /** 检索：调用搜索引擎 */
    SEARCHING,

    /** 抓取：抓取并解析页面内容 */
    SCRAPING,

    /** 总结：过滤、压缩、提炼上下文 */
    SUMMARIZING,

    /** 写作：生成最终报告 */
    WRITING,

    /** deep research 超阶段：内部跑 LangGraph4j 递归图 */
    RESEARCH
}
