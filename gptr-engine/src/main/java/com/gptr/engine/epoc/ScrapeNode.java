package com.gptr.engine.epoc;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.engine.EffectiveBudgets;
import com.gptr.engine.context.ContextManager;
import com.gptr.integration.client.LlmClient;
import com.gptr.integration.client.ScrapedContent;
import com.gptr.integration.client.ScraperClient;
import java.util.ArrayList;
import java.util.function.DoubleConsumer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** ScrapeNode —— 由 DeepResearchGraph 外迁的节点（方法体逐字未改）。
 *
 * <p><b>选句脱水（代码键名 {@code sourceDistill}，产物 {@code [DISTILLED]} 协议块）的溯源</b> ——
 * 机制源自 <b>WebWeaver</b>（arXiv 2509.13312v3，阿里）"从选中页抽取细粒度证据"那一步，
 * 但<b>本项目有意偏离论文</b>：
 * <ul>
 *   <li>论文用 LLM 生成 query-relevant summary（有损、可改写）→ 且论文侧
 *       <b>无 fidelity 审计</b>（这是其已知缺陷）；</li>
 *   <li>本项目改为 <b>LLM 逐字摘原句（只删不改）+ Java 程序化保真前检</b>
 *       （{@link EvidenceText#containsNormalized}，见 {@code distillPageSentences}）。</li>
 * </ul>
  * 偏离理由：脱水若改写句子，
 * quote 失真会以"脱水改写"形式复现（H2 同源错误）⇒ "脱水必须**只删不改**并做程序化保真前检"。
 * 根因是 {@link EvidenceNote#quote()} 的语义价值**恰恰是"逐字"**——用 LLM 生成的引文去支撑
 * LLM 生成的主张会形成自证回路。
 *
 * <p><b>勿与论文的另一层产物混淆</b>：WebWeaver 的 query-relevant summary 是**只回喂 planner、
 * 不入证据库**的；本节点产出的是**入证据库**的那一层。"蒸馏"这个名字是 flat 侧先有的命名沿用
 * （deep 侧 prompt key 为 {@code source-distill-deep}），设计文档里更准确的叫法是
 * 「<b>脱水选句</b>」。**它不是模型知识蒸馏（knowledge distillation）。**
 *
 */
final class ScrapeNode {

    private static final Logger LOG = LoggerFactory.getLogger(ScrapeNode.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 蒸馏 min 页长（低于此不蒸馏，直接 RAW——短页无"后段丢失"问题）。 */
    static final int DISTILL_MIN_PAGE_CHARS = 4000;

    /** 成块所需的**最少合格句数**：不足则整页判定失败、回退 RAW。 */
    private static final int DISTILL_MIN_KEPT_SENTENCES = 3;

    /** 单页蒸馏的最大尝试次数（两次均失败则回退 RAW）。 */
    private static final int DISTILL_MAX_ATTEMPTS = 2;

    /** 蒸馏响应缺 {@code sentences} 数组时，打进 WARN 的残留文本截断长度。 */
    private static final int DISTILL_LOG_TEXT_CHARS = 160;

    /** 蒸馏单句的**最长**长度（超过判为过长并丢弃）。 */
    private static final int DISTILL_MAX_SENTENCE_CHARS = 400;

    /** 蒸馏 WARN 日志里原文开头（srcHead）的截断长度。 */
    private static final int DISTILL_LOG_SOURCE_HEAD_CHARS = 120;

    /** 蒸馏 WARN 日志里 LLM 原始输出（rawFULL）的截断长度。 */
    private static final int DISTILL_LOG_RAW_CHARS = 2500;

    private final ScraperClient scraper;
    private final LlmClient llm;
    private final DistillGate gate;
    private final DoubleConsumer costCallback;

    /** 依赖构造注入（scraper / llm / 蒸馏闸 / 成本回调）。每次 {@code buildReal} 新建一个实例，
     *  使 gate 与 costCallback 在整图生命周期内保持一致；本类不持有可变静态状态。 */
    ScrapeNode(ScraperClient scraper, LlmClient llm, DistillGate gate, DoubleConsumer costCallback) {
        this.scraper = scraper;
        this.llm = llm;
        this.gate = gate;
        this.costCallback = costCallback;
    }

    /** 抓取当前轮 searchResults 中的 URL（visited 过滤，跨层不重复抓）。
     *  全文追加进【引用该 URL 的每个条目组】（per-query 提炼的证据归位）。
     *  extraChars>0（sourceDistill）→ 后端取更长正文、条目截断上限同步放大。
     *  distillOn 时对长页（>4000 字符）做【选句脱水】——逐字摘原句（禁改写），
     *  Java 逐句保真前检（containsNormalized），产物以 [DISTILLED] 协议块进组；
     *  蒸馏失败/合格句 <3 → 整页回退 RAW（4k 截断原文块，走现有 extract 路径）。
     *  并发 Semaphore（进程级，防 20k 长文 burst 打爆 API TPM）。
     *  <p>抓取选项每次 invoke 可不同（来自 config），故留在调用参数上；依赖走构造注入。
     *  <p>{@code budgets} 由 {@link EffectiveBudgets#of} 统一算出（原为本类内联表达式）；
     *  {@code sourceRank} 开启时在取名前按 URL 分档重排（见 {@link SourceRanker}）。 */
    Map<String, Object> runScrape(DeepResearchState state, DeepResearchGraph.ScrapeQuota quota,
                                  EffectiveBudgets budgets,
                                  boolean distillOn, boolean sourceRank) {
        String results = state.searchResults();
        List<String> visited = state.visitedUrls();
        // linked 模式按查询组轮转取名（每组保底名额）；flat 模式维持现状
        //（各查询结果按序拼接后取前 N——前几条查询吃满名额是实测的素材瓶颈）。
        // 关口 A（实验键 sourceRank）：取名前按来源分档重排，使高质量来源优先占用配额。
        // 只调顺序——不增删候选、不改配额；任何失败则各组保持原序（rank 返回 null）。
        // 注意：与下方写回 queryItems 用的 groups 是两个不同用途的列表，勿合并。
        List<List<String>> candidateGroups = state.queryItems();
        if (sourceRank && !candidateGroups.isEmpty()) {
            candidateGroups = rankGroups(candidateGroups, state.query());
        }
        List<Map.Entry<String, Integer>> picked;
        if (quota.isLinked() && !candidateGroups.isEmpty()) {
            picked = ScrapeQuotaScheduler.pickRoundRobin(candidateGroups, visited,
                    quota.quotaFor(candidateGroups.size()));
        } else if (quota.isLinked()) {
            // 组结构缺失（mock/旧整层路径）→ 回退扁平取名，不让 linked 档变成"不抓"
            picked = ScrapeQuotaScheduler.pickSequential(
                    EvidenceText.extractUrls(results), visited, quota.min());
        } else {
            picked = ScrapeQuotaScheduler.pickSequential(
                    EvidenceText.extractUrls(results), visited, quota.flatMax());
        }
        List<String> batch = picked.stream().map(Map.Entry::getKey).toList();
        if (batch.isEmpty()) {
            return Map.of(); // 无可抓新 URL
        }
        int contentCap = budgets.pageRawCap();
        List<ScrapedContent> pages;
        try {
            pages = budgets.extraChars() > 0 ? scraper.scrape(batch, budgets.extraChars()) : scraper.scrape(batch);
        } catch (Exception e) {
            // 爬虫调用失败：必须先把"已取名"计入统计再降级——否则漏斗显示"取名 0 → 抓成 0"，
            // 读者会误判为"没检索到 URL"（真实原因是抓取失败）。降级行为不变：
            // 仍不写 searchResults / fetchedUrls。
            LOG.warn("[scrape] 爬虫调用失败，本批 {} 个 URL 未抓取：{}", batch.size(), e.toString());
            Map<String, Object> failedStats = state.accumulateStats(DeepResearchState.K_CHAIN_STATS);
            failedStats.merge("picked", batch.size(), DeepResearchState.addInt());
            Map<String, Object> failed = new HashMap<>();
            failed.put("chainStats", failedStats);
            return failed;
        }
        LOG.info("[diag] scrape quota={}({}) groups={} picked={} dist={} returned={} lens={}",
                quota.quotaFor(state.queryItems().size()), quota.mode(),
                state.queryItems().size(), batch.size(), ScrapeQuotaScheduler.groupDistribution(picked),
                pages.size(),
                pages.stream().map(p -> p.content() == null ? 0 : p.content().length()).toList());
        Map<String, Object> distillStats = distillOn
                ? state.accumulateStats(DeepResearchState.K_DISTILL_STATS) : null;
        if (distillOn && !pages.isEmpty()) {
            List<ScrapedContent> processed = new ArrayList<>(pages.size());
            for (ScrapedContent p : pages) {
                if (p.content() == null || p.content().length() < DISTILL_MIN_PAGE_CHARS) {
                    processed.add(p); // 短页不蒸（RAW 路径）
                    continue;
                }
                String block = distillPageSentences(p, budgets.extraChars());
                if (block == null) {
                    processed.add(p); // 蒸馏失败 → 回退 RAW 截断版
                    distillStats.merge("fallbackPages", 1, DeepResearchState.addInt());
                } else {
                    processed.add(new ScrapedContent(p.url(), p.title(), block));
                    distillStats.merge("distilledPages", 1, DeepResearchState.addInt());
                    distillStats.merge("keptSentences", EvidenceText.sentencesIn(block),
                            DeepResearchState.addInt());
                }
            }
            pages = processed;
        }
        // 链路漏斗计数：与 distillStats 同模式跨层累加。
        // picked=本次取名数；returned=爬虫返回页数（含空正文）；validPages=正文非空页数。
        // 用途：回答"检索到了但没抓 / 抓了但没正文"各流失多少（ResearchEngineImpl 的 [chain] 日志）。
        Map<String, Object> chainStats = state.accumulateStats(DeepResearchState.K_CHAIN_STATS);
        chainStats.merge("picked", batch.size(), DeepResearchState.addInt());
        chainStats.merge("returned", pages.size(), DeepResearchState.addInt());
        int validPages = 0;
        List<String> fetched = new ArrayList<>(state.fetchedUrls());
        for (ScrapedContent p : pages) {
            if (p.content() != null && !p.content().isBlank()) {
                validPages++;
            }
            String u = p.url();
            if (u != null && !u.isBlank() && !fetched.contains(u)) {
                fetched.add(u);
            }
        }
        chainStats.merge("validPages", validPages, DeepResearchState.addInt());

        List<String> merged = new ArrayList<>(visited);
        merged.addAll(batch);
        Map<String, Object> updates = new HashMap<>();
        updates.put(DeepResearchState.K_VISITED_URLS, merged);
        updates.put(DeepResearchState.K_CHAIN_STATS, chainStats);
        updates.put(DeepResearchState.K_FETCHED_URLS, fetched);
        if (pages.isEmpty()) {
            return updates;
        }
        StringBuilder sb = new StringBuilder(results);
        // 深拷贝条目组再追加（LangGraph4j 只合并 updates 声明的键，不能 mutate 内部对象）
        List<List<String>> groups = new ArrayList<>();
        for (List<String> g : state.queryItems()) {
            groups.add(new ArrayList<>(g));
        }
        for (ScrapedContent p : pages) {
            String content = p.content() == null ? "" : p.content();
            boolean distilled = content.startsWith(EvidenceText.DISTILLED_MARKER);
            String full = distilled
                    ? content // 蒸馏块：自带 [DISTILLED] URL 头 + 原句列表（≤2500，不再截）
                    : "Full content of " + p.url() + ":\n" + ContextManager.truncateEach(content, contentCap);
            sb.append("\n").append(full).append("\n");
            // per-query 条目组归位：引用该 URL 的组各自追加条目
            EvidenceText.appendToGroupsReferencing(groups, p.url(), full);
        }
        updates.put(DeepResearchState.K_SEARCH_RESULTS, sb.toString());
        updates.put(DeepResearchState.K_QUERY_ITEMS, groups);
        if (distillStats != null) {
            updates.put(DeepResearchState.K_DISTILL_STATS, distillStats);
        }
        return updates;
    }

    /** 单页选句蒸馏：调 source-distill-deep → 逐句保真前检（归一化包含于原文）→
     *  组装 [DISTILLED] 块；坏输出/合格句<3 → 重试一次（LLM 摘句偶发退化到训练记忆句，
     *  实测同页同 prompt 两次调用 kept 0 vs 15）；仍 <3 → null（调用方回退 RAW）。
     *  返回 null 前把失败原因留日志（蒸馏静默吞错是审计教训——C 痛点）。 */
    private String distillPageSentences(ScrapedContent p, int contentCap) {
        String source = p.content() == null ? "" : p.content();
        for (int attempt = 0; attempt < DISTILL_MAX_ATTEMPTS; attempt++) {
            // 中断即退出、不再重试。gate.around 因中断返回的 null 不能被当成"本次蒸馏失败"，
            // 否则中断响应的时机与改造前不一致（改造前：acquire 被中断就直接 return）。
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            final int attemptNo = attempt;
            String block = gate.around(() -> distillOnce(p, contentCap, source, attemptNo));
            if (block != null) {
                return block;
            }
        }
        return null; // 两次均失败 → RAW 回退（不失败）
    }

    /** 蒸馏的<b>一次尝试</b>：返回 {@code null} = 本次不可用（空输出 / 无 sentences / 合格句 &lt;3 / 异常）。
     *  返回 null 前把失败原因留日志（蒸馏静默吞错是审计教训——C 痛点）。
     *  <p>并发限制由调用方经 {@link DistillGate#around} 施加，本方法只管"一次尝试"本身。 */
    private String distillOnce(ScrapedContent p, int contentCap, String source, int attempt) {
        String raw = null;
        try {
            String system = DeepResearchPrompts.get("source-distill-deep.system");
            String user = DeepResearchPrompts.get("source-distill-deep.user")
                    .replace("{url}", p.url())
                    .replace("{content}", ContextManager.truncateEach(source, contentCap));
            raw = llm.chatJson(system, user);
            if (costCallback != null) {
                costCallback.accept(llm.lastCallCostUsd());
            }
            if (raw == null || raw.isBlank()) {
                LOG.warn("[diag] distill empty raw for {} (attempt {})", p.url(), attempt);
                return null;
            }
            // 容错剥围栏（```json … ```）后解析——与 DeepResearchPrompts 解析容错同哲学
            String text = raw.trim();
            if (text.startsWith("```")) {
                int first = text.indexOf('\n');
                int last = text.lastIndexOf("```");
                if (first >= 0 && last > first) {
                    text = text.substring(first + 1, last).trim();
                }
            }
            JsonNode root = MAPPER.readTree(text);
            JsonNode arr = root.path("sentences");
            if (!arr.isArray()) {
                LOG.warn("[diag] distill no sentences array for {} (attempt {}): {}",
                        p.url(), attempt, text.substring(0, Math.min(DISTILL_LOG_TEXT_CHARS, text.length())));
                return null;
            }
            StringBuilder block = new StringBuilder(EvidenceText.DISTILLED_MARKER).append(p.url()).append("\n");
            int kept = 0;
            int tooShort = 0;
            int tooLong = 0;
            int notVerbatim = 0;
            for (JsonNode n : arr) {
                String s = n.asText("");
                if (s == null || s.isBlank()) {
                    continue;
                }
                String single = s.trim().replaceAll("\\s+", " ");
                if (single.length() < EvidenceText.DISTILL_MIN_SENTENCE_CHARS) {
                    tooShort++;
                    continue;
                }
                if (single.length() > DISTILL_MAX_SENTENCE_CHARS) {
                    tooLong++;
                    continue;
                }
                if (!EvidenceText.containsNormalized(source, single)) {
                    notVerbatim++;
                    continue; // 保真前检：非原文原句（改写/记忆句）剔除
                }
                block.append("- ").append(single).append("\n");
                kept++;
            }
            if (kept >= DISTILL_MIN_KEPT_SENTENCES) {
                return block.toString();
            }
            LOG.warn("[diag] distill kept<{} for {} (attempt={} kept={} short={} long={} "
                            + "rewritten={} srcLen={} srcHead={} rawFULL={})",
                    DISTILL_MIN_KEPT_SENTENCES, p.url(), attempt, kept, tooShort, tooLong,
                    notVerbatim,
                    source == null ? -1 : source.length(),
                    source == null ? "" : source.substring(0, Math.min(DISTILL_LOG_SOURCE_HEAD_CHARS,
                            source.length())).replace("\n", "\\n"),
                    raw.length() > DISTILL_LOG_RAW_CHARS ? raw.substring(0, DISTILL_LOG_RAW_CHARS) : raw);
        } catch (Exception e) {
            LOG.warn("[diag] distill exception for {} (attempt {}): {}",
                    p.url(), attempt, e.toString());
        }
        return null;
    }

    /** 对每组候选按来源分档重排（high 前置）；任一组失败则该组保持原序。
     *  **只调顺序**：不增删候选、不改配额。URL 缺失的条目以空串占位，保持下标对齐。 */
    private List<List<String>> rankGroups(List<List<String>> groups, String query) {
        List<List<String>> out = new ArrayList<>(groups.size());
        for (List<String> group : groups) {
            List<String> urls = group.stream().map(this::urlOrBlank).toList();
            SourceRanker.Priority p = SourceRanker.rank(urls, query, llm, costCallback);
            out.add(SourceRanker.reorder(group, p));
        }
        return out;
    }

    /** 条目的 URL；缺 URL 时以空串占位——保持与组内下标对齐，使分档结果能回指原条目。 */
    private String urlOrBlank(String item) {
        String u = ScrapeQuotaScheduler.urlOfItem(item);
        return u == null ? "" : u;
    }
}
