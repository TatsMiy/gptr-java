package com.gptr.engine.budget;

/** 写作阶段预算——上下文规模、证据目录、quote 渲染与预览截断。
 *
 *  <p>本载体是「截断/预算常量按域集中化」的**写作域**：
 *  {@code contextMaxChars} 收敛了"默认值的出处"——原 {@code ContextManager.DEFAULT_MAX_CHARS}
 *  与 {@code EngineConfig} 的 {@code contextMaxChars} 任务键默认值同为 12000，
 *  靠 javadoc 与兜底表达式维持同步。⚠️ 该键**仍是任务 config 键**（可被任务覆盖），
 *  这里收敛的只是默认值来源，**不是**把 config 键搬进预算载体。
 *
 *  @param contextMaxChars       原 {@code ContextManager.DEFAULT_MAX_CHARS} = 12000
 *                               —— 默认上下文最大字符数（原版 25k words 的保守中文版预算）
 *  @param sectionQuoteChars     原 {@code SectionWriter.SECTION_QUOTE_CHARS} = 600
 *                               —— 逐节写作时节内 quote 的渲染截断（长于 learnings 渲染，
 *                               保留数据细节）
 *  @param indexSummaryChars     原 {@code SectionWriter.INDEX_SUMMARY_CHARS} = 120
 *                               —— 目录每行摘要截断长度（60–120 字，取 120）
 *  @param evidenceIndexMaxChars 原 {@code SectionWriter.DEFAULT_EVIDENCE_INDEX_CHARS} = 40000
 *                               —— 证据目录默认**上限**（{@code EngineConfig.evidenceIndexMaxChars}
 *                               可配）。2026-09-13 由 12000 提到 40000：实测 195 条证据 ≈21k 字符，
 *                               12000 时截断 31%，被截证据 LLM 看不见 → 无法直引 → 全进兜底组。
 *                               语义是"内容实际长度封顶"而非"预算"（证据少时不补齐）
 *  @param quoteRenderMaxChars   原 {@code DeepResearchPrompts.QUOTE_RENDER_MAX_CHARS} = 120
 *                               —— quote 渲染截断（**存储**上限是 2000，见 {@code quoteStoreMax}）
 *  @param sectionPreviewMaxChars 原 {@code ResearchEngineImpl.SECTION_PREVIEW_MAX_CHARS} = 600
 *                               —— 每节正文压平后进 Key Takeaways prompt 的预览长度
 */
public record WritingBudget(int contextMaxChars,
                            int sectionQuoteChars,
                            int indexSummaryChars,
                            int evidenceIndexMaxChars,
                            int quoteRenderMaxChars,
                            int sectionPreviewMaxChars) {
}
