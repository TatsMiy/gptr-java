package com.gptr.engine.epoc;


import com.gptr.common.engine.ActivitySink;
import com.gptr.common.task.TaskStage;
import com.gptr.engine.EffectiveBudgets;
import com.gptr.engine.budget.Budgets;
import com.gptr.engine.budget.ExtractionBudget;
import com.gptr.integration.client.LlmClient;
import com.gptr.integration.client.ScraperClient;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.DoubleConsumer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * deep research 递归图（LangGraph4j StateGraph）。
 *
 * <p>E3 结构（learnings 驱动的递归 + 守卫 + visited 去重 + 层内并行）：
 * <pre>
 * START → [research_plan（澄清前奏，可配）]
 *       → generate_queries（首层：校准后 query → breadth 个子查询）
 *       → search（虚拟线程并行；产出 per-query 条目组 queryItems）
 *       → [scrape（visited 过滤；全文追加进引用该 URL 的条目组）]
 *       → [curate_sources（来源质量闸，可配；per-query 排序精选，坏输出回退）]
 *       → extract_learnings（I-7 per-query 独立提炼，breadth 次调用；或旧整层一次）
 *       → route：
 *            ├─ 本轮 learnings 为 0（守卫 G2）→ END
 *            ├─ currentDepth ≥ depth → END
 *            └─ 否则 followUpDriven ? follow_up_queries（learnings 驱动，
 *                breadth×decay 衰减，G1）→ search（循环）: generate_queries（旧线性加深）
 * </pre>
 *
 * <p>I 批对标（gpt-researcher skills/deep_research.py）：research_plan = py
 * generate_research_plan（初搜→LLM 生成覆盖不同方面/时间段的澄清问题→自动回答→
 * combined query 校准方向，:292-344,588-601）；per-query 提炼 = py process_query
 * 每子查询独立 conduct_research + process_research_results（:428-484，分支失败跳过，
 * 整层全失败停）；curate_sources = py SourceCurator（skills/curator.py，LLM 排序精选、
 * 坏输出回退原文防误杀；py 默认 CURATE_SOURCES=False，故默认关）。
 *
 * <p><b>本类分块地图</b>（按块名与方法名定位——行号会腐烂，故本注释刻意不写行号；
 *  物理排布与下表同序，可顺流阅读）：
 * <ol>
 *   <li><b>数据契约</b> —— {@link ResearchOptions}（一次 invoke 的全部配置）、
 *       {@link ScrapeQuota}（抓取配额模式）、{@link NodeSet}（本图的 8 个节点，可空）、
 *       {@link com.gptr.engine.budget.Budgets}（代码内预算常量，独立于配置传入）。</li>
 *   <li><b>装配</b> —— {@code buildReal} 是<b>生产唯一入口</b>（依赖经 {@link GraphDeps} 传入、
 *       预算经 {@code budgets} 参数传入；
 *       {@code build}/{@code buildCompiled} 为测试与 mock 通道）；{@code wireFull} 定义节点与边、
 *       {@code compile} 编译；{@code observed}／{@code detail} 是遥测切面。</li>
 *   <li><b>节点实现</b> —— <b>已全部外迁为独立类</b>（按图的执行序列出，便于顺流查找）：
 *       {@link ResearchPlanNode} → {@link GenerateQueriesNode} → {@link SearchNode} →
 *       {@link ScrapeNode} → {@link CurateNode} → {@link ExtractNode} →
 *       {@link PlanReflectNode} → {@link FollowUpNode}。本类只负责装配（{@code buildReal}）
 *       与连线（{@code wireFull}），不再内联任何节点逻辑。</li>
 *   <li><b>本类不持有可变静态状态</b> —— 蒸馏并发闸已改为注入式共享件（{@link DistillGate}），
 *       由装配层（{@code ResearchEngineFactory}）持有并注入。</li>
 *   <li><b>已抽出的协作类</b> —— 抓取配额调度 {@link ScrapeQuotaScheduler}；证据与文本
 *       {@link EvidenceText}（含条目归组）；prompt 预算 {@link PromptText}；
 *       状态键与统计工具 {@link DeepResearchState}；蒸馏并发闸 {@link DistillGate}。</li>
 * </ol>
 */
public class DeepResearchGraph {

    private static final Logger LOG = LoggerFactory.getLogger(DeepResearchGraph.class);

    public static final String NODE_PLAN = "research_plan";
    public static final String NODE_GENERATE = "generate_queries";
    public static final String NODE_SEARCH = "search";
    public static final String NODE_SCRAPE = "scrape";
    public static final String NODE_CURATE = "curate_sources";
    public static final String NODE_EXTRACT = "extract_learnings";
    public static final String NODE_PLAN_REFLECT = "plan_reflect";
    public static final String NODE_FOLLOWUP = "follow_up_queries";

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 真实图选项（按**消费节点**分组——每个子 record 恰好对应一个节点的入参）。
     *  <p>{@code budgets} 留在顶层：它被 scrape 与 extract **两处**消费（塞进任一组都会制造伪耦合）。
     *  <p>{@code distillGate} 不在此：它是装配层注入的进程内共享件（见 {@link GraphDeps}），
     *  属"依赖"而非"每次 invoke 可变的配置"。 */
    public record ResearchOptions(EffectiveBudgets budgets, PlanningOptions planning,
                                  ScrapeOptions scrape, ExtractOptions extract,
                                  CurateOptions curate, FollowUpOptions followUp) {

        // 此处原有 `public static ResearchOptions production()` 静态工厂，返回一份"生产默认值"。
        // 删除理由：它**零调用点**，且默认值早已与真实来源分叉（它写 curate=false、
        // extractOnDistilled=true，而 EngineConfig 现为 true/false）——它是 grep「默认值」时的
        // 第一个命中，正是"读源码读出错默认值"的直接来源。
        // 生产默认值只认 EngineConfig，不再存在第二处声明。
    }

    /** 查询规划阶段（plan 节点读 clarifyQuestions、generate 节点读 coverMode）。 */
    public record PlanningOptions(int clarifyQuestions, String coverMode) {
    }

    /** 抓取阶段。{@code maxScrapeUrls} **不再是分量**：它没有节点读点，调用方直接传 quota。 */
    public record ScrapeOptions(boolean fetchFullPage, ScrapeQuota quota,
                                boolean sourceDistill, boolean sourceRank) {
    }

        /** 提炼阶段。{@code perQueryExtract} 另被 curateOn 判据读（跨组顺带依赖）。 */
    public record ExtractOptions(boolean perQueryExtract, boolean extractOnDistilled) {
    }

    /** 来源精选阶段。{@code curateSources} 由调用方传入"curate 是否生效"的组合判据。 */
    public record CurateOptions(boolean curateSources, int curatorMaxSources) {
    }

    /** 追问 / 计划反思阶段。{@code followUpDriven} 另被路由（resolveNextNode）读。 */
    public record FollowUpOptions(boolean followUpDriven, double breadthDecay, boolean planReflect) {
    }

        /** 抓取配额。
     *  <p>{@code flat} = 现状（各查询结果按序拼接后取前 {@code flatMax} 个）；
     *  {@code linked} = 配额随查询组数联动 + 组轮转分配（每组保底名额，防前几条查询吃满）。 */
    public record ScrapeQuota(String mode, int flatMax, int perQuery, int min, int hardCap) {

        /** flat 档的每查询组名额（flat 不按组分配，此值只为保持结构一致而占位）。 */
        private static final int FLAT_QUOTA_PER_QUERY = 2;

        /** flat 档 {@code hardCap} 的下界：与 {@code maxUrls} 取大，避免小 maxUrls 压到 0。 */
        private static final int FLAT_QUOTA_HARD_CAP_FLOOR = 16;

        /** 现状档：固定取前 {@code maxUrls} 个。 */
        public static ScrapeQuota flat(int maxUrls) {
            return new ScrapeQuota("flat", Math.max(0, maxUrls), FLAT_QUOTA_PER_QUERY,
                    Math.max(0, maxUrls), Math.max(FLAT_QUOTA_HARD_CAP_FLOOR, maxUrls));
        }

        /** 联动档：clamp(perQuery × 组数, min, hardCap)。 */
        public static ScrapeQuota linked(int perQuery, int min, int hardCap) {
            return new ScrapeQuota("linked", min, perQuery, min, hardCap);
        }

        public boolean isLinked() {
            return "linked".equalsIgnoreCase(mode);
        }

        /** 该层配额（组数 ≤0 时取 flatMax 语义兜底）。 */
        public int quotaFor(int groups) {
            if (!isLinked()) {
                return flatMax;
            }
            int want = perQuery * Math.max(1, groups);
            return Math.max(min, Math.min(hardCap, want));
        }
    }

    /** 图的 8 个节点；{@code null} = 该节点按配置裁剪（语义与改造前的 null 实参逐字相同）。
     *  分量为可空，故用 builder 构造——8 个参数同类型，位置构造无编译器防护。 */
    record NodeSet(AsyncNodeAction<DeepResearchState> plan,
                   AsyncNodeAction<DeepResearchState> generate,
                   AsyncNodeAction<DeepResearchState> search,
                   AsyncNodeAction<DeepResearchState> scrape,
                   AsyncNodeAction<DeepResearchState> curate,
                   AsyncNodeAction<DeepResearchState> extract,
                   AsyncNodeAction<DeepResearchState> planReflect,
                   AsyncNodeAction<DeepResearchState> followUp) {

        static Builder builder() {
            return new Builder();
        }

        /** PoC 通道专用：generate + mockSearch + extract（其余节点恒空）。 */
        static NodeSet poc(BiFunction<String, String, String> llmChatJson) {
            return builder().generate(EpocNodes.generateQueries(llmChatJson))
                    .search(EpocNodes.mockSearch())
                    .extract(EpocNodes.extractLearnings(llmChatJson))
                    .build();
        }

        static final class Builder {
            private AsyncNodeAction<DeepResearchState> plan;
            private AsyncNodeAction<DeepResearchState> generate;
            private AsyncNodeAction<DeepResearchState> search;
            private AsyncNodeAction<DeepResearchState> scrape;
            private AsyncNodeAction<DeepResearchState> curate;
            private AsyncNodeAction<DeepResearchState> extract;
            private AsyncNodeAction<DeepResearchState> planReflect;
            private AsyncNodeAction<DeepResearchState> followUp;

            Builder plan(AsyncNodeAction<DeepResearchState> v) { this.plan = v; return this; }
            Builder generate(AsyncNodeAction<DeepResearchState> v) { this.generate = v; return this; }
            Builder search(AsyncNodeAction<DeepResearchState> v) { this.search = v; return this; }
            Builder scrape(AsyncNodeAction<DeepResearchState> v) { this.scrape = v; return this; }
            Builder curate(AsyncNodeAction<DeepResearchState> v) { this.curate = v; return this; }
            Builder extract(AsyncNodeAction<DeepResearchState> v) { this.extract = v; return this; }
            Builder planReflect(AsyncNodeAction<DeepResearchState> v) { this.planReflect = v; return this; }
            Builder followUp(AsyncNodeAction<DeepResearchState> v) { this.followUp = v; return this; }

            NodeSet build() {
                return new NodeSet(plan, generate, search, scrape, curate, extract, planReflect, followUp);
            }
        }
    }

    /** 图的**依赖**（长生命周期协作件）——与「每次 invoke 可变的配置」{@link ResearchOptions} 分离。
     *  {@code distillGate} 在此：它是装配层注入的**进程内共享件**（{@code ResearchEngineFactory}），
     *  不是任务配置——原先混在 {@code ResearchOptions} 里是分类错误。 */
    public record GraphDeps(LlmClient llm, SearchClient search, ScraperClient scraper,
                            DistillGate distillGate, BaseCheckpointSaver saver,
                            DoubleConsumer costCallback, ActivitySink sink) {
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private DeepResearchGraph() {
    }

    /** 构建图（无 checkpoint saver，内存执行）——PoC/mock 通道。 */
    public static StateGraph<DeepResearchState> build(BiFunction<String, String, String> llmChatJson)
            throws org.bsc.langgraph4j.GraphStateException {
        return wireFull(NodeSet.poc(llmChatJson), NODE_GENERATE);
    }

    /** 构建图（带 checkpoint saver）——PoC 通道。 */
    public static CompiledGraph<DeepResearchState> buildCompiled(
            BiFunction<String, String, String> llmChatJson, BaseCheckpointSaver saver) throws Exception {
        return compile(build(llmChatJson), saver);
    }

    /** I 批：构建真实图（E3 learnings 驱动 + I-8 澄清 + I-7 per-query 提炼 + I-6 curate）。
     *  OBS-1：sink 非空时各节点包 observed（node start/end 活动事件；detail 仅元数据）。
     *  <p>{@code budgets} 独立传入（不并入 {@link ResearchOptions} 的任一分量组）：它是横跨
          *  全部节点的**代码内常量载体**。 */
    public static CompiledGraph<DeepResearchState> buildReal(
            GraphDeps deps, SearchOptions searchOptions, ResearchOptions opts, Budgets budgets)
            throws Exception {
        // 澄清前奏（初搜原 query → 校准 query），clarifyQuestions=0 时跳过
        AsyncNodeAction<DeepResearchState> plan = null;
        if (opts.planning().clarifyQuestions() > 0) {
            plan = observed(ResearchPlanNode.realResearchPlan(deps.llm(), deps.search(), searchOptions,
                    opts.planning().clarifyQuestions(), deps.costCallback(), budgets.retrieval()),
                    NODE_PLAN, deps.sink(), null);
        }
        AsyncNodeAction<DeepResearchState> generate = observed(
                GenerateQueriesNode.realGenerateQueries(deps.llm(), deps.costCallback(), opts.planning().coverMode()),
                NODE_GENERATE, deps.sink(), null);
        AsyncNodeAction<DeepResearchState> searchNode = observed(
                SearchNode.realSearch(deps.search(), searchOptions, budgets.retrieval()),
                NODE_SEARCH, deps.sink(),
                Map.of("chain", deps.search().name()));
        boolean withScrape = opts.scrape().fetchFullPage() && deps.scraper() != null;
        // J3：sourceDistill → 抓取取更长正文（distillMaxChars）；M-2026：deep 路径追加每页
        // 蒸馏（选句脱水 + 程序化保真前检），llm 参与 scrape 节点（Semaphore 限流）
        Map<String, Object> scrapeDetail = deps.scraper() == null ? null : Map.of("scraper", deps.scraper().name());
        AsyncNodeAction<DeepResearchState> scrapeNode = null;
        if (withScrape) {
            scrapeNode = observed(new ScrapeNode(deps.scraper(), deps.llm(), deps.distillGate(),
                    deps.costCallback()).action(opts.scrape().quota(), opts.budgets(),
                    opts.scrape().sourceDistill(), opts.scrape().sourceRank()),
                    NODE_SCRAPE, deps.sink(), scrapeDetail);
        }
        // I-7：per-query 独立提炼（默认开，对标 py 每子查询独立研究）；false = 旧整层一次
                // joinCap 与 quote/queryText 存储上限同源于提炼域预算载体
        ExtractionBudget extraction = budgets.extraction();
        AsyncNodeAction<DeepResearchState> extract = observed(opts.extract().perQueryExtract()
                ? ExtractNode.realExtractPerQuery(deps.llm(), deps.costCallback(), extraction,
                        opts.extract().extractOnDistilled())
                : ExtractNode.realExtractRound(deps.llm(), deps.costCallback(), extraction),
                NODE_EXTRACT, deps.sink(), null);
        // curate 依赖 per-query 条目组结构（perQueryExtract=false 时禁用）
        boolean curateOn = opts.curate().curateSources() && opts.extract().perQueryExtract()
                && opts.curate().curatorMaxSources() > 0;
        AsyncNodeAction<DeepResearchState> curateNode = null;
        if (curateOn) {
            curateNode = observed(CurateNode.realCurateSources(deps.llm(),
                    opts.curate().curatorMaxSources(), deps.costCallback(), budgets.curate()),
                    NODE_CURATE, deps.sink(), null);
        }
        AsyncNodeAction<DeepResearchState> followUpNode = null;
        if (opts.followUp().followUpDriven()) {
            followUpNode = observed(FollowUpNode.realFollowUpQueries(deps.llm(), deps.costCallback(),
                    opts.followUp().breadthDecay(), budgets.reflect()), NODE_FOLLOWUP, deps.sink(), null);
        }
        // 层间计划反思（仅 learnings 驱动路径；researchState 供下轮查询生成）
        boolean planReflectOn = opts.followUp().followUpDriven() && opts.followUp().planReflect();
        AsyncNodeAction<DeepResearchState> planReflectNode = null;
        if (planReflectOn) {
            planReflectNode = observed(PlanReflectNode.realPlanReflect(deps.llm(),
                    deps.costCallback(), budgets.reflect()), NODE_PLAN_REFLECT, deps.sink(), null);
        }
        String next = resolveNextNode(planReflectOn, opts.followUp().followUpDriven());
        return compile(wireFull(NodeSet.builder()
                .plan(plan).generate(generate).search(searchNode).scrape(scrapeNode)
                .curate(curateNode).extract(extract).planReflect(planReflectNode)
                .followUp(followUpNode).build(), next), deps.saver());
    }

    /**
     * OBS-1：节点活动包装——start/end（或 fail）各 emit 一条 node 事件。
     * sink 为空 → 原样返回（零开销）；事件失败由 sink 实现侧吞掉，不进入节点链路。
     * detail 仅含元数据（计数/耗时），遵守 payload 瘦身条款。
     */
    static AsyncNodeAction<DeepResearchState> observed(
            AsyncNodeAction<DeepResearchState> node, String name,
            ActivitySink sink,
            Map<String, Object> extra) {
        if (sink == null || node == null) {
            return node;
        }
        TaskStage stage = TaskStage.RESEARCH;
        return state -> observedApply(state, node, stage, name, sink, extra);
    }

    /** {@link #observed} 的实现体（原 lambda 体逐字搬入）。 */
    private static CompletableFuture<Map<String, Object>> observedApply(
            DeepResearchState state, AsyncNodeAction<DeepResearchState> node, TaskStage stage,
            String name, ActivitySink sink, Map<String, Object> extra) {
        sink.emit(stage, "node", name, detail(state, "start", extra));
        long t0 = System.currentTimeMillis();
        try {
            return node.apply(state).whenComplete(
                    (upd, err) -> emitNodeEnd(state, stage, name, sink, extra, err, t0));
        } catch (RuntimeException e) {
            emitNodeEnd(state, stage, name, sink, extra, e, t0);
            throw e;
        }
    }

    /** 节点结束/失败遥测（原 whenComplete lambda 体逐字搬入）：err==null → end，否则 fail。 */
    private static void emitNodeEnd(DeepResearchState state, TaskStage stage, String name,
                                    ActivitySink sink, Map<String, Object> extra,
                                    Throwable err, long t0) {
        Map<String, Object> d = detail(state, err == null ? "end" : "fail", extra);
        d.put("elapsedMs", System.currentTimeMillis() - t0);
        sink.emit(stage, "node", name, d);
    }

    /** 节点状态摘要（仅计数类元数据；OBS payload 瘦身条款）。 */
    private static Map<String, Object> detail(DeepResearchState state, String phase,
                                                        Map<String, Object> extra) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("phase", phase);
        d.put("depth", state.currentDepth());
        d.put("roundLearnings", state.roundLearnings());
        d.put("learnings", state.learnings().size());
        d.put("queries", state.queries().size());
        d.put("bank", state.evidenceBank().size());
        // 实际命中源（search 节点/澄清初搜累计进状态；节点内更新的键在下一事件可见）
        Object hits = state.hitSources();
        if (hits instanceof Collection<?> c && !c.isEmpty()) {
            d.put("hitSources", new ArrayList<>(c));
        }
        // M-2026：蒸馏累计统计（scrape 节点写入 distillStats 键）
        Object ds = state.distillStats(); // 勿传 null 默认（AgentState NPE）
        if (ds instanceof Map<?, ?> m && !m.isEmpty()) {
            d.put("distillStats", new LinkedHashMap<>(m));
        }
        if (extra != null) {
            d.putAll(extra);
        }
        return d;
    }

    /**
     * 统一装配：[plan] → generate → search → [scrape] → [curate] → extract → route。
     * route：本轮 learnings==0（守卫）或达 depth → END；否则 → next
     * （plan_reflect → follow_up → search，或旧路径 follow_up/generate）。
     * plan/scrape/curate/planReflect/followUp 可空（按配置裁剪节点）。
     */
    private static StateGraph<DeepResearchState> wireFull(NodeSet nodes, String nextNode)
            throws org.bsc.langgraph4j.GraphStateException {
        StateGraph<DeepResearchState> graph = new StateGraph<>(DeepResearchState::new);
        if (nodes.plan() != null) {
            graph.addNode(NODE_PLAN, nodes.plan());
        }
        graph.addNode(NODE_GENERATE, nodes.generate());
        graph.addNode(NODE_SEARCH, nodes.search());
        if (nodes.scrape() != null) {
            graph.addNode(NODE_SCRAPE, nodes.scrape());
        }
        if (nodes.curate() != null) {
            graph.addNode(NODE_CURATE, nodes.curate());
        }
        graph.addNode(NODE_EXTRACT, nodes.extract());
        if (nodes.planReflect() != null) {
            graph.addNode(NODE_PLAN_REFLECT, nodes.planReflect());
        }
        if (nodes.followUp() != null) {
            graph.addNode(NODE_FOLLOWUP, nodes.followUp());
        }
        String afterSearch = resolveAfterSearch(nodes.scrape() != null, nodes.curate() != null);
        graph.addEdge(StateGraph.START, nodes.plan() != null ? NODE_PLAN : NODE_GENERATE);
        if (nodes.plan() != null) {
            graph.addEdge(NODE_PLAN, NODE_GENERATE);
        }
        graph.addEdge(NODE_GENERATE, NODE_SEARCH);
        graph.addEdge(NODE_SEARCH, afterSearch);
        if (nodes.scrape() != null) {
            graph.addEdge(NODE_SCRAPE, nodes.curate() != null ? NODE_CURATE : NODE_EXTRACT);
        }
        if (nodes.curate() != null) {
            graph.addEdge(NODE_CURATE, NODE_EXTRACT);
        }
        if (nodes.planReflect() != null) {
            graph.addEdge(NODE_PLAN_REFLECT, NODE_FOLLOWUP);
        }
        if (nodes.followUp() != null) {
            graph.addEdge(NODE_FOLLOWUP, NODE_SEARCH);
        }

        // 守卫路由：本轮 learnings==0 → END（G2）；深度预算内 → nextNode
        AsyncEdgeAction<DeepResearchState> route =
                state -> CompletableFuture.completedFuture(routeTarget(state));
        graph.addConditionalEdges(NODE_EXTRACT, route,
                Map.of("next", nextNode, "end", StateGraph.END));
        return graph;
    }

    /** 守卫路由判据：本轮无 learnings 或深度到顶 → {@code "end"}，否则 {@code "next"}。
     *  抽为具名方法（原为跨三行三元：条件含两个比较，三元跨行后难读）。 */
    private static String routeTarget(DeepResearchState state) {
        boolean done = state.roundLearnings() == 0 || state.currentDepth() >= state.depth();
        return done ? "end" : "next";
    }

    private static CompiledGraph<DeepResearchState> compile(
            StateGraph<DeepResearchState> graph, BaseCheckpointSaver saver) throws Exception {
        if (saver == null) {
            // 无 saver：内存执行（测试/无 DB 场景）
            return graph.compile();
        }
        CompileConfig config = CompileConfig.builder()
                .checkpointSaver(saver)
                .build();
        return graph.compile(config);
    }

    // ------------------------------------------------------------------
    // 节点实现
    // ------------------------------------------------------------------


    /** 检索后去向（原嵌套三元抽为具名方法）：计划反思 > 缺口驱动追问 > 重新生成查询。 */
    private static String resolveNextNode(boolean planReflectOn, boolean followUpDriven) {
        if (planReflectOn) {
            return NODE_PLAN_REFLECT;
        }
        if (followUpDriven) {
            return NODE_FOLLOWUP;
        }
        return NODE_GENERATE;
    }

    /** 检索后去向（原嵌套三元抽为具名方法）：抓取 > 精选 > 直接提炼。 */
    private static String resolveAfterSearch(boolean withScrape, boolean withCurate) {
        if (withScrape) {
            return NODE_SCRAPE;
        }
        if (withCurate) {
            return NODE_CURATE;
        }
        return NODE_EXTRACT;
    }
}
