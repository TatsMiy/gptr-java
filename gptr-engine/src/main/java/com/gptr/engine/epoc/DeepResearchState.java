package com.gptr.engine.epoc;


import java.util.function.BiFunction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.state.AgentState;

/**
 * deep research 图状态（LangGraph4j {@link AgentState} 子类）。
 *
 * <p>与底座任务状态分离——这是"图内状态"（查询 / 素材 / learnings）；任务生命周期状态在底座。
 *
 * <h2>键契约（21 键）</h2>
 * <p>底层存储恒为 {@code Map<String,Object>}（框架约束），但<b>所有读写都必须经由本类的
 * {@code K_*} 常量与 getter</b> —— 节点里禁止出现裸字符串键：拼错不会报错，本项目已因此产生过
 * "写了却无人消费"的键。每条格式：{@code 键名 : 类型 : 默认值 —— 语义}。
 *
 * <h3>1. 任务输入（图启动时注入，之后只读）</h3>
 * <ul>
 *   <li>{@code query} : String : {@code ""} —— 校准后的研究问题</li>
 *   <li>{@code breadth} : int : {@code 3} —— 首层子查询数</li>
 *   <li>{@code depth} : int : {@code 2} —— 最大递归层数</li>
 * </ul>
 *
 * <h3>2. 迭代位置（守卫路由读）</h3>
 * <ul>
 *   <li>{@code currentDepth} : int : {@code 0} —— 已完成的层数</li>
 *   <li>{@code roundLearnings} : int : {@code -1} —— 本轮提炼出的 learnings 数；
 *       <b>0 → 提前 END（守卫 G2）</b>；{@code -1} = 尚未设置</li>
 * </ul>
 *
 * <h3>3. 检索与素材（每轮迭代覆写）</h3>
 * <ul>
 *   <li>{@code queries} : List&lt;String&gt; : 空 —— 本层实际执行的子查询</li>
 *   <li>{@code searchResults} : String : {@code ""} —— 检索结果全文拼接（抓取与提炼两个节点的输入）</li>
 *   <li>{@code queryItems} : List&lt;List&lt;String&gt;&gt; : 空 —— per-query 条目组（与 queries 对齐序；
 *       抓取到的全文追加进引用该 URL 的组）</li>
 *   <li>{@code collectedUrls} : List&lt;String&gt; : 空 —— 图内真实检索到的 URL（报告授权来源，
 *       替代 LLM 自报 sourceUrl）</li>
 *   <li>{@code visitedUrls} : List&lt;String&gt; : 空 —— 已抓取 URL（跨层去重）</li>
 *   <li>{@code dimensions} : List&lt;String&gt; : 空 —— 维度清单（{@code coverMode=dimensions} 时产出）。
 *       ⚠️ <b>生产代码尚无消费方</b>（仅测试断言其存在）；"接入维度缺口判断"已登记为功能项</li>
 * </ul>
 *
 * <h3>4. 提炼产物（每轮迭代覆写）</h3>
 * <ul>
 *   <li>{@code learnings} : List&lt;String&gt; : 空 —— 累计 learnings</li>
 *   <li>{@code followUpQuestions} : List&lt;String&gt; : 空 —— 下一层追问</li>
 *   <li>{@code evidenceBank} : List&lt;String&gt; : 空 —— 证据条目（{@link EvidenceNote} 的 JSON 单对象串）。
 *       用 String 承载是 <b>checkpoint 约束</b>：PostgresCheckpointSaver 整体 Jackson 化，
 *       对象列表会破坏恢复</li>
 *   <li>{@code researchState} : String : {@code ""} —— 层间计划反思产出的中央研究状态；
 *       {@code "(none)"} = 未生成（坏输出不失败）</li>
 *   <li>{@code planGaps} : List&lt;String&gt; : 空 —— 计划缺口清单</li>
 * </ul>
 *
 * <h3>5. 观测统计（跨轮累计，<b>不参与业务判断</b>）</h3>
 * <ul>
 *   <li>{@code distillStats} : Map&lt;String,Object&gt; : 空 —— 蒸馏页数 / 回退 / 保留句</li>
 *   <li>{@code chainStats} : Map&lt;String,Object&gt; : 空 —— 链路漏斗计数（picked / returned / validPages）</li>
 *   <li>{@code fetchedUrls} : List&lt;String&gt; : 空 —— 抓取成功页 URL（漏斗"未读来源"差集的基准）</li>
 *   <li>{@code hitSources} : List&lt;String&gt; : 空 —— 各子查询实际命中的检索源名（观测 detail）</li>
 *   <li>{@code clarifyApplied} : boolean : {@code false} —— 澄清前奏是否生效</li>
 * </ul>
 *
 * <p><b>框架约束（改本类前必读）</b>：
 * <ol>
 *   <li>必须继承 {@code AgentState}，存储恒为 {@code Map&lt;String,Object&gt;}；</li>
 *   <li>节点更新只能返回 {@code Map}，框架<b>按键独立 merge</b> ⇒ <b>不可把状态包成 record</b>
 *       （那会让单键更新退化为整体覆盖）；</li>
 *   <li>值必须 JSON 友好（checkpoint 整体 Jackson 序列化）；</li>
 *   <li>{@code value(k, null)} <b>会 NPE</b>（框架实测）⇒ 本类每个 getter 的默认值都不得为 null。</li>
 * </ol>
 */
public class DeepResearchState extends AgentState {

    // ==================== 键名常量 ====================
    // 1. 任务输入
    static final String K_QUERY = "query";
    static final String K_BREADTH = "breadth";
    static final String K_DEPTH = "depth";
    // 2. 迭代位置
    static final String K_CURRENT_DEPTH = "currentDepth";
    static final String K_ROUND_LEARNINGS = "roundLearnings";
    // 3. 检索与素材
    static final String K_QUERIES = "queries";
    static final String K_SEARCH_RESULTS = "searchResults";
    static final String K_QUERY_ITEMS = "queryItems";
    static final String K_COLLECTED_URLS = "collectedUrls";
    static final String K_VISITED_URLS = "visitedUrls";
    static final String K_DIMENSIONS = "dimensions";
    // 4. 提炼产物
    static final String K_LEARNINGS = "learnings";
    static final String K_FOLLOW_UP_QUESTIONS = "followUpQuestions";
    static final String K_EVIDENCE_BANK = "evidenceBank";
    static final String K_RESEARCH_STATE = "researchState";
    static final String K_PLAN_GAPS = "planGaps";
    // 5. 观测统计
    static final String K_DISTILL_STATS = "distillStats";
    static final String K_CHAIN_STATS = "chainStats";
    static final String K_FETCHED_URLS = "fetchedUrls";
    static final String K_HIT_SOURCES = "hitSources";
    static final String K_CLARIFY_APPLIED = "clarifyApplied";

    public DeepResearchState(Map<String, Object> initData) {
        super(initData);
    }

    // ==================== 1. 任务输入 ====================

    public String query() {
        return value(K_QUERY, "");
    }

    public int breadth() {
        return value(K_BREADTH, 3);
    }

    public int depth() {
        return value(K_DEPTH, 2);
    }

    // ==================== 2. 迭代位置 ====================

    public int currentDepth() {
        return value(K_CURRENT_DEPTH, 0);
    }

    /** 本轮 extract 产出的 learnings 数（守卫路由读；0 → 提前 END）；-1 = 尚未设置。 */
    public int roundLearnings() {
        return value(K_ROUND_LEARNINGS, -1);
    }

    // ==================== 3. 检索与素材 ====================

    public List<String> queries() {
        return value(K_QUERIES, List.of());
    }

    /** 检索结果全文拼接（抓取与提炼两个节点的输入）。 */
    public String searchResults() {
        return value(K_SEARCH_RESULTS, "");
    }

    /** per-query 条目组（与 {@link #queries()} 对齐序；组内一条 = 一个来源，抓取全文追加进引用该 URL 的组）。
     *  空 = 旧整层路径未填充。 */
    public List<List<String>> queryItems() {
        return value(K_QUERY_ITEMS, List.of());
    }

    /** 图内真实检索到的 URL（search 结果累积；作报告授权来源，替代 LLM 自报 sourceUrl）。 */
    public List<String> collectedUrls() {
        return value(K_COLLECTED_URLS, List.of());
    }

    /** 已抓取 URL（跨层去重）。 */
    public List<String> visitedUrls() {
        return value(K_VISITED_URLS, List.of());
    }

    /** 维度清单（{@code coverMode=dimensions} 时产出）。
     *  ⚠️ <b>生产代码尚无消费方</b> —— 仅测试断言其存在；接上"维度缺口判断"已登记为功能项。 */
    public List<String> dimensions() {
        return value(K_DIMENSIONS, List.of());
    }

    // ==================== 4. 提炼产物 ====================

    public List<String> learnings() {
        return value(K_LEARNINGS, List.of());
    }

    public List<String> followUpQuestions() {
        return value(K_FOLLOW_UP_QUESTIONS, List.of());
    }

    /** 证据库（每条 = {@link EvidenceNote} 的 JSON 单对象字符串；String 编码承载是 checkpoint 约束）。
     *  空 = 旧整层路径未启用（perQueryExtract=false 不产出结构化 note）。 */
    public List<String> evidenceBank() {
        return value(K_EVIDENCE_BANK, List.of());
    }

    /** 层间计划反思产出的中央研究状态（已覆盖 vs 缺口；"(none)"=未生成）。 */
    public String researchState() {
        return value(K_RESEARCH_STATE, "");
    }

    /** 计划缺口清单（反思节点产出，追问节点消费）。 */
    public List<String> planGaps() {
        return value(K_PLAN_GAPS, List.of());
    }

    // ==================== 5. 观测统计（不参与业务判断）====================

    /** 蒸馏累计统计（页数 / 回退 / 保留句）。 */
    public Map<String, Object> distillStats() {
        return value(K_DISTILL_STATS, Map.of());
    }

    /** 链路漏斗累计计数（picked / returned / validPages）。 */
    public Map<String, Object> chainStats() {
        return value(K_CHAIN_STATS, Map.of());
    }

    /** 抓取成功页 URL（跨层累计）——链路漏斗"未读来源"差集的基准。 */
    public List<String> fetchedUrls() {
        return value(K_FETCHED_URLS, List.of());
    }

    /** 各子查询实际命中的检索源名（观测 detail；每次成功响应 sourceUsed 去重）。 */
    public List<String> hitSources() {
        return value(K_HIT_SOURCES, List.of());
    }

    /** 澄清前奏是否生效（无问题 / LLM 失败 → false）。 */
    public boolean clarifyApplied() {
        return value(K_CLARIFY_APPLIED, false);
    }

    // ==================== 统计工具（被节点与引擎共用）====================

    /** 读累计统计键并返回<b>可写副本</b>（跨轮累计：蒸馏页数 / 回退 / 保留句）。
     *
     *  <p>返回的是新 Map，<b>不修改本状态</b>——调用方改完后放进节点的 updates 才会生效
     *  （框架按键 merge 的语义要求如此）。
     *
     *  <p>{@code checkpoint} 恢复值可能是 {@code Long}/{@code Integer}，故一律走 {@link Number}
     *  转换：直接 {@code (int)} 拆箱会 ClassCastException（实测翻车）。
     *
     *  <p>注意 {@code value(key, default)} 的 default 不可为 null（框架 Optional NPE）。 */
    Map<String, Object> accumulateStats(String key) {
        Object prev = value(key, Map.of());
        Map<String, Object> m = new LinkedHashMap<>();
        if (prev instanceof Map<?, ?> pm) {
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                if (e.getValue() instanceof Number num) {
                    m.put(String.valueOf(e.getKey()), num);
                }
            }
        }
        return m;
    }

    /** 链路漏斗单键计数（picked / returned / validPages；缺键记 0）。
     *  <p><b>public</b>：引擎（{@code ResearchEngineImpl}，不同包）读漏斗前三个计数要用。 */
    public int chainStat(String key) {
        Object prev = chainStats();
        if (prev instanceof Map<?, ?> pm) {
            Object v = pm.get(key);
            if (v instanceof Number num) {
                return num.intValue();
            }
        }
        return 0;
    }

    /** {@code Map.merge} 用的整数累加器（checkpoint 恢复值可能是 Long/Integer，走 Number 转换）。 */
    static BiFunction<Object, Object, Object> addInt() {
        return (a, b) -> ((Number) a).intValue() + ((Number) b).intValue();
    }
}
