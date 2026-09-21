package com.gptr.engine.epoc;


import com.gptr.engine.budget.ExtractionBudget;
import com.gptr.integration.client.LlmClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.DoubleConsumer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** ExtractNode —— 由 DeepResearchGraph 外迁的节点（方法体逐字未改）。 */
final class ExtractNode {

    private static final Logger LOG = LoggerFactory.getLogger(ExtractNode.class);

    private ExtractNode() {
    }

    /** I-7：per-query 独立提炼——每子查询用自己的结果条目组单独调 LLM（对标 py
     *  process_query → process_research_results）。单分支失败/空上下文 → 记 0 跳过
     *  （不杀整轮）；全层 learnings 为 0 → 守卫 END。上下文 = 该 query 的条目组
     *  （joinCap 为组上下文预算；sourceDistill 开时放大以覆盖长正文）。
     *  产出结构化 EvidenceNote 写入 evidenceBank（String 承载，checkpoint 兼容），
     *  learnings 保持为 bank 的渲染视图——同一合并处单点双写，下游零改动。 */
    static AsyncNodeAction<DeepResearchState> realExtractPerQuery(
            LlmClient llm, DoubleConsumer costCallback, ExtractionBudget extraction,
            boolean extractOnDistilled) {
        return state -> CompletableFuture.supplyAsync(
                () -> runPerQueryExtract(state, llm, costCallback, extraction, extractOnDistilled));
    }

    /** {@link #realExtractPerQuery} 的实现体（原 lambda 体逐字搬入，缩进 −2 层）。 */
    private static Map<String, Object> runPerQueryExtract(DeepResearchState state, LlmClient llm,
                                                         DoubleConsumer costCallback,
                                                         ExtractionBudget extraction,
                                                         boolean extractOnDistilled) {
        List<String> queries = state.queries();
        List<List<String>> items = state.queryItems();
        if (queries.isEmpty()) {
            Map<String, Object> updates = new HashMap<>();
            updates.put(DeepResearchState.K_ROUND_LEARNINGS, 0);
            updates.put(DeepResearchState.K_CURRENT_DEPTH, state.currentDepth() + 1);
            return updates;
        }
        String system = DeepResearchPrompts.get("process-results.system");
        List<String> authorized = state.collectedUrls();
        // 定长槽位（null=该分支尚未完成/无证据），并行完成无序写入 → set(idx) 安全
        List<List<EvidenceNote.Raw>> branchRaws =
                new ArrayList<>(Collections.nCopies(queries.size(), null));
        List<List<String>> branchFollowUps =
                new ArrayList<>(Collections.nCopies(queries.size(), null));
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            BranchInput branchInput = new BranchInput(system, extraction.groupJoinCap(),
                    extractOnDistilled,
                    state.currentDepth() + 1, llm, costCallback);
            for (int i = 0; i < queries.size(); i++) {
                final int idx = i;
                futures.add(CompletableFuture.runAsync(
                        () -> fillBranch(idx, queries, items, branchInput, branchRaws,
                                branchFollowUps, extraction),
                        pool));
            }
            for (CompletableFuture<Void> f : futures) {
                f.join();
            }
        }
        // 合并：唯一写路径——bank 与 learnings 双写（learnings = bank 渲染视图）
        List<String> learnings = new ArrayList<>(state.learnings());
        List<String> followUps = new ArrayList<>(state.followUpQuestions());
        List<String> bank = new ArrayList<>(state.evidenceBank());
        int depth = state.currentDepth() + 1;
        int round = 0;
        // M-2026（实测驱动）：(sourceUrl, insight) 去重——Y 臂直通时同 URL 蒸馏块经
        // appendToGroupsReferencing 进多个查询组，每组直通同一批句 → 跨组重复（q04-Y 单
        // URL max 248 条实证）。页级蒸馏无查询视角，跨组复制零信息增益。保留首组归属。
        Set<String> seenNotes = new HashSet<>();
        int dedupDropped = 0;
        for (int i = 0; i < branchRaws.size(); i++) {
            List<EvidenceNote.Raw> raws = branchRaws.get(i);
            if (raws == null) {
                continue;
            }
            for (EvidenceNote.Raw r : raws) {
                String dedupKey = (r.sourceUrl() == null ? "" : r.sourceUrl())
                        + "\u0000" + r.insight();
                if (r.direct() && !seenNotes.add(dedupKey)) {
                    dedupDropped++;
                    continue; // 直通句跨组/组内重复：只留首组（LLM 提炼产物不去重）
                }
                // LLM 自报 sourceUrl 必须 ∈ 真实检索 URL 集，否则剥夺引用锚
                //（孤儿 quote 无 URL 锚不可审计 → 一并清空，与渲染/剥除红线一致）
                boolean auth = EvidenceText.isAuthorized(r.sourceUrl(), authorized);
                String url = auth ? r.sourceUrl() : "";
                String quote = auth ? r.quote() : "";
                EvidenceNote note = new EvidenceNote(bank.size(), depth, depth, i,
                        EvidenceText.truncateQueryText(queries.get(i), extraction.queryTextStoreMax()),
                        r.insight(), quote, url);
                bank.add(note.toJson());
                learnings.add(note.renderLearningText());
                round++; // E3 守卫输入（实际新增数——去重后同旧语义口径）
            }
        }
        List<String> doneFollowUps = new ArrayList<>();
        for (List<String> branch : branchFollowUps) {
            if (branch != null) {
                doneFollowUps.addAll(branch);
            }
        }
        followUps.addAll(doneFollowUps);
        Map<String, Object> updates = new HashMap<>();
        updates.put(DeepResearchState.K_EVIDENCE_BANK, bank);
        updates.put(DeepResearchState.K_LEARNINGS, learnings);
        updates.put(DeepResearchState.K_FOLLOW_UP_QUESTIONS, followUps);
        updates.put(DeepResearchState.K_ROUND_LEARNINGS, round); // E3 守卫输入
        updates.put(DeepResearchState.K_CURRENT_DEPTH, state.currentDepth() + 1);
        // 批 2 诊断：分支产出/去重丢弃（Y 臂直通句跨组重复会被丢——覆盖补查是否真增益看这里）
        StringBuilder perBranch = new StringBuilder();
        for (int i = 0; i < branchRaws.size(); i++) {
            List<EvidenceNote.Raw> raws = branchRaws.get(i);
            perBranch.append(i).append(':').append(raws == null ? -1 : raws.size()).append(' ');
        }
        LOG.info("[batch2] extract d{}: queries={} raws[{}] kept={} dedupDropped={}",
                depth, queries.size(), perBranch.toString().trim(), round, dedupDropped);
        return updates;
    }

    /** 并行分支的共享输入（原内层 lambda 闭包捕获的不变部分，5 项打包）。 */
    private record BranchInput(String system, int joinCap, boolean extractOnDistilled,
                               int depth, LlmClient llm, DoubleConsumer costCallback) {
    }

    /** 提炼第 idx 个子查询的条目组并写入定长槽位（原内层 lambda 体逐字搬入）。
     *  各分支只写自己的 idx，故并行完成顺序无关。 */
    private static void fillBranch(int idx, List<String> queries, List<List<String>> items,
                                   BranchInput input, List<List<EvidenceNote.Raw>> branchRaws,
                                   List<List<String>> branchFollowUps, ExtractionBudget extraction) {
        List<String> group = idx < items.size() ? items.get(idx) : List.of();
        // M-2026：Y 臂（extractOnDistilled=false）→ [DISTILLED] 句块由 Java 直通
        // note（insight=原句，URL 程序化注入，无二次 LLM 加工）；RAW 条目照旧提炼。
        // X 臂（true）→ 蒸馏句块同 RAW 一起进 LLM 提炼（双臂实验）。
        List<EvidenceNote.Raw> direct = new ArrayList<>();
        List<String> llmGroup = new ArrayList<>();
        if (group == null) {
            group = List.of();
        }
        if (input.extractOnDistilled()) {
            llmGroup.addAll(group);
        } else {
            for (String item : group) {
                if (item != null && item.startsWith(EvidenceText.DISTILLED_MARKER)) {
                    direct.addAll(EvidenceText.parseDistilledBlock(item));
                } else if (item != null && !item.isBlank()) {
                    llmGroup.add(item);
                }
            }
        }
        List<EvidenceNote.Raw> llmRaws = new ArrayList<>();
        List<String> llmFollowUps = new ArrayList<>();
        // C3-S1：RAW 组为空 → 不调 LLM（防空上下文编造）
        boolean llmNeeded = llmGroup.stream().anyMatch(s -> s != null && !s.isBlank());
        if (llmNeeded) {
            // 组内拼接后实际进 prompt 的字符数（B6.2 排障口径：W→A 段的观测点）。
            // 置于 try 外：观测不参与业务，也不该被"单分支失败即跳过"的 catch 吞掉。
            PromptText.Joined joinedResult = PromptText.joinItemsLimit(llmGroup, input.joinCap());
            String joined = joinedResult.text();
            LOG.info("[batch2] extract d{} group={} items={} joinedChars={} truncated={} cap={}{}",
                input.depth(), idx, llmGroup.size(), joined.length(),
                joined.endsWith("...[truncated]"), input.joinCap(), droppedText(joinedResult));
            try {
                String user = DeepResearchPrompts.get("process-results.user")
                        .replace("{query}", queries.get(idx))
                        .replace("{context}", joined);
                String raw = input.llm().chatJson(input.system(), user);
                var parsed = DeepResearchPrompts.parseNotes(raw, extraction);
                input.costCallback().accept(input.llm().lastCallCostUsd());
                llmRaws = parsed.raws();
                llmFollowUps = parsed.followUpQuestions();
            } catch (Exception e) {
                // 单分支失败 → 跳过（对标 py process_query 异常返回 None）
                llmRaws = List.of();
            }
        }
        List<EvidenceNote.Raw> combined = new ArrayList<>(direct);
        combined.addAll(llmRaws);
        branchRaws.set(idx, combined);
        branchFollowUps.set(idx, llmFollowUps);
    }

    /** 被丢条目摘要（仅在被丢时非空）：{@code " dropped=[下标:类型:字符数 …]"}。
     *  类型判据见 {@link PromptText.Joined}；**不打印条目内容**（避免日志爆量）。 */
    private static String droppedText(PromptText.Joined joined) {
        if (joined.dropped().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" dropped=[");
        for (int i = 0; i < joined.dropped().size(); i++) {
            PromptText.Dropped d = joined.dropped().get(i);
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(d.index()).append(':').append(d.kind()).append(':').append(d.chars());
        }
        return sb.append(']').toString();
    }

    /** 旧路径（perQueryExtract=false）：整层 searchResults 一次性提炼 learnings + 追问。 */
    static AsyncNodeAction<DeepResearchState> realExtractRound(
            LlmClient llm, DoubleConsumer costCallback, ExtractionBudget extraction) {
        return state -> CompletableFuture.supplyAsync(
                () -> runExtractRound(state, llm, costCallback, extraction));
    }

    /** {@link #realExtractRound} 的实现体（原 lambda 体逐字搬入，缩进 −2 层）。 */
    private static Map<String, Object> runExtractRound(DeepResearchState state, LlmClient llm,
                                                       DoubleConsumer costCallback,
                                                       ExtractionBudget extraction) {
        String context = state.searchResults();
        // C3-S1：检索证据为空（搜索全失败/空结果）时不调 LLM——避免模型在空上下文上
        // "提炼"编造 learnings（roundLearnings>0 会绕过守卫）；直接记 0 走守卫 END
        if (context.isBlank()) {
            Map<String, Object> updates = new HashMap<>();
            updates.put(DeepResearchState.K_ROUND_LEARNINGS, 0);
            return updates;
        }
        String system = DeepResearchPrompts.get("process-results.system");
        String user = DeepResearchPrompts.get("process-results.user")
                .replace("{query}", state.query())
                .replace("{context}", context);
        String raw = llm.chatJson(system, user);
        var parsed = DeepResearchPrompts.parseLearnings(raw, extraction);
        costCallback.accept(llm.lastCallCostUsd());

        List<String> learnings = new ArrayList<>(state.learnings());
        // LLM 自报 sourceUrl 必须 ∈ 真实检索 URL 集（collectedUrls），否则剥除
        // 引用标记（insight 保留但失去引用锚点）——杜绝"自报即授权"的自证闭环
        List<String> authorized = state.collectedUrls();
        for (String insight : parsed.learnings()) {
            learnings.add(EvidenceText.stripUnauthorizedSource(insight, authorized));
        }
        List<String> followUps = new ArrayList<>(state.followUpQuestions());
        followUps.addAll(parsed.followUpQuestions());

        Map<String, Object> updates = new HashMap<>();
        updates.put(DeepResearchState.K_LEARNINGS, learnings);
        updates.put(DeepResearchState.K_FOLLOW_UP_QUESTIONS, followUps);
        updates.put(DeepResearchState.K_ROUND_LEARNINGS, parsed.learnings().size()); // E3 守卫输入
        updates.put(DeepResearchState.K_CURRENT_DEPTH, state.currentDepth() + 1);
        return updates;
    }
}
