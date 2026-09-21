package com.gptr.engine.budget;

import com.gptr.engine.config.ConfigKey;
import com.gptr.engine.config.ConfigKey.Kind;
import com.gptr.engine.config.ConfigKey.State;

/** 提炼阶段预算——正文进状态的截断、单组拼接预算、单源与引用/queryText 的存储上限。
 *
 *  <p>本载体是「截断/预算常量按域集中化」的**提炼域**。
 *  其中 {@code maxCharsPerSource} 消掉了原先 {@code ResearchEngineImpl} 与
 *  {@code SourceFetcher} **各声明一份**的重复（同值 3000）。
 *
 *  <p>⚠️ **登记状态**：本类**只有 {@code groupJoinCap} 已带 {@code @ConfigKey}**
 *  （它是 joinCap 10000→20000 变更的**默认值来源**，属 EXPERIMENT / OPEN）；其余 7 个分量的标记
 *  属其余 7 个分量的登记工作，**尚未标** —— 这正是"集中化后登记对象
 *  降到少量载体类"的预期落点。
 *
 *  @param pageRawCap                 原 {@code EffectiveBudgets.DEFAULT_PAGE_RAW_CAP} = 4000
 *                                    （{@code sourceDistill} 关闭时生效）
 *  @param groupJoinCap               原 {@code EffectiveBudgets.DEFAULT_GROUP_JOIN_CAP} = 20000
 *                                    （{@code sourceDistill} 关闭时生效；取 20000 而非 10000：
 *                                    实测每组通常为 5 条摘要 + 2–4 个 4000 字正文块，10000 只装得下
 *                                    约 2 块、第 3 块起被整条丢弃）
 *  @param maxCharsPerSource          原 {@code MAX_CHARS_PER_SOURCE} = 3000（原两处重复定义：
 *                                    engine 的 {@code ResearchEngineImpl} 与 benchmark 的
 *                                    {@code SourceFetcher}）—— 每条来源正文进入上下文的字符上限
 *  @param quoteStoreMax              原 {@code EvidenceNote.QUOTE_STORE_MAX} = 2000
 *                                    —— quote 的**存储**上限（渲染时才切 ≤120）
 *  @param queryTextStoreMax          原 {@code EvidenceNote.QUERY_TEXT_STORE_MAX} = 400
 *                                    —— queryText 存储截断（写作期归节匹配用）
 *  @param flatDistillSummaryMaxChars 原 {@code ResearchEngineImpl.FLAT_DISTILL_SUMMARY_MAX_CHARS} = 400
 *                                    —— flat 蒸馏产物 summary 的截断长度
 *  @param flatDistillMaxEvidenceItems 原 {@code ResearchEngineImpl.FLAT_DISTILL_MAX_EVIDENCE_ITEMS} = 8
 *                                    —— flat 蒸馏产物保留的 evidence 条数上限
 *  @param flatDistillEvidenceMaxChars 原 {@code ResearchEngineImpl.FLAT_DISTILL_EVIDENCE_MAX_CHARS} = 400
 *                                    —— flat 蒸馏产物每条 evidence 的截断长度
 */
public record ExtractionBudget(int pageRawCap,
                               @ConfigKey(kind = Kind.EXPERIMENT, owner = "gptr-dev", added = "2026-09-17",
                                       expires = "2026-09-20", state = State.OPEN,
                                       evidence = "2026-09-16 实测：joinItemsLimit=10000 时第 3 个正文块必然出局（结构性上限）")
                               int groupJoinCap,
                               int maxCharsPerSource,
                               int quoteStoreMax,
                               int queryTextStoreMax,
                               int flatDistillSummaryMaxChars,
                               int flatDistillMaxEvidenceItems,
                               int flatDistillEvidenceMaxChars) {
}
