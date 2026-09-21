package com.gptr.engine.budget;

/** 检索阶段预算——检索结果自带正文进条目组、以及澄清前奏 prompt 的字符/条数上限。
 *
 *  <p>本载体是「截断/预算常量按域集中化」的**检索域**。
 *  字段名 = 原常量名转小驼峰（去掉类名前缀），{@code @param} 保留原常量名以便 grep 追溯。
 *
 *  @param searchContentMaxChars  原 {@code SearchNode.SEARCH_CONTENT_MAX_CHARS} = 2000
 *  @param clarifySnippetMaxChars 原 {@code ResearchPlanNode.CLARIFY_SNIPPET_MAX_CHARS} = 300
 *  @param clarifyResultsMaxChars 原 {@code ResearchPlanNode.CLARIFY_SEARCH_RESULTS_MAX_CHARS} = 4000
 *  @param calibratedQueryMaxChars 原 {@code ResearchPlanNode.CALIBRATED_QUERY_MAX_CHARS} = 3000
 *  @param clarifyMaxShownResults 原 {@code ResearchPlanNode.CLARIFY_MAX_SHOWN_RESULTS} = 8
 */
public record RetrievalBudget(int searchContentMaxChars,
                              int clarifySnippetMaxChars,
                              int clarifyResultsMaxChars,
                              int calibratedQueryMaxChars,
                              int clarifyMaxShownResults) {
}
