package com.gptr.engine;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.common.engine.ActivitySink;
import com.gptr.common.engine.ResearchEngine;
import com.gptr.common.engine.StageResult;
import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskStage;
import com.gptr.engine.budget.Budgets;
import com.gptr.engine.budget.CurateBudget;
import com.gptr.engine.budget.ExtractionBudget;
import com.gptr.engine.budget.WritingBudget;
import com.gptr.engine.context.ContextManager;
import com.gptr.engine.epoc.DeepResearchGraph;
import com.gptr.engine.epoc.DeepResearchPrompts;
import com.gptr.engine.epoc.DeepResearchState;
import com.gptr.engine.epoc.DistillGate;
import com.gptr.engine.epoc.EvidenceNote;
import com.gptr.engine.epoc.PostgresCheckpointSaver;
import com.gptr.engine.plan.SubQuery;
import com.gptr.engine.plan.SubQueryPlanner;
import com.gptr.engine.search.BlockedSearchClient;
import com.gptr.engine.search.Searcher;
import com.gptr.engine.search.SourceMirror;
import com.gptr.engine.write.CitationVerifier;
import com.gptr.engine.write.ReportWriter;
import com.gptr.engine.write.SectionWriter;
import com.gptr.integration.client.LlmClient;
import com.gptr.integration.client.ScrapedContent;
import com.gptr.integration.client.ScraperClient;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResult;
import com.gptr.integration.exception.TransientApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.TreeSet;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 研究引擎实现：实现 {@link ResearchEngine}，按模式编排阶段。
 *
 * <p>普通 web 研究：五阶段平面流水线（PLANNING → SEARCHING → SCRAPING[抓正文] →
 * SUMMARIZING[预算整理] → WRITING[正文依据 + 引用核验]）；deep research
 * （config mode=deep_research）：PLANNING → RESEARCH（LangGraph4j 递归图，图内抓取）→ WRITING。
 *
 * <p>阶段间数据经实例字段流转；RESEARCH 阶段图内成本累加进该阶段 StageResult.costUsd
 * （底座预算机制天然生效）。
 *
 * <p>兼容旧测试配置：{@code mock.failAtStage}、{@code mock.stageDelayMs}、
 * {@code mock.costPerStage}。新增键：{@code retriever}/{@code maxResults}/
 * {@code fetchFullPage}/{@code maxScrapeUrls}。
 *
 * <p><b>本类分块地图</b>（按块名与方法名定位——行号会腐烂，故本注释刻意不写行号）：
 * <ol>
 *   <li><b>阶段编排</b> —— {@code STAGES_FLAT}/{@code STAGES_DEEP} 定义两种模式的阶段链；
 *       {@code stages()}/{@code runStage()} 是底座调用入口；{@code finalReport()} 取产物。</li>
 *   <li><b>各阶段实现</b> —— 每阶段一个 {@code xxxPayload()}：{@code planPayload} /
 *       {@code deepResearchPayload}（图执行在此）/ {@code searchPayload} / {@code scrapPayload} /
 *       {@code summarizePayload}。</li>
 *   <li><b>写作阶段</b> —— {@code writePayload} 编排"单遍/逐节"两条路径；逐节路径
 *       {@code writeSectionReport} = outline → 逐节写作 → takeaways → 机械 References；
 *       可引用来源由 {@code writingAuthorizedUrls} 决定。</li>
 *   <li><b>链路观测</b> —— {@code logChainFunnel} 打印 [chain] 两行漏斗，外加四个纯函数辅助
 *       （{@code unreadNoteSources} / {@code distinctMirrorKeys} / {@code canonicalNoteSources} /
 *       {@code canonicalSet}）。</li>
 *   <li><b>工具</b> —— {@code distillPage}（flat 蒸馏）、{@code parseJsonLenient}（宽松 JSON）、
 *       {@code notePayload} / {@code sleep} / {@code activity}。</li>
 * </ol>
 *
 * <p><b>实例作用域</b>：本类<b>每任务新建</b>（worker 的 Job 每任务 create 一个），阶段间数据经
 * 实例字段流转 → 实例字段无需加锁，但<b>严禁跨任务共享</b>。
 */
public class ResearchEngineImpl implements ResearchEngine {

    private static final Logger LOG =
            LoggerFactory.getLogger(ResearchEngineImpl.class);

    private static final List<TaskStage> STAGES_FLAT = List.of(
            TaskStage.PLANNING, TaskStage.SEARCHING, TaskStage.SCRAPING,
            TaskStage.SUMMARIZING, TaskStage.WRITING);
    private static final List<TaskStage> STAGES_DEEP = List.of(
            TaskStage.PLANNING, TaskStage.RESEARCH, TaskStage.WRITING);

    private static final Pattern SOURCE_URL = Pattern.compile("\\[source:\\s*(https?://[^\\]]+)\\]");
    /** 逐节活动事件 label（节标题）的截断长度。 */
    private static final int ACTIVITY_SECTION_LABEL_MAX_CHARS = 80;

    /** [chain] 日志用任务号的前缀长度。 */
    private static final int LOG_TASK_ID_CHARS = 8;

    private final ResearchTask task;
    /** [chain] 漏斗日志的任务标识（taskId 前 8 位）——多任务并发时用于归因。 */
    private final String logTaskId;
    private final String query;
    private final int maxSubQueries;
    private final boolean deepResearch;
    private final int breadth;
    private final int depth;
    private final String failAtStage;
    private final long stageDelayMs;
    private final double costPerStage;
    private final SearchOptions searchOptions;
    private final boolean fetchFullPage;
    private final int maxScrapeUrls;
    private final String scrapeQuotaMode;   // flat（现状）/ linked（联动+组轮转）
    private final int scrapePerQueryQuota;  // linked 每查询组名额
    private final int scrapeMinQuota;       // linked 配额下界
    private final int scrapeHardCap;        // linked 配额上界
    private final String coverMode;         // dimensions / legacy / off
    private final boolean assignByCitation; // 归节模式（直引优先 vs 文本匹配）
    private final int evidenceIndexMaxChars; // 证据目录注入预算
    private final boolean followUpDriven; // learnings 驱动下一层
    private final double breadthDecay;    // 每层查询数衰减
    private final int clarifyQuestions;   // 澄清前奏问题数（0=关，默认 3）
    private final boolean perQueryExtract; // per-query 独立提炼（默认 true）
    private final boolean curateSources;   // 来源质量闸（默认 true，见 EngineConfig）
    private final boolean sourceRank;      // 关口 A：取名前按 URL 分档（默认 false）
    private final int curatorMaxSources;   // curate 保留上限（默认 10）
    private final boolean sourceDistill;   // 来源提炼（默认关=截断兜底）
    private final int distillMaxChars;     // 进提炼的正文上限
    private final int distillConcurrency;  // 任务级并发配置；⚠️ **不影响闸容量**（见构造器注释）
    private final DistillGate distillGate;  // 蒸馏并发闸（装配层注入的进程内共享件）
    private final boolean extractOnDistilled; // 蒸馏句块是否仍走 extract（X=true/Y=false）
    private final int contextMaxChars;      // 回退/单遍写作上下文预算
    private final String language;         // 研报语言（报告 writer 由 factory 使用）
    private final boolean planReflect;     // 层间计划反思（默认开）
    private final SubQueryPlanner planner;
    private final Searcher searcher;
    private final LlmClient llmClient;
    private final boolean sectionWriting;  // outline 逐节写作（默认开，见 EngineConfig）
    private final SectionWriter sectionWriter; // null=关闭逐节写作
    private final boolean sectionRetryOnUnauthorized; // 节级违规重写
    private final SearchClient searchClient;
        /** 提炼域预算载体（原此处 4 个 private static final 常量已并入）。 */
    private final ExtractionBudget extraction;
        /** 精选域预算载体（原 FLAT_CURATE_TOTAL_MAX_CHARS 并入 totalMaxChars）。 */
    private final CurateBudget curate;
        /** 写作域预算载体（原 SECTION_PREVIEW_MAX_CHARS 并入）。
     *  {@code static}：本类既有静态工具（{@code truncateForPreview}）也要读它。 */
    private static final WritingBudget WRITING = Budgets.defaults().writing();

    /** 研究进程活动观察者（可空=无观测；失败由调用侧吞掉，绝不影响研究）。 */
    private ActivitySink activitySink;

    @Override
    public void setActivitySink(ActivitySink sink) {
        this.activitySink = sink;
    }

    /** 观测 emit 帮助（sink 空 = 零开销；detail 仅元数据）。 */
    private void activity(TaskStage stage, String kind, String label,
                          Map<String, Object> detail) {
        if (activitySink != null) {
            try {
                activitySink.emit(stage, kind, label, detail);
            } catch (Exception ignored) {
                // 观测失败不连带任务
            }
        }
    }
    private final ReportWriter reportWriter;
    private final ScraperClient scraperClient;         // 可空：无则 SCRAPING 记 note 不抓
    private final ContextManager contextManager;
    private final PostgresCheckpointSaver checkpointSaver; // 可空（null 时图用内存 checkpoint）
    private final ObjectMapper mapper = new ObjectMapper();

    // 阶段间状态
    private List<SubQuery> subQueries = List.of();
    private List<SearchResult> sources = List.of();
    private List<ScrapedContent> scrapedPages = List.of(); // flat SCRAPING 产出
    private String summarizedContext = "";                  // flat SUMMARIZING 产出
    private List<String> deepLearnings = List.of();         // RESEARCH 阶段图产出
    private List<String> deepSourceUrls = List.of();         // RESEARCH 真实检索 URL（授权来源）
    private List<String> deepEvidenceBank = List.of();       // RESEARCH 证据库（EvidenceNote JSON 串）
    // 链路漏斗：抓取成功页 URL + 图内累计计数（picked/returned/validPages）。
    // 与 visitedUrls（=尝试抓取，含失败）区分——"未读来源"的差集必须以真正读到的页为基准。
    private List<String> deepFetchedUrls = List.of();
    private int deepChainPicked;
    private int deepChainReturned;
    private int deepChainValidPages;
    private String deepResearchState = "";                   // 中央研究状态（outline 输入）
    private String report = "";
    private double researchCost;                            // RESEARCH 阶段图内成本累加

    /** 引擎的**依赖**（长生命周期协作件）——与「一次任务的配置」分离。
     *  8 个分量类型各不相同（无同类批量风险），故用 record 位置构造而非 builder
     *  （对比 {@code DeepResearchGraph.NodeSet}：那里 8 个参数同类型，才必须 builder）。 */
    public record EngineDeps(SubQueryPlanner planner, Searcher searcher, LlmClient llmClient,
                             SearchClient searchClient, ReportWriter reportWriter,
                             ScraperClient scraperClient, ContextManager contextManager,
                             PostgresCheckpointSaver checkpointSaver) {
    }

    /** 简化构造（测试 / mock 通道）：**自建一个默认容量的蒸馏闸**（每引擎一个，测试间天然隔离）；
     *  生产走下面的全参构造，由装配层注入进程内共享的闸。 */
    public ResearchEngineImpl(ResearchTask task, EngineDeps deps) {
        this(task, deps, false, new DistillGate(3));
    }

    /** @param allowMockConfig 是否允许任务 config 里的 mock.* 测试旋钮（生产必须 false）
     *  @param distillGate 蒸馏并发闸——由装配层注入的<b>进程内共享件</b>（见 {@code DistillGate} 类注释） */
    public ResearchEngineImpl(ResearchTask task, EngineDeps deps,
                              boolean allowMockConfig, DistillGate distillGate) {
        this.task = task;
        Object tid = task.getId();
        String tidStr = tid == null ? "" : tid.toString();
        this.logTaskId = shortTaskId(tidStr);
        this.query = task.getQuery();
        this.planner = deps.planner();
        this.searcher = deps.searcher();
        this.llmClient = deps.llmClient();
        this.reportWriter = deps.reportWriter();
        this.scraperClient = deps.scraperClient();
        this.contextManager = deps.contextManager();
        this.checkpointSaver = deps.checkpointSaver();

        var cfg = new EngineConfig(task.getConfig(), allowMockConfig);
        // 任务 config 带 blockedUrls → 检索结果层屏蔽（flat 与 deep 图共用同一 client）
        this.searchClient = cfg.blockedUrls.isEmpty()
                ? deps.searchClient()
                : new BlockedSearchClient(deps.searchClient(), cfg.blockedUrls);
        this.maxSubQueries = cfg.maxSubQueries;
        this.deepResearch = cfg.deepResearch;
        this.breadth = cfg.breadth;
        this.depth = cfg.depth;
        this.failAtStage = cfg.failAtStage;
        this.stageDelayMs = cfg.stageDelayMs;
        this.costPerStage = cfg.costPerStage;
        this.searchOptions = SearchOptions.of(cfg.retriever, cfg.maxResults);
        this.fetchFullPage = cfg.fetchFullPage;
        this.maxScrapeUrls = cfg.maxScrapeUrls;
        this.scrapeQuotaMode = cfg.scrapeQuotaMode;
        this.scrapePerQueryQuota = cfg.scrapePerQueryQuota;
        this.scrapeMinQuota = cfg.scrapeMinQuota;
        this.scrapeHardCap = cfg.scrapeHardCap;
        this.coverMode = cfg.coverMode;
        this.followUpDriven = cfg.followUpDriven;
        this.breadthDecay = cfg.breadthDecay;
        this.clarifyQuestions = cfg.clarifyQuestions;
        this.perQueryExtract = cfg.perQueryExtract;
        this.curateSources = cfg.curateSources;
        this.sourceRank = cfg.sourceRank;
        this.curatorMaxSources = cfg.curatorMaxSources;
        this.sourceDistill = cfg.sourceDistill;
        this.distillMaxChars = cfg.distillMaxChars;
        this.distillConcurrency = cfg.distillConcurrency;
        this.extractOnDistilled = cfg.extractOnDistilled;
        this.contextMaxChars = cfg.contextMaxChars;
        this.language = cfg.language;
        this.planReflect = cfg.planReflect;
        this.sectionWriting = cfg.sectionWriting;
        this.sectionRetryOnUnauthorized = cfg.sectionRetryOnUnauthorized;
        // 蒸馏并发闸由装配层注入（进程内共享）——本类不再"配置"一个静态字段。
        // ⚠️ 任务级 cfg.distillConcurrency 自本日起**不再影响闸容量**：闸是进程级共享的，
        // 容量必须来自同生命周期的应用级配置（gptr.engine.distill-concurrency）。
        this.distillGate = distillGate;
        this.sectionWriter = new SectionWriter(deps.llmClient(),
                cfg.sectionContextChars, cfg.sectionRetryOnUnauthorized, cfg.maxSections,
                cfg.priorSectionsMaxChars, cfg.language);
        // 直引归节开关 + 证据目录预算
        this.assignByCitation = cfg.assignByCitation;
        this.evidenceIndexMaxChars = cfg.evidenceIndexMaxChars;
                // 预算载体：代码内常量（原 private static final，提炼域）
        this.extraction = Budgets.defaults().extraction();
        this.curate = Budgets.defaults().curate();

        // 生效值快照：把"本任务实际生效的是什么"从跨文件推理变成看日志
        // 观测失败不得影响任务——可观测性不能成为任务失败的新原因。
        try {
            for (String line : cfg.snapshot()) {
                LOG.info("[snapshot] task={} | {}", logTaskId, line);
            }
        } catch (Exception e) {
            LOG.warn("[snapshot] failed for task={}: {}", logTaskId, e.toString());
        }
    }

    @Override
    public List<TaskStage> stages() {
        return deepResearch ? STAGES_DEEP : STAGES_FLAT;
    }

    @Override
    public StageResult runStage(TaskStage stage) {
        if (failAtStage != null && failAtStage.equals(stage.name())) {
            throw new IllegalStateException("mock failure at stage " + stage);
        }
        sleep(stageDelayMs);
        return switch (stage) {
            case PLANNING -> planPayload();
            case RESEARCH -> deepResearchPayload();
            case SEARCHING -> searchPayload();
            case SCRAPING -> scrapPayload();
            case SUMMARIZING -> summarizePayload();
            case WRITING -> writePayload();
        };
    }

    @Override
    public String finalReport() {
        return report.isEmpty()
                ? "# 占位报告\n\n（本次运行未产出报告正文）\n"
                : report;
    }

    private StageResult planPayload() {
        subQueries = planner.plan(query, maxSubQueries);
        ArrayNode arr = mapper.createArrayNode();
        for (SubQuery s : subQueries) {
            ObjectNode o = arr.addObject();
            o.put("query", s.query());
            o.put("researchGoal", s.researchGoal());
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("stage", "PLANNING");
        payload.set("queries", arr);
        return new StageResult(TaskStage.PLANNING, payload.toString(), llmClient.lastCallCostUsd());
    }

    /** RESEARCH 阶段：执行 LangGraph4j deep research 递归图。 */
    private StageResult deepResearchPayload() {
        try {
            researchCost = 0.0;
            // 执行前清空本任务的图 checkpoint——LangGraph4j 对同 thread 二次 invoke
            // 是"旧状态播种 + 全图重放"，retry/租约回队重执行时会导致 learnings 残留重复、
            // 成本双计；重试语义 = 从零重跑
            if (checkpointSaver != null) {
                checkpointSaver.delete(task.getId().toString());
            }
            CompiledGraph<DeepResearchState> graph = DeepResearchGraph.buildReal(
                    // 图节点活动事件（activitySink 随依赖集传入）
                    new DeepResearchGraph.GraphDeps(llmClient, searchClient, scraperClient, distillGate,
                            checkpointSaver, cost -> researchCost += cost, activitySink),
                    searchOptions,
                    new DeepResearchGraph.ResearchOptions(
                            EffectiveBudgets.of(extraction, sourceDistill, distillMaxChars),
                            new DeepResearchGraph.PlanningOptions(clarifyQuestions, coverMode),
                            new DeepResearchGraph.ScrapeOptions(fetchFullPage,
                                    // 抓取配额（flat=现状 / linked=联动+组轮转）
                                    "linked".equalsIgnoreCase(scrapeQuotaMode)
                                            ? DeepResearchGraph.ScrapeQuota.linked(
                                                    scrapePerQueryQuota, scrapeMinQuota, scrapeHardCap)
                                            : DeepResearchGraph.ScrapeQuota.flat(maxScrapeUrls),
                                    sourceDistill, sourceRank),
                            new DeepResearchGraph.ExtractOptions(perQueryExtract, extractOnDistilled),
                            // curate 依赖 per-query 条目组结构：perQueryExtract=false 时禁用
                            new DeepResearchGraph.CurateOptions(curateSources && perQueryExtract,
                                    curatorMaxSources),
                            new DeepResearchGraph.FollowUpOptions(followUpDriven, breadthDecay,
                                    planReflect)),
                                        // 预算载体：代码内常量
                    // ——独立传入，不并入 ResearchOptions；本批只接线检索域
                    Budgets.defaults());
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(task.getId().toString()).build();
            DeepResearchState state = graph.invoke(
                    Map.of("query", query, "breadth", breadth, "depth", depth), config)
                    .orElseThrow();
            deepLearnings = state.learnings();
            // 报告授权来源 = 图内真实检索 URL（替代 LLM 自报 sourceUrl 的自证闭环）
            deepSourceUrls = new ArrayList<>(state.collectedUrls());
            // 证据库（per-query 结构化路径产出；空 = 旧整层路径未启用）
            deepEvidenceBank = new ArrayList<>(state.evidenceBank());
            // 链路漏斗：抓取成功页 + 图内累计计数（写入 WRITING 后的 [chain] 汇总行）
            deepFetchedUrls = new ArrayList<>(state.fetchedUrls());
            deepChainPicked = state.chainStat("picked");
            deepChainReturned = state.chainStat("returned");
            deepChainValidPages = state.chainStat("validPages");
            deepResearchState = state.researchState();                     // 中央研究状态（outline 输入）
            // 研究零产出（检索/解析全失败被守卫提前 END）→ 显式失败而非
            // 让 WRITING 基于空/编造内容"成功"
            if (deepLearnings.isEmpty()) {
                throw new TransientApiException("research",
                        "deep research produced no learnings (all searches/parses failed)");
            }

            ObjectNode payload = mapper.createObjectNode();
            payload.put("stage", "RESEARCH");
            payload.put("depthReached", state.currentDepth());
            payload.put("learnings", deepLearnings.size());
            payload.put("followUpQuestions", state.followUpQuestions().size());
            payload.put("fetchFullPage", fetchFullPage && scraperClient != null);
            payload.put("clarifyApplied", state.clarifyApplied());
            payload.put("evidenceNotes", state.evidenceBank().size()); // 证据库条目数（观测点）
            // 层间计划反思是否产出中央研究状态（"(none)"=坏输出/关闭）
            String rs = state.researchState();
            payload.put("planReflectApplied", planReflect && !rs.isBlank() && !"(none)".equals(rs));
            return new StageResult(TaskStage.RESEARCH, payload.toString(), researchCost);
        } catch (TransientApiException e) {
            throw e; // 保留错误分类（TRANSIENT_EXHAUSTED，可运维重试）
        } catch (Exception e) {
            throw new IllegalStateException("deep research graph failed: " + e.getMessage(), e);
        }
    }

    private StageResult searchPayload() {
        // 编排结果带实际命中源集（每子查询成功响应 sourceUsed 去重）
        Searcher.SearchOutcome outcome =
                searcher.searchAll(subQueries, searchOptions);
        sources = outcome.results();
        // 检索成功但零结果（如反爬空页）→ 显式失败（可重试），防空上下文编造
        if (sources.isEmpty()) {
            throw new TransientApiException(searchClient.name(),
                    "search succeeded but returned no results");
        }
        // 一次检索活动的元数据（链名/实际命中源/引擎 retriever 配置/计数）
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("chain", searchClient.name());
        if (!outcome.hitSources().isEmpty()) {
            detail.put("hitSources", outcome.hitSources());
        }
        detail.put("retrieverCfg", searchOptions.retriever() == null
                ? "(crawler-default)" : searchOptions.retriever());
        detail.put("queries", subQueries.size());
        detail.put("results", sources.size());
        activity(TaskStage.SEARCHING, "search", "searchAll", detail);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("stage", "SEARCHING");
        payload.put("sources", searchClient.name());
        payload.put("results", sources.size());
        if (searchOptions.retriever() != null) {
            payload.put("retriever", searchOptions.retriever());
        }
        return new StageResult(TaskStage.SEARCHING, payload.toString(), costPerStage);
    }

    /**
     * SCRAPING 阶段：对 SEARCHING 结果抓取正文（上限 maxScrapeUrls）。
     * sourceDistill=true 时请求后端放大正文上限（distillMaxChars）并对每页做 LLM
     * 要点提炼（长网页后半段核心数据不再因 3000 截断丢失）；提炼失败回退原文截断版
     * （不失败，质量降级）。提炼成本 ≈ 调用次数 × 单次成本（记入 stage，近似口径）。
     */
    private StageResult scrapPayload() {
        if (scraperClient == null || !fetchFullPage || maxScrapeUrls <= 0 || sources.isEmpty()) {
            scrapedPages = List.of();
            return notePayload(TaskStage.SCRAPING,
                    noScrapeReason());
        }
        List<String> urls = new ArrayList<>(new LinkedHashSet<>(
                sources.stream().map(SearchResult::url).toList()));
        List<String> targets = urls.size() > maxScrapeUrls ? urls.subList(0, maxScrapeUrls) : urls;
        boolean distill = sourceDistill;
        try {
            scrapedPages = distill
                    ? scraperClient.scrape(targets, distillMaxChars)
                    : scraperClient.scrape(targets);
        } catch (Exception e) {
            scrapedPages = List.of();
            return notePayload(TaskStage.SCRAPING, "scrape failed: " + e.getMessage());
        }
        int distillCount = 0;
        int distillFallback = 0;
        if (distill && !scrapedPages.isEmpty()) {
            List<ScrapedContent> distilled = new ArrayList<>();
            for (ScrapedContent p : scrapedPages) {
                String points = distillPage(p);
                if (points == null) {
                    distilled.add(p); // 提炼失败 → 回退原文（截断版）
                    distillFallback++;
                } else {
                    distilled.add(new ScrapedContent(p.url(), p.title(), points));
                    distillCount++;
                }
            }
            scrapedPages = distilled;
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("stage", "SCRAPING");
        payload.put("requested", targets.size());
        payload.put("scraped", scrapedPages.size());
        payload.put("totalChars", scrapedPages.stream()
                .mapToInt(p -> p.content() == null ? 0 : p.content().length()).sum());
        if (distill) {
            payload.put("distill", distillCount);
            payload.put("distillFallback", distillFallback);
            payload.put("distillMaxChars", distillMaxChars);
        }
        return new StageResult(TaskStage.SCRAPING, payload.toString(),
                distillCount > 0 ? distillCount * llmClient.lastCallCostUsd() : costPerStage);
    }

    /**
     * 单页 LLM 证据提炼（以研究问题为锚；输出总结+硬核证据块，兼容旧 points 结构）。
     * 坏输出/异常 → null（回退截断原文）。
     */
    private String distillPage(ScrapedContent page) {
        String system = DeepResearchPrompts.get("source-distill.system");
        String user = DeepResearchPrompts.get("source-distill.user")
                .replace("{query}", query)
                .replace("{url}", page.url())
                .replace("{title}", page.title() == null ? "" : page.title())
                .replace("{content}", ContextManager.truncateEach(
                        page.content() == null ? "" : page.content(), distillMaxChars));
        try {
            String raw = llmClient.chatJson(system, user);
            JsonNode root = parseJsonLenient(raw);
            if (root == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder("（LLM 提炼：总结+硬核证据，覆盖全文）\n");
            String summary = root.path("summary").asText("");
            if (!summary.isBlank()) {
                String head = ContextManager.truncateEach(summary.trim(),
                        extraction.flatDistillSummaryMaxChars());
                sb.append("总结：").append(head).append("\n");
            }
            JsonNode evidence = root.path("evidence");
            if (!evidence.isArray()) {
                evidence = root.path("points"); // 兼容旧结构 {"points":[...]}
            }
            if (!evidence.isArray() || evidence.isEmpty()) {
                return sb.length() > "（LLM 提炼：总结+硬核证据，覆盖全文）\n".length()
                        ? sb.toString() : null; // 只有总结也算成功
            }
            sb.append("硬核证据：\n");
            int kept = 0;
            for (JsonNode n : evidence) {
                String point = n.isTextual() ? n.asText()
                        : n.path("point").asText(n.path("insight").asText(""));
                if (point.isBlank() || kept >= extraction.flatDistillMaxEvidenceItems()) {
                    continue;
                }
                String one = ContextManager.truncateEach(point.trim(),
                        extraction.flatDistillEvidenceMaxChars());
                sb.append("- ").append(one).append("\n");
                kept++;
            }
            return kept == 0 ? null : sb.toString();
        } catch (Exception e) {
            // 解析失败 → 返回 null：调用方按"无内容"降级，不中断流程
            return null;
        }
    }

    /** 容错 JSON 解析：裸 JSON → 剥 ```json 围栏后取首个 {...}。 */
    private JsonNode parseJsonLenient(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int first = text.indexOf('\n');
            int last = text.lastIndexOf("```");
            if (first >= 0 && last > first) {
                text = text.substring(first + 1, last).trim();
            }
        }
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            try {
                Matcher m = Pattern.compile("\\{[\\s\\S]*\\}").matcher(raw);
                if (m.find()) {
                    return mapper.readTree(m.group());
                }
            } catch (Exception ignored) {
                // fallthrough
            }
            return null;
        }
    }

    /**
     * SUMMARIZING 阶段：正文/摘要条目经 ContextManager 预算整理。
     * curateSources=true（默认开，可显式关）时先对条目整批做
     * LLM 质量排序（对标 py researcher.py 的 source_curator.curate_sources）——
     * 排序精选、坏输出回退原文（防误杀），随后才进预算。
     */
    private StageResult summarizePayload() {
        // 条目级构建（每源一条；抓取正文优先，摘要兜底）
        List<String> blocks = new ArrayList<>();
        if (!scrapedPages.isEmpty()) {
            for (ScrapedContent p : scrapedPages) {
                blocks.add("Title: " + p.title() + "\nURL: " + p.url() + "\nContent: "
                        + ContextManager.truncateEach(p.content(), extraction.maxCharsPerSource()));
            }
        } else {
            for (SearchResult r : sources) {
                String block = "Title: " + r.title() + "\nURL: " + r.url() + "\nSnippet: " + r.snippet();
                if (r.hasContent()) {
                    block += "\nContent: " + ContextManager.truncateEach(
                            r.content(), extraction.maxCharsPerSource());
                }
                blocks.add(block);
            }
        }
        boolean curateCalled = false;
        if (curateSources && !blocks.isEmpty()) {
            List<String> kept = curateFlat(blocks);
            curateCalled = kept != blocks;
            if (curateCalled) {
                blocks = kept;
            }
        }
        summarizedContext = contextManager.truncateToBudget(String.join("\n\n", blocks) + "\n\n");
        ObjectNode payload = mapper.createObjectNode();
        payload.put("stage", "SUMMARIZING");
        payload.put("sourcesInContext", blocks.size());
        payload.put("contextChars", summarizedContext.length());
        payload.put("mode", scrapedPages.isEmpty() ? "snippet-fallback" : "full-content");
        if (curateCalled) {
            payload.put("curated", true);
        }
        return new StageResult(TaskStage.SUMMARIZING, payload.toString(),
                curateCalled ? llmClient.lastCallCostUsd() : costPerStage);
    }

    /**
     * flat 质量闸：整批条目一次 LLM 排序精选（编号条目、1-based kept）。
     * 坏输出/空 kept/异常 → 返回原列表（防误杀）。curate prompt 同 deep 图。
     */
    private List<String> curateFlat(List<String> blocks) {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            entries.append(i + 1).append(". ").append(blocks.get(i)).append("\n\n");
            if (entries.length() > curate.totalMaxChars()) {
                entries.append("...[truncated]");
                break;
            }
        }
        String system = DeepResearchPrompts.get("curate-sources.system");
        String user = DeepResearchPrompts.get("curate-sources.user")
                .replace("{query}", query)
                .replace("{maxSources}", String.valueOf(curatorMaxSources))
                .replace("{entries}", entries.toString());
        try {
            String raw = llmClient.chatJson(system, user);
            var kept = DeepResearchPrompts.parseKeptIndices(raw);
            if (kept == null || kept.isEmpty()) {
                return blocks; // 坏输出 → 回退原文
            }
            List<String> out = new ArrayList<>();
            for (int k : kept) {
                if (k >= 1 && k <= blocks.size() && !out.contains(blocks.get(k - 1))) {
                    out.add(blocks.get(k - 1));
                }
                if (out.size() >= curatorMaxSources) {
                    break;
                }
            }
            return out.isEmpty() ? blocks : out; // 全删 → 回退（宁可不过滤不可删光）
        } catch (Exception e) {
            // 例外 → 回退未过滤原文（宁可不过滤，不可删光；与上方正常回退同语义）
            return blocks;
        }
    }

    private StageResult writePayload() {
        List<String> sourceUrls = new ArrayList<>();
        String context = null;
        SectionWriter.WriteResult sectionResult = null;
        if (deepResearch) {
            // 写授权 = 实际有证据锚的 URL（bank notes sourceUrl 集；bank 空走
            // learnings [source:] 提取——该路径 URL 已在 extract 过提炼授权过滤）。
            // 替代旧的"全检索集"（含未抓取/未提炼 URL，会为'只见过标题列表'的
            // 引用开合法通道）。逐节路径的节级授权本就用 note 锚集（与此一致）。
            sourceUrls.addAll(writingAuthorizedUrls());
            // outline 逐节写作（evidenceBank 非空时；outline/归节失败 → 回退单遍）
            if (sectionWriting && sectionWriter != null && !deepEvidenceBank.isEmpty()) {
                sectionResult = writeSectionReport();
            }
            if (sectionResult == null) {
                // 证据库非空（per-query 结构化路径）→ 按子问题分组组装（每子问题 ≥1 +
                // 来源多样优先，替代旧到达序截断丢尾部）；空库/旧整层路径 → 回退 buildContext
                context = buildFallbackContext();
            }
        } else {
            // flat：SUMMARIZING 已按预算整理（正文优先，摘要兜底）
            context = summarizedContext;
            sourceUrls.addAll(new LinkedHashSet<>(sources.stream().map(SearchResult::url).toList()));
        }

        if (sectionResult != null) {
            report = sectionResult.report();
        } else {
            report = reportWriter.write(query, context, sourceUrls);
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("stage", "WRITING");
        payload.put("reportChars", report.length());
        payload.put("sources", sourceUrls.size());
        if (sectionResult != null) {
            payload.put("writingMode", "section");
            payload.put("sections", sectionResult.sectionCount());
            payload.put("sectionUnauthorized", sectionResult.unauthorizedTotal());
            payload.put("sectionRetried", sectionResult.retriedSections());
            payload.put("fallbackNotes", sectionResult.fallbackNotes());
        } else {
            payload.put("writingMode", "single");
            payload.put("contextChars", context == null ? 0 : context.length());
        }

        // 幻觉引用核验——正文引用的 URL 必须 ∈ 授权来源（报告级全局闸）
        CitationVerifier verifier = new CitationVerifier(sourceUrls);
        List<String> cited = verifier.citedUrls(report);
        List<String> verified = verifier.verify(report);
        payload.put("citedUrls", cited.size());
        payload.put("verifiedCitations", verified.size());
        payload.put("unauthorizedCitations", cited.size() - verified.size());
        logChainFunnel(payload);
        return new StageResult(TaskStage.WRITING, payload.toString(), llmClient.lastCallCostUsd());
    }

    /**
     * 链路漏斗：一题两行，回答"报告缺的东西断在链路的哪一环"。
     *
     * <p>来源行回答"<b>检索到了但没抓</b>"；事实行回答"<b>抓到了但没用上</b>"。
     * 每段数字都有独立采集点（检索=collectedUrls、取名/抓成=图内 chainStats、note=evidenceBank、
     * 引用=报告全文），故可逐段对账；过程细节仍看 [batch2]/[diag] 日志，两者不重复。
     *
     * <p><b>未读来源</b> = note 的 sourceUrl 集（canonical）− 抓取成功页集（canonical）。
     * 大于 0 说明有 note 把内容归因到了<b>从未成功抓取</b>的页面——extract 阶段的授权校验
     * 只要求 URL ∈ 检索集（collectedUrls）、不要求抓过，故该缺口真实存在（正确性问题，非效率问题）。
     */
    private void logChainFunnel(ObjectNode payload) {
        if (report == null || report.isBlank()) {
            return;
        }
        CitationVerifier.CitationStats cs = CitationVerifier.citationStats(report);
        if (deepResearch) {
            Set<String> noteSources = canonicalNoteSources(deepEvidenceBank);
            Set<String> unread = unreadNoteSources(noteSources, canonicalSet(deepFetchedUrls));
            // "未回" = 取名 − 爬虫返回页数。可从两个已有计数推出，故不单独存 state 键
            // （独立存一份只会新增"两数不一致"的故障面）。
            int notReturned = Math.max(0, deepChainPicked - deepChainReturned);
            LOG.info("[chain] task={} | 来源：检索 {}（镜像去重后 {}）→ 取名 {} → 抓成 {}(有效 {}, 未回 {})"
                            + " | 事实：note {}（来源 {}，其中未读 {}）→ 引用 {} 源/{} 次",
                    logTaskId, deepSourceUrls.size(), distinctMirrorKeys(deepSourceUrls),
                    deepChainPicked, deepFetchedUrls.size(), deepChainValidPages, notReturned,
                    deepEvidenceBank.size(), noteSources.size(), unread.size(),
                    cs.distinctCanonical(), cs.total());
            if (payload != null) {
                payload.put("chainCollected", deepSourceUrls.size());
                payload.put("chainFetched", deepFetchedUrls.size());
                payload.put("chainNotes", deepEvidenceBank.size());
                payload.put("chainNoteSources", noteSources.size());
                payload.put("chainUnreadSources", unread.size());
                payload.put("chainCitations", cs.total());
                payload.put("chainCitationSources", cs.distinctCanonical());
            }
        } else {
            LOG.info("[chain] task={} | 来源：检索 {} → 抓成 {} | 事实：上下文 {} 字符 → 引用 {} 源/{} 次",
                    logTaskId, sources.size(), scrapedPages.size(),
                    summarizedContext == null ? 0 : summarizedContext.length(),
                    cs.distinctCanonical(), cs.total());
        }
    }

    /**
     * 未读来源 = note 来源集 − 抓取成功集（两侧均为 canonical）。
     * 非空 ⇒ 有 note 把内容归因到<b>从未成功抓取</b>的页面（extract 阶段只校验 URL ∈ 检索集，
     * 不要求抓过）——这是<b>正确性</b>信号，不是效率信号。
     */
    static Set<String> unreadNoteSources(Set<String> noteSources, Set<String> fetchedCanonical) {
        Set<String> unread = new TreeSet<>();
        if (noteSources != null) {
            unread.addAll(noteSources);
        }
        if (fetchedCanonical != null) {
            unread.removeAll(fetchedCanonical);
        }
        return unread;
    }

    /** 检索集的镜像去重计数（同一篇文章的多个入口算一个）。 */
    private static int distinctMirrorKeys(List<String> urls) {
        Set<String> keys = new LinkedHashSet<>();
        for (String u : urls) {
            keys.add(SourceMirror.mirrorKey(u));
        }
        return keys.size();
    }

    /** evidenceBank（EvidenceNote JSON 串）→ canonical sourceUrl 集合。 */
    private Set<String> canonicalNoteSources(List<String> bank) {
        Set<String> out = new LinkedHashSet<>();
        for (String json : bank) {
            try {
                String c = CitationVerifier.canonicalize(
                        mapper.readTree(json).path("sourceUrl").asText(""));
                if (c != null) {
                    out.add(c);
                }
            } catch (Exception ignored) {
                // 单条 note 解析失败不影响观测（坏数据不该让链路日志整条消失）
            }
        }
        return out;
    }

    /** URL 列表 → canonical 集合（合并尾斜杠/跟踪参数变体，避免"已抓却判未读"）。 */
    private static Set<String> canonicalSet(List<String> urls) {
        Set<String> out = new LinkedHashSet<>();
        for (String u : urls) {
            String c = CitationVerifier.canonicalize(u);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 逐节写作主流程：解码证据（过滤空锚 note——无授权引用资格）→ outline →
     * 覆盖校验 → 机械归节 → 逐节写作（节级闸门）→ 未归组兜底节 → takeaways → 机械合并。
     * 任何一步失败返回 null（调用方回退单遍 writer，不中断任务）。
     */
    private SectionWriter.WriteResult writeSectionReport() {
        try {
            List<EvidenceNote> notes = decodeNotes();
            if (notes.isEmpty()) {
                return null;
            }
            List<String> subQueries = subQueriesOf(notes);
            if (subQueries.isEmpty()) {
                return null;
            }
            SectionWriter.EvidenceIndexStats idxStats =
                    // 把证据目录注入 outline（LLM 据此直引 evidenceIdx）
                    SectionWriter.buildEvidenceIndexWithStats(notes, evidenceIndexMaxChars);
            LOG.info("evidence index: {} items → {} included ({} omitted), {} chars",
                    idxStats.total(), idxStats.included(), idxStats.omitted(), idxStats.chars());
            SectionWriter.OutlineResult outline = sectionWriter.writeOutline(
                    query, deepResearchState, subQueries, idxStats.text());
            if (outline == null || outline.sections().isEmpty()) {
                return null;
            }
            // 覆盖校验：结果当前仅作观测，未被消费（证据零丢失由兜底节保证）。
            // 保留调用以维持行为不变。
            sectionWriter.uncoveredSubQueries(subQueries, outline.sections());

            SectionGroups groups = groupBySection(notes, outline.sections());
            SectionWriteOutcome written = writeSections(groups.sections(), groups.bySection());
            String takeaways = writeTakeaways(
                    buildSectionPreview(groups.sections(), written.markdowns()));
            String merged = sectionWriter.merge(outline.title(), written.markdowns(), takeaways);
            return new SectionWriter.WriteResult(merged, groups.sections().size(),
                    written.unauthorizedTotal(), written.retried(), groups.fallbackNotes().size());
        } catch (Exception e) {
            return null; // 逐节路径任何异常 → 回退单遍（不中断任务）
        }
    }

    /** 解码证据库，过滤空锚 note（无授权引用资格）；坏 JSON 元素跳过（原内联段逐字搬入）。 */
    private List<EvidenceNote> decodeNotes() {
        List<EvidenceNote> notes = new ArrayList<>();
        for (String json : deepEvidenceBank) {
            try {
                EvidenceNote n = EvidenceNote.fromJson(json);
                if (n.sourceUrl() != null && !n.sourceUrl().isBlank()) {
                    notes.add(n);
                }
            } catch (Exception ignored) {
                // 坏元素跳过
            }
        }
        return notes;
    }

    /** 证据中出现过的子查询文本（去重保序；原内联段逐字搬入）。 */
    private static List<String> subQueriesOf(List<EvidenceNote> notes) {
        LinkedHashSet<String> querySet = new LinkedHashSet<>();
        for (EvidenceNote n : notes) {
            if (n.queryText() != null && !n.queryText().isBlank()) {
                querySet.add(n.queryText());
            }
        }
        return new ArrayList<>(querySet);
    }

    /** 机械归节结果。{@code sections} 可能已追加兜底节，故一并返回。 */
    private record SectionGroups(List<SectionWriter.Section> sections,
                                 List<List<EvidenceNote>> bySection,
                                 List<EvidenceNote> fallbackNotes) {
    }

    /** 按归节模式把证据分到各节，未归组者进兜底节（原内联段逐字搬入）。 */
    private SectionGroups groupBySection(List<EvidenceNote> notes,
                                         List<SectionWriter.Section> inputSections) {
        List<SectionWriter.Section> sections = inputSections;
        List<List<EvidenceNote>> bySection = new ArrayList<>();
        List<EvidenceNote> fallbackNotes = new ArrayList<>();
        if (assignByCitation) {
            // 直引归节模式：直引优先（多对多；未引者全部入兜底节，语义干净）
            SectionWriter.CitationStats cs = SectionWriter.groupByCitationWithStats(
                    notes, sections);
            List<List<Integer>> grouped = cs.grouped();
            if (cs.emptySections() > 0) {
                // 唯一的占位前兆：该节无直引证据 → 将走占位。全量评测据此判断是否需加保险。
                LOG.warn("citation: {} section(s) got NO cited evidence → will use "
                        + "placeholder (fallback group holds {} notes)",
                        cs.emptySections(), cs.fallbackNotes());
            }
            for (int s = 0; s < sections.size(); s++) {
                List<EvidenceNote> bucket = new ArrayList<>();
                for (Integer idx : grouped.get(s)) {
                    bucket.add(notes.get(idx));
                }
                bySection.add(bucket);
            }
            for (Integer idx : grouped.get(sections.size())) {
                fallbackNotes.add(notes.get(idx));
            }
        } else {
            // 旧模式（默认）：文本匹配单归属
            int[] assigned = sectionWriter.assignNotes(notes, sections);
            for (int i = 0; i < sections.size(); i++) {
                bySection.add(new ArrayList<>());
            }
            for (int i = 0; i < notes.size(); i++) {
                if (assigned[i] >= 0) {
                    bySection.get(assigned[i]).add(notes.get(i));
                } else {
                    fallbackNotes.add(notes.get(i));
                }
            }
        }
        // 兜底节（未归组证据零丢失；覆盖校验的未覆盖子查询对应证据大概率在此）
        if (!fallbackNotes.isEmpty()) {
            sections = new ArrayList<>(sections);
            bySection = new ArrayList<>(bySection);
            sections.add(new SectionWriter.Section("补充证据与未覆盖方面",
                    "涵盖未能归入主节但与研究问题相关的证据", List.of()));
            bySection.add(fallbackNotes);
        }
        return new SectionGroups(sections, bySection, fallbackNotes);
    }

    /** 逐节写作的汇总结果。 */
    private record SectionWriteOutcome(List<String> markdowns, int unauthorizedTotal, int retried) {
    }

    /** 逐节写作（节级引用闸门 + 可选重写），并上报逐节活动（原内联段逐字搬入）。 */
    private SectionWriteOutcome writeSections(List<SectionWriter.Section> sections,
                                              List<List<EvidenceNote>> bySection) {
        List<String> sectionMarkdowns = new ArrayList<>();
                StringBuilder priorSections = new StringBuilder();   // 已写节注入（抑制节间重复）
        int unauthorizedTotal = 0;
        int retried = 0;
        for (int i = 0; i < sections.size(); i++) {
            List<EvidenceNote> secNotes = bySection.get(i);
            LinkedHashSet<String> secUrls = new LinkedHashSet<>();
            for (EvidenceNote n : secNotes) {
                secUrls.add(n.sourceUrl());
            }
            SectionWriter.SectionOutcome so = sectionWriter.writeSection(
                    sections.get(i), secNotes, new ArrayList<>(secUrls), priorSections.toString());
            sectionMarkdowns.add(so.markdown());
            priorSections.append(so.markdown()).append("\n\n");
            unauthorizedTotal += so.unauthorized();
            if (so.retried()) {
                retried++;
            }
            // 逐节写作活动（节标题截断为 label；仅节元数据入 detail）
            String title = sections.get(i).title();
            String label = title == null ? "" : title.trim();
            if (label.length() > ACTIVITY_SECTION_LABEL_MAX_CHARS) {
                label = label.substring(0, ACTIVITY_SECTION_LABEL_MAX_CHARS) + "…";
            }
            Map<String, Object> secDetail = new LinkedHashMap<>();
            secDetail.put("index", i);
            secDetail.put("chars", so.markdown() == null ? 0 : so.markdown().length());
            secDetail.put("unauthorized", so.unauthorized());
            secDetail.put("retried", so.retried());
            activity(TaskStage.WRITING, "section", label, secDetail);
        }
        return new SectionWriteOutcome(sectionMarkdowns, unauthorizedTotal, retried);
    }

    /** 节正文前段摘要，作为 Key Takeaways 的输入（原内联段逐字搬入）。 */
    private static String buildSectionPreview(List<SectionWriter.Section> sections,
                                              List<String> sectionMarkdowns) {
        StringBuilder sectionPreview = new StringBuilder();
        for (int i = 0; i < sections.size(); i++) {
            SectionWriter.Section s = sections.get(i);
            sectionPreview.append("- ").append(s.title());
            if (s.goal() != null && !s.goal().isBlank()) {
                sectionPreview.append("：").append(s.goal());
            }
            sectionPreview.append("\n");
            String md = sectionMarkdowns.get(i);
            if (md != null) {
                sectionPreview.append(truncateForPreview(md)).append("\n");
            }
        }
        return sectionPreview.toString();
    }

    private String writeTakeaways(String sectionsPreview) {
        try {
            String system = DeepResearchPrompts.get("report-takeaways.system");
            String user = DeepResearchPrompts.get("report-takeaways.user",
                    Map.of("language", language, "query", query, "sections", sectionsPreview));
            return llmClient.chat(system, user).trim();
        } catch (Exception e) {
            return ""; // 无 takeaways 不失败
        }
    }

    private static String truncateForPreview(String md) {
        if (md == null) {
            return "";
        }
        String flat = md.replaceAll("#{1,6}\\s*", "").trim();
        return flat.length() <= WRITING.sectionPreviewMaxChars()
                ? flat : flat.substring(0, WRITING.sectionPreviewMaxChars()) + "…";
    }

    /** 证据条目 JSON 里的来源 URL；缺失 / 空白 / 坏 JSON → null。 */
    private static String sourceUrlOf(String json) {
        try {
            var n = EvidenceNote.fromJson(json);
            String url = n.sourceUrl();
            return url == null || url.isBlank() ? null : url;
        } catch (Exception ignored) {
            // 坏元素跳过
            return null;
        }
    }

    /** 写作授权 URL 集 = 实际有证据的锚（见 writePayload 注释）。 */
    private List<String> writingAuthorizedUrls() {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (deepEvidenceBank != null) {
            for (String json : deepEvidenceBank) {
                String url = sourceUrlOf(json);
                if (url != null) {
                    urls.add(url);
                }
            }
        }
        if (urls.isEmpty() && deepLearnings != null) {
            // bank 空（旧整层路径）：learnings 渲染带 [source: url]（提炼层已授权过滤）
            for (String l : deepLearnings) {
                if (l == null) {
                    continue;
                }
                int i = l.indexOf("[source:");
                if (i < 0) {
                    continue;
                }
                String tail = l.substring(i + "[source:".length()).trim();
                int end = tail.indexOf(']');
                String u = (end < 0 ? tail : tail.substring(0, end)).trim();
                if (!u.isBlank()) {
                    urls.add(u);
                }
            }
        }
        return new ArrayList<>(urls);
    }

    private StageResult notePayload(TaskStage stage, String note) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("stage", stage.name());
        payload.put("note", note);
        return new StageResult(stage, payload.toString(), costPerStage);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 日志用任务号（原嵌套三元抽为具名方法）：空 → "-"，超 8 字符截断。 */
    private static String shortTaskId(String tid) {
        if (tid.isEmpty()) {
            return "-";
        }
        return tid.length() > LOG_TASK_ID_CHARS ? tid.substring(0, LOG_TASK_ID_CHARS) : tid;
    }

    /** 无可抓取原因（原嵌套三元抽为具名方法）：未配置 > 开关关闭 > 无来源。 */
    private String noScrapeReason() {
        if (scraperClient == null) {
            return "scraper not configured";
        }
        if (!fetchFullPage) {
            return "fetchFullPage disabled";
        }
        return "no sources to scrape";
    }

    /** 单遍回退上下文（原嵌套三元抽为具名方法）：空库走 learnings，否则按预算组装证据库。 */
    private String buildFallbackContext() {
        if (deepEvidenceBank == null || deepEvidenceBank.isEmpty()) {
            return contextManager.buildContext(deepLearnings);
        }
        int maxChars = contextMaxChars > 0 ? contextMaxChars : WRITING.contextMaxChars();
        return contextManager.buildEvidenceContext(deepEvidenceBank, maxChars);
    }
}
