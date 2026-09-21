package com.gptr.engine.budget;

/** 全部预算的聚合——**唯一装配点**。
 *
 *  <p>为什么不是一个包含 28 个字段的大类：{@code record} 分量上限是 8（门禁硬判据），
 *  且共用判据是"值同**且域同**"——"检索摘要 300"与"报告目录 40000"域不同。
 *  按消费域分成 5 个子载体后，读某节点的人只看它那一组。
 *
 *  <p>传递方式：由 {@code ResearchEngineImpl} 构造后随 {@code buildReal} 独立传入
 *  （**不**并入任一新节点选项组——它是横跨全部节点的横切关注点，塞进任一组都会制造伪耦合）。
 *
 *  @param retrieval  检索域
 *  @param extraction 提炼域
 *  @param curate     精选域
 *  @param reflect    反思域
 *  @param writing    写作域
 */
public record Budgets(RetrievalBudget retrieval, ExtractionBudget extraction,
                      CurateBudget curate, ReflectBudget reflect, WritingBudget writing) {

    /** 出厂默认 = 迁移前全部字面值（**零行为变更**，数值一字不改）。
     *
     *  <p>各行的实参顺序与对应 record 的分量顺序严格一致；每个值的出处见各载体的
     *  {@code @param}（原常量名 + 原字面值）。
     *  <p>预算**不提供**运行时改值入口——要改阈值请改代码，不要在这里兜临时值。
     */
    public static Budgets defaults() {
        return new Budgets(
                //              searchContentMaxChars, clarifySnippet, clarifyResults, calibratedQuery, clarifyMaxShown
                new RetrievalBudget(2000, 300, 4000, 3000, 8),
                //              pageRawCap, groupJoinCap, maxCharsPerSource, quoteStoreMax, queryTextStoreMax,
                //              flatDistillSummary, flatDistillMaxItems, flatDistillEvidence
                new ExtractionBudget(4000, 20000, 3000, 2000, 400, 400, 8, 400),
                //              entryMaxChars, totalMaxChars, maxPerUrl
                new CurateBudget(1200, 6000, 3),
                //              followUpLearnings, followUpGaps, planReflectLearnings, planReflectGaps, researchState
                new ReflectBudget(8000, 2000, 6000, 1500, 2000),
                //              contextMaxChars, sectionQuote, indexSummary, evidenceIndex, quoteRender, sectionPreview
                new WritingBudget(12000, 600, 120, 40000, 120, 600));
    }
}
