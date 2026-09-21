package com.gptr.engine;

import com.gptr.engine.budget.ExtractionBudget;

/**
 * 任务级生效预算——{@link EngineConfig} 派生量的唯一载体。
 *
 * <p>这三个值原本是散落在 {@code ScrapeNode} 与 {@code DeepResearchGraph} 里的内联表达式：
 * 既不在任务 config JSON 中、也无法从任何配置界面看到，排查时只能跨文件推理
  * （普查实测：197 个行为决定点里 129 个不在 config）。
 * <p>收敛到此 record 后，消费节点读它、快照日志打它、断言测它，**同一个对象** ——
 * 不再存在"第二处默认值声明"（该原则的先例见 {@code ResearchOptions} 中
 * {@code production()} 静态工厂的删除留痕）。
 *
 * @param extraChars   sourceDistill 打开时的抓取正文长度；0 = 关闭（用后端默认）
 * @param pageRawCap   每页正文进入图状态时的截断上限
 * @param groupJoinCap 单查询条目组拼进提炼 prompt 的字符预算
 */
public record EffectiveBudgets(int extraChars, int pageRawCap, int groupJoinCap) {

    /** 三个派生量的**唯一计算式**——原为 {@code ScrapeNode} 与 {@code DeepResearchGraph}
     *  的内联表达式，逐字搬入此处。
     *  <p>{@link EngineConfig} 与 {@code ResearchOptions} 的兼容构造都经此计算，
     *  故全仓只有一个分母：改这里，消费节点与快照日志**同时**改变。
     *  <p>两个默认值**不在此声明**：由 {@link ExtractionBudget} 供给
     *  （原 {@code DEFAULT_PAGE_RAW_CAP}=4000 / {@code DEFAULT_GROUP_JOIN_CAP}=20000，
     *  取值理由与出处见该载体的 {@code @param}）。 */
    public static EffectiveBudgets of(ExtractionBudget extraction, boolean sourceDistill,
                                      int distillMaxChars) {
        int extraChars = sourceDistill ? distillMaxChars : 0;
        int pageRawCap = extraChars > 0 ? extraChars : extraction.pageRawCap();
        int groupJoinCap = sourceDistill
                ? Math.max(extraction.groupJoinCap(), distillMaxChars) : extraction.groupJoinCap();
        return new EffectiveBudgets(extraChars, pageRawCap, groupJoinCap);
    }
}
