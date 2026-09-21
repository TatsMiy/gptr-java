package com.gptr.engine.budget;

import com.gptr.engine.EffectiveBudgets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 「默认值等价」的**机械核对**：
  *  迁移前的「值」列
  *  必须与 {@link Budgets#defaults()} 逐字段相同（零行为变更）。
 *
  *  <p>为什么要有这个测试：载体字段**全是 int**（把 2000 与 300
 *  写反编译器不会报错）。断言逐个写出字段名，等于同时锁住**顺序**与**取值**：
 *  任何后续批次（含批 2 把 {@code EffectiveBudgets#of} 改为读载体）若改动了出厂默认值，
 *  本测试立刻变红。
 *
 *  <p>断言里的字面值来源 = **迁移前的原常量字面值**（不是从载体反抄），故它是一份真正的
  *  独立基线。改这些值必须同时改本测试的期望值，不允许只改代码。
 */
class BudgetsTest {

    // ---------------- 检索域：原 SearchNode / ResearchPlanNode ----------------

    @Test
    void retrievalDefaultsMatchPreMigrationLiterals() {
        RetrievalBudget r = Budgets.defaults().retrieval();
        assertEquals(2000, r.searchContentMaxChars(), "原 SearchNode.SEARCH_CONTENT_MAX_CHARS");
        assertEquals(300, r.clarifySnippetMaxChars(), "原 ResearchPlanNode.CLARIFY_SNIPPET_MAX_CHARS");
        assertEquals(4000, r.clarifyResultsMaxChars(),
                "原 ResearchPlanNode.CLARIFY_SEARCH_RESULTS_MAX_CHARS");
        assertEquals(3000, r.calibratedQueryMaxChars(),
                "原 ResearchPlanNode.CALIBRATED_QUERY_MAX_CHARS");
        assertEquals(8, r.clarifyMaxShownResults(), "原 ResearchPlanNode.CLARIFY_MAX_SHOWN_RESULTS");
    }

    // ---------------- 提炼域：原 EffectiveBudgets / EvidenceNote / ResearchEngineImpl ----------------

    @Test
    void extractionDefaultsMatchPreMigrationLiterals() {
        ExtractionBudget e = Budgets.defaults().extraction();
        assertEquals(4000, e.pageRawCap(), "原 EffectiveBudgets.DEFAULT_PAGE_RAW_CAP");
        assertEquals(20000, e.groupJoinCap(), "原 EffectiveBudgets.DEFAULT_GROUP_JOIN_CAP");
        assertEquals(3000, e.maxCharsPerSource(), "原 MAX_CHARS_PER_SOURCE（原两处重复定义）");
        assertEquals(2000, e.quoteStoreMax(), "原 EvidenceNote.QUOTE_STORE_MAX");
        assertEquals(400, e.queryTextStoreMax(), "原 EvidenceNote.QUERY_TEXT_STORE_MAX");
        assertEquals(400, e.flatDistillSummaryMaxChars(),
                "原 ResearchEngineImpl.FLAT_DISTILL_SUMMARY_MAX_CHARS");
        assertEquals(8, e.flatDistillMaxEvidenceItems(),
                "原 ResearchEngineImpl.FLAT_DISTILL_MAX_EVIDENCE_ITEMS");
        assertEquals(400, e.flatDistillEvidenceMaxChars(),
                "原 ResearchEngineImpl.FLAT_DISTILL_EVIDENCE_MAX_CHARS");
    }

    // ---------------- 精选域：原 CurateNode / ResearchEngineImpl / ContextManager ----------------

    @Test
    void curateDefaultsMatchPreMigrationLiterals() {
        CurateBudget c = Budgets.defaults().curate();
        assertEquals(1200, c.entryMaxChars(), "原 CurateNode.CURATE_ENTRY_MAX_CHARS");
        assertEquals(6000, c.totalMaxChars(),
                "原 CURATE_TOTAL_MAX_CHARS / FLAT_CURATE_TOTAL_MAX_CHARS（两组声明合一）");
        assertEquals(3, c.maxPerUrl(), "原 ContextManager.MAX_PER_URL");
    }

    // ---------------- 反思域：原 FollowUpNode / PlanReflectNode ----------------

    @Test
    void reflectDefaultsMatchPreMigrationLiterals() {
        ReflectBudget f = Budgets.defaults().reflect();
        assertEquals(8000, f.followUpLearningsMaxChars(), "原 FollowUpNode.FOLLOWUP_LEARNINGS_MAX_CHARS");
        assertEquals(2000, f.followUpGapsMaxChars(), "原 FollowUpNode.FOLLOWUP_GAPS_MAX_CHARS");
        assertEquals(6000, f.planReflectLearningsMaxChars(),
                "原 PlanReflectNode.PLAN_REFLECT_LEARNINGS_MAX_CHARS");
        assertEquals(1500, f.planReflectGapsMaxChars(), "原 PlanReflectNode.PLAN_REFLECT_GAPS_MAX_CHARS");
        assertEquals(2000, f.researchStateMaxChars(), "原 PlanReflectNode.RESEARCH_STATE_MAX_CHARS");
    }

    // ---------------- 写作域：原 ContextManager / SectionWriter / DeepResearchPrompts ----------------

    @Test
    void writingDefaultsMatchPreMigrationLiterals() {
        WritingBudget w = Budgets.defaults().writing();
        assertEquals(12000, w.contextMaxChars(), "原 ContextManager.DEFAULT_MAX_CHARS");
        assertEquals(600, w.sectionQuoteChars(), "原 SectionWriter.SECTION_QUOTE_CHARS");
        assertEquals(120, w.indexSummaryChars(), "原 SectionWriter.INDEX_SUMMARY_CHARS");
        assertEquals(40000, w.evidenceIndexMaxChars(), "原 SectionWriter.DEFAULT_EVIDENCE_INDEX_CHARS");
        assertEquals(120, w.quoteRenderMaxChars(), "原 DeepResearchPrompts.QUOTE_RENDER_MAX_CHARS");
        assertEquals(600, w.sectionPreviewMaxChars(),
                "原 ResearchEngineImpl.SECTION_PREVIEW_MAX_CHARS");
    }

        // ---------------- 派生关系不变 ----------------
    // 批 2 会把 EffectiveBudgets#of 的两个默认值改读 ExtractionBudget；
    // 本测试是那次改动的护栏：算式与分支必须逐字等价。

    @Test
    void effectiveBudgetsDerivationIsUnchanged() {
        ExtractionBudget e = Budgets.defaults().extraction();
        EffectiveBudgets on = EffectiveBudgets.of(e, true, 30000);
        assertEquals(30000, on.extraChars(), "sourceDistill=true 时 extraChars = distillMaxChars");
        assertEquals(30000, on.pageRawCap(), "pageRawCap = extraChars > 0 ? extraChars : 4000");
        assertEquals(30000, on.groupJoinCap(), "groupJoinCap = max(20000, distillMaxChars)");

        EffectiveBudgets off = EffectiveBudgets.of(e, false, 0);
        assertEquals(0, off.extraChars(), "sourceDistill=false 时 extraChars = 0");
        assertEquals(4000, off.pageRawCap(), "关闭时回落 DEFAULT_PAGE_RAW_CAP");
        assertEquals(20000, off.groupJoinCap(), "关闭时回落 DEFAULT_GROUP_JOIN_CAP");
    }
}
