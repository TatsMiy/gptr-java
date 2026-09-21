package com.gptr.engine.budget;

/** 层间反思与追问预算——learnings / gaps / 研究状态进 prompt 前的字符上限。
 *
 *  <p>本载体是「截断/预算常量按域集中化」的**反思域**。
 *
 *  @param followUpLearningsMaxChars    原 {@code FollowUpNode.FOLLOWUP_LEARNINGS_MAX_CHARS} = 8000
 *                                      —— 追问 prompt 的 {@code {learnings}} 占位符预算
 *  @param followUpGapsMaxChars         原 {@code FollowUpNode.FOLLOWUP_GAPS_MAX_CHARS} = 2000
 *                                      —— 追问 prompt 的 {@code {gaps}} 占位符预算；两个填充来源
 *                                      （{@code followUpQuestions} 与 {@code planGaps}）同属该占位符，
 *                                      故共用一个上限
 *  @param planReflectLearningsMaxChars 原 {@code PlanReflectNode.PLAN_REFLECT_LEARNINGS_MAX_CHARS} = 6000
 *                                      —— 反思 prompt 的 {@code {learnings}} 占位符预算
 *  @param planReflectGapsMaxChars      原 {@code PlanReflectNode.PLAN_REFLECT_GAPS_MAX_CHARS} = 1500
 *                                      —— 反思 prompt 的 {@code {gaps}} 占位符预算
 *                                      （缺口是短句清单，故小于 learnings）
 *  @param researchStateMaxChars        原 {@code PlanReflectNode.RESEARCH_STATE_MAX_CHARS} = 2000
 *                                      —— 中央研究状态写回 state 时的存储截断长度
 */
public record ReflectBudget(int followUpLearningsMaxChars,
                            int followUpGapsMaxChars,
                            int planReflectLearningsMaxChars,
                            int planReflectGapsMaxChars,
                            int researchStateMaxChars) {
}
