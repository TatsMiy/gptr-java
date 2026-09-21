package com.gptr.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.engine.budget.Budgets;
import com.gptr.engine.config.ConfigKey;
import com.gptr.engine.config.ConfigKey.Kind;
import com.gptr.engine.config.ConfigKey.State;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 引擎级配置：从任务 config JSON 解析（E 系列 maxSubQueries / deep research 模式等）。
 *
 * <p>P0 遗留的测试旋钮 {@code mock.*}（failAtStage/stageDelayMs/costPerStage）只允许在
 * 显式开启 mock 配置的环境生效：{@code allowMock=false}（生产默认）时
 * mock 子树被完全忽略，阶段延迟/固定成本为 0——真实任务不再有假延迟、假成本，
 * 也杜绝远程触发失败/成本通胀。集成测试经 {@code gptr.engine.allow-mock-config=true} 开启。
 */
final class EngineConfig {

    private static final Logger LOG = LoggerFactory.getLogger(EngineConfig.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final int maxSubQueries;
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final boolean deepResearch;
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final int breadth;
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final int depth;
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17",
            reason = "P0 mock 测试旋钮：allowMock=false 时整棵 mock 子树被忽略（刻意设计）")
    final String failAtStage;
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17",
            reason = "P0 mock 测试旋钮：allowMock=false 时整棵 mock 子树被忽略（刻意设计）")
    final long stageDelayMs;
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17",
            reason = "P0 mock 测试旋钮：allowMock=false 时整棵 mock 子树被忽略（刻意设计）")
    final double costPerStage;
    /** 检索器名（python-crawler 客户端内引擎，如 duckduckgo/arxiv/bocha）；null=客户端默认。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final String retriever;
    /** 每查询结果数上限；null=客户端默认。 */
    @ConfigKey(kind = Kind.UNVERIFIED, owner = "gptr-dev", added = "2026-09-17",
            reason = "缺证据：测试统一传 SearchOptions.DEFAULT（maxResults 恒 null）⇒ 截断分支从未进入")
    final Integer maxResults;
    /** 抓取全文总开关。 */
    @ConfigKey(kind = Kind.OPS, owner = "gptr-dev", added = "2026-09-17")
    final boolean fetchFullPage;
    /** 每任务最多抓取 URL 数（0=不抓）。 */
    @ConfigKey(kind = Kind.OPS, owner = "gptr-dev", added = "2026-09-17")
    final int maxScrapeUrls;
    /** learnings 驱动下一层查询（false=旧线性加深兼容）。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final boolean followUpDriven;
    /** 每层查询数衰减系数（默认 0.5，1=不衰减）。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final double breadthDecay;
    /** 澄清追问前奏问题数（0=关；默认 3=对标 py 深研恒有 research-plan）。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final int clarifyQuestions;
    /** I-7：extract 按子查询独立提炼（默认 true=对标 py 每子查询独立研究；false=旧整层一次）。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final boolean perQueryExtract;
    /** 来源质量闸（**默认 false** —— 2026-09-20 翻关；原为 true：2026-09-13 三题双臂实测
     *  "跨领域噪声 11 处 → 0、兜底节 43 条 → 3 条"后翻转默认值）。
     *  <p>⚠️ 翻关依据：那次验收的**适用域未覆盖"组内条目数 > {@link #curatorMaxSources}"区间** ——
     *  当时 {@code maxResults} 为默认 5 ⇒ 组内 7 条 &lt; 10 ⇒ 两道闸（保留数 / 候选可见性）
     *  **均未触发，curate 实为空转**。而一旦组内条目数越过该闸（如 {@code maxResults=20}），
     *  排在组尾的**已抓取正文块被整批丢弃**：实测 note 62 → 34、extract {@code joinedChars}
     *  6783 → 2000、具名基准 9 → 0。
     *  ⇒ 在候选池混装问题修复前默认关。
     *  <p>开则 SUMMARIZING/RESEARCH curate 节点介入，排序精选、坏输出回退原文。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final boolean curateSources;
    /** 关口 A（实验键，**默认 false**）：取名前对候选来源**按 URL 分档**重排，
     *  使高质量来源优先占用抓取配额。**只改顺序，不改配额、不过滤**；
     *  失败/坏输出 → 完整回退检索原序（行为与该开关关闭时逐字相同）。
     *  <p>与 {@link #curateSources} 的分工：本键在 {@code scrape} **之前**、只看 URL、
     *  只调顺序；curate 在 {@code scrape} **之后**、看内容、做筛选。
     *  <p>实验期单键：验证完成后二选一——进 Profile 或物理删除。 */
    @ConfigKey(kind = Kind.EXPERIMENT, owner = "gptr-dev", added = "2026-09-17",
            expires = "2026-09-20", state = State.CLOSED_FLIPPED,
            evidence = "2026-09-17 首跑真实任务 + 六维盲判双跑一致：sourceRank=true 胜 overall/结构/引用/诚实")
    final boolean sourceRank;
    /** curate 保留来源数上限（py max_results=10）。 */
    @ConfigKey(kind = Kind.UNVERIFIED, owner = "gptr-dev", added = "2026-09-17",
            reason = "缺证据：测试零引用该键，无任何验收记录；默认值来源只是抄 py max_results=10")
    final int curatorMaxSources;
    /** P0-4：禁止来源列表（URL 前缀或域名）；空 = 不屏蔽。引擎检索/抓取层直接过滤。 */
    @ConfigKey(kind = Kind.UNVERIFIED, owner = "gptr-dev", added = "2026-09-17",
                        reason = "缺证据：解析与装饰器装配零测试；且 flat 路径绕过 BlockedSearchClient")
    final List<String> blockedUrls;
    /** J3：来源提炼开关（默认关=现有截断兜底）。开：抓取取更长正文（distillMaxChars），
     *  flat 由 LLM 提炼要点（长网页后半不再丢核心数据）；deep 的 extract 读长正文提炼。 */
    @ConfigKey(kind = Kind.UNVERIFIED, owner = "gptr-dev", added = "2026-09-17",
            reason = "2026-09-19 已补 on/off 同题对照（R2 vs R6，唯一变量=本键）：结论 tie=交换项"
                    + "（开 ⇒ 具名基准 7 vs 0、note 64 vs 32、honesty 胜；关 ⇒ 中文 71.1% vs 45.5%、成本 −20%）"
                                        + "⇒ 默认保持 false；是否翻转待定（故本键暂无法归入 STABLE）")
    final boolean sourceDistill;
    /** J3：进提炼的正文上限（字符）。默认 20000（覆盖长网页主体）。 */
    @ConfigKey(kind = Kind.UNVERIFIED, owner = "gptr-dev", added = "2026-09-17",
            reason = "缺证据：无 20000 vs 50000 对照；注入仅对 acpt_run.py 成立，文档与脚本不符")
    final int distillMaxChars;
    /** 深研素材增强（M-2026）：每页蒸馏并发上限（Semaphore，进程级语义；仅 sourceDistill=true
     *  且 deep 路径使用；默认 3——防 20k 长文并发 burst 打爆 API TPM）。 */
    @ConfigKey(kind = Kind.UNVERIFIED, owner = "gptr-dev", added = "2026-09-17",
                        reason = "缺证据：任务级该键已失效（快照打 IGNORED），应用级真实峰值从未观测")
    final int distillConcurrency;
    /** 深研素材增强（M-2026）：extract 对 [DISTILLED] 选句块是否做 LLM 二次提炼。
     *  **默认 false（Y 臂：蒸馏句 Java 直通 note——2026-09-10 三题双臂物料验收后翻转默认值）**：
     *  Y 臂 bank 342-383 条/75-89k 字符、报告 16.2-29.6k（追平 py）、成本 $0.038-0.042，
     *  全面优于 X 臂（提炼：43-55 条/16.6-20.9k、报告 7.5-8.4k、$0.041-0.057）；
     *  true = X 臂（蒸馏句仍走 extract 提炼，保留为对照/回退配置）。仅 sourceDistill=true 时生效。 */
    @ConfigKey(kind = Kind.EXPERIMENT, owner = "gptr-dev", added = "2026-09-17",
            expires = "2026-09-20", state = State.CLOSED_FLIPPED,
            evidence = "2026-09-10 三题双臂物料验收：直通臂在体量/报告长度/成本三项全面优于提炼臂"
                    + "（关键点遗漏率口径尚未对照，不阻塞结案）")
    final boolean extractOnDistilled;
    /** J3：研报语言（默认"中文"；zh/中文→中文，en/english→English，其它透传）。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final String language;
    /** 层间计划反思（默认 true：每层 extract 后生成中央研究状态 researchState，
     *  下轮查询生成读取；false = 旧路径 learnings+gaps 直驱）。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final boolean planReflect;
    /** outline 先行逐节写作（默认 true——A/B 通过后翻转默认值：盲判 depth 9:0/accuracy
     *  6:3/citation 6:3 + KAE KSR +15 点；structure 维单遍略优 6:3 已记录 p22-ab-result.md）。
     *  evidenceBank 空（旧整层路径）或 outline 失败时自动回退单遍。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final boolean sectionWriting;
    /** M-2026：回退/单遍写作上下文预算（字符）。默认值**不再硬编码**，由
     *  {@code Budgets.defaults().writing().contextMaxChars()} 供给（原为
     *  {@code ContextManager.DEFAULT_MAX_CHARS}=12000）；
     *  评测扩容场景经此键注入）。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final int contextMaxChars;
    /** 每节证据上下文预算（字符）。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final int sectionContextChars;
    /** 已写节注入的字符预算（**≤0 = 关闭注入 = 现状等价**；默认 24000）。
     *  已写节注入：把已写完的小节注入后续小节上下文，抑制节间重复。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-19")
    final int priorSectionsMaxChars;
    /** 批 2：大纲节数上限（outline prompt 与解析 cap 同源；默认 6，评测批可配 8）。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final int maxSections;
    /** 节级引用闸门发现节外引用时是否重写 1 次（默认只记录，同现 WRITING 行为）。 */
    @ConfigKey(kind = Kind.UNVERIFIED, owner = "gptr-dev", added = "2026-09-17",
            reason = "缺证据：该分支从未以 true 执行——测试全部以第三实参 false 构造 SectionWriter")
    final boolean sectionRetryOnUnauthorized;
    /** 首层查询的覆盖机制。`dimensions`（默认）=LLM 一次给出"维度→查询"
     *  完整映射，Java 机械校验每维度有查询 + 总数 ≥ breadth（缺则补一次）；
     *  `legacy`=批 2 自检补查（LLM 自证缺失维度，判定随机，保留为对照/回滚）；
     *  `off`=批 1 行为（旧 prompt 裸 queries，不生成维度、不补查）——4 格对照的基线档。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final String coverMode;
    /** 抓取配额模式。`linked`（默认，2026-09-10 4 格对照后翻转默认值）
     *  =按查询组数联动配额 + 组轮转分配（每组保底名额，防前几条查询吃满）；
     *  依据：dims + flat 实测 3/5 组零产出（60% 维度白生成），dims + linked 为 0/6；
     *  linked 在查询少时配额 = min（默认 8）不增成本。
     *  `flat` = 现状（按查询顺序取前 maxScrapeUrls），保留为回退/对照档。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final String scrapeQuotaMode;
    /** 批 4-pre：linked 模式下每查询组名额（默认 2）。 */
    @ConfigKey(kind = Kind.OPS, owner = "gptr-dev", added = "2026-09-17")
    final int scrapePerQueryQuota;
    /** 批 4-pre：linked 模式配额下界（默认 8 = 现状值，保证只增不减）。 */
    @ConfigKey(kind = Kind.OPS, owner = "gptr-dev", added = "2026-09-17")
    final int scrapeMinQuota;
    /** 批 4-pre：linked 模式配额上界（默认 16，成本护栏）。 */
    @ConfigKey(kind = Kind.OPS, owner = "gptr-dev", added = "2026-09-17")
    final int scrapeHardCap;
    /** 归节模式。true（**2026-09-17 转正为默认**）= **直引优先**（大纲写 evidenceIdx，
     *  多对多，未引者入兜底节）；false = 现有文本匹配单归属（保留为**显式回退**，不再是默认）。
     *  <p>转正依据（{@code state = CLOSED_FLIPPED}）：① 批 4 三题验收（F10 全 0、报告 +67%、
     *  引用 33/51/46、成本反降）；② 2026-09-17 默认路径实测复发 **3 次 F10（≥2 次错位型）**
     *  ⇒ 旧路径确在产生缺陷；③ **F10 前兆观测只存在于本开关为 true 的分支**
      *  （{@code ResearchEngineImpl#groupBySection}）—— 不转正则 F10 始终**不可观测**。
      *  <p>✅ 质量盲判**已于 2026-09-19 补齐**：干净对照 R2 vs R4
     *  （唯一变量 = 本键，两臂同为 bocha+蒸馏+rank）⇒ `overall` 与 `honesty_restraint` 双跑一致判
     *  `true` 更优，胜因 = 消除尾部两节整节塌陷（占位 2→0）、成本不变。
     *  ⚠️ 遗留代价：`true` 会引入同事实复述 ×3–5（未解决）。 */
    @ConfigKey(kind = Kind.EXPERIMENT, owner = "gptr-dev", added = "2026-09-17",
            expires = "2026-09-20", state = State.CLOSED_FLIPPED,
            evidence = "2026-09-13 机制验收（空节占位三题全 0、报告 +67~79%、引用近翻倍）"
                    + " + 2026-09-19 唯一变量干净对照、双跑盲判：空节 2→0、引用 ×1.84、成本不变")
    final boolean assignByCitation;
    /** 证据目录**上限**（默认 40000；≤0 → SectionWriter 默认值）。
     *  2026-09-13：12000 → 40000——实测 195 条证据 ≈21k 字符，12000 时被截断 31%，被截证据
     *  LLM 看不见 → 无法直引 → 全进兜底组（抓取改善的素材增量被上限吃掉）。语义是"内容实际
     *  长度封顶"：证据少时内容本就短，不补齐；多时才截断。 */
    @ConfigKey(kind = Kind.STABLE, owner = "gptr-dev", added = "2026-09-17")
    final int evidenceIndexMaxChars;

    EngineConfig(String config) {
        this(config, false);
    }

    /**
     * @param allowMock 是否允许 config 中的 {@code mock.*} 测试旋钮（生产必须 false）
     */
    EngineConfig(String config, boolean allowMock) {
        int maxQueries = 3;
        boolean deep = false;
        int breadthVal = 3;
        int depthVal = 2;
        String fail = null;
        long delay = 0L;      // 生产默认 0：无假延迟
        double cost = 0.0;    // 生产默认 0：无假成本（真实成本按 LLM usage 计）
        String retrieverVal = null;
        Integer maxResultsVal = null;
        boolean fetchVal = true;
        int scrapeVal = 8;
        boolean followUpVal = true;
        double decayVal = 0.5;
        int clarifyVal = 3;       // I-8：默认开（对标 py research-plan 恒执行）
        boolean perQueryVal = true; // I-7：默认开（对标 py per-query 独立提炼）
        boolean curateVal = false;  // I-6：默认关（2026-09-20 翻关；依据与适用域边界见字段 javadoc）
        boolean sourceRankVal = true;  // 关口 A：默认开（2026-09-19 转正：P1 干净对照双跑 6 维一致）
        int curatorMaxVal = 10;     // I-6：py max_results=10
        List<String> blockedVal = List.of(); // P0-4：空=不屏蔽
        boolean distillVal = false; // J3：默认关=截断兜底
        int distillMaxVal = 20000;  // J3：提炼正文上限
        int distillConcVal = 3;     // M-2026：蒸馏并发（进程级信号量）
        boolean extractDistillVal = false; // M-2026：Y 臂默认（2026-09-10 验收后翻转默认值——直通全面更优）
        String languageVal = "中文"; // J3：默认中文
        boolean planReflectVal = true; // J6：默认开（+1 调用/层）
        boolean sectionVal = true;      // P2-2：默认 true（A/B 通过后翻转默认值；可显式关）
        int sectionCharsVal = 6000;     // P2-2：每节证据预算
        int maxSectionsVal = 6;         // 批 2：大纲节数上限（prompt+解析同源）
        int priorSectionsMaxCharsVal = 24000; // 2026-09-19：已写节注入预算（≤0 = 关闭 = 现状）
        int contextCharsVal = Budgets.defaults().writing().contextMaxChars(); // 默认值由预算载体供给
        boolean sectionRetryVal = false; // P2-2：节级违规重写（默认只记录）
        String coverModeVal = "dimensions";   // 维度清单机械下界
        String quotaModeVal = "linked";       // 批 4-pre：抓取配额（2026-09-10 4 格对照后翻转默认值）
        int perQueryQuotaVal = 2;             // 批 4-pre：每查询组名额
        int minQuotaVal = 8;                  // 批 4-pre：配额下界（=现状 maxScrapeUrls）
        int hardCapVal = 16;                  // 批 4-pre：配额上界（成本护栏）
        boolean assignByCitationVal = true;   // 批 4：归节模式（2026-09-17 **转正为默认**；显式设 false 回退）
        int evidenceIndexCharsVal = 40000;    // 证据目录上限（原 12000 时 195 条证据截断 31%）
        try {
            JsonNode root = MAPPER.readTree(config == null ? "{}" : config);
            JsonNode maxNode = root.path("maxSubQueries");
            if (maxNode.isInt()) {
                maxQueries = maxNode.asInt();
            }
            JsonNode modeNode = root.path("mode");
            if (modeNode.isTextual() && "deep_research".equals(modeNode.asText())) {
                deep = true;
            }
            JsonNode breadthNode = root.path("breadth");
            if (breadthNode.isInt()) {
                breadthVal = breadthNode.asInt();
            }
            JsonNode depthNode = root.path("depth");
            if (depthNode.isInt()) {
                depthVal = depthNode.asInt();
            }
            JsonNode retrieverNode = root.path("retriever");
            if (retrieverNode.isTextual() && !retrieverNode.asText().isBlank()) {
                retrieverVal = retrieverNode.asText();
            }
            JsonNode maxResultsNode = root.path("maxResults");
            if (maxResultsNode.isInt()) {
                maxResultsVal = maxResultsNode.asInt();
            }
            JsonNode fetchNode = root.path("fetchFullPage");
            if (fetchNode.isBoolean()) {
                fetchVal = fetchNode.asBoolean();
            }
            JsonNode scrapeNode = root.path("maxScrapeUrls");
            if (scrapeNode.isInt()) {
                scrapeVal = scrapeNode.asInt();
            }
            JsonNode followUpNode = root.path("followUpDriven");
            if (followUpNode.isBoolean()) {
                followUpVal = followUpNode.asBoolean();
            }
            JsonNode decayNode = root.path("breadthDecay");
            if (decayNode.isNumber()) {
                double d = decayNode.asDouble();
                if (d > 0 && d <= 1) {
                    decayVal = d;
                }
            }
            JsonNode clarifyNode = root.path("clarifyQuestions");
            if (clarifyNode.isInt()) {
                clarifyVal = Math.max(0, clarifyNode.asInt());
            }
            JsonNode perQueryNode = root.path("perQueryExtract");
            if (perQueryNode.isBoolean()) {
                perQueryVal = perQueryNode.asBoolean();
            }
            JsonNode curateNode = root.path("curateSources");
            if (curateNode.isBoolean()) {
                curateVal = curateNode.asBoolean();
            }
            JsonNode sourceRankNode = root.path("sourceRank");
            if (sourceRankNode.isBoolean()) {
                sourceRankVal = sourceRankNode.asBoolean();
            }
            JsonNode curatorMaxNode = root.path("curatorMaxSources");
            if (curatorMaxNode.isInt() && curatorMaxNode.asInt() > 0) {
                curatorMaxVal = curatorMaxNode.asInt();
            }
            // P0-4（对标 Bench II）：禁止来源（URL 前缀或域名）——评测防泄漏用；
            // 引擎在检索/抓取层直接屏蔽，比"prompt 阻断"更彻底（产品侧同样适用：排除站点）
            JsonNode blockedNode = root.path("blockedUrls");
            if (blockedNode.isArray()) {
                List<String> list = new ArrayList<>();
                for (JsonNode b : blockedNode) {
                    if (b.isTextual() && !b.asText().isBlank()) {
                        list.add(b.asText().trim());
                    }
                }
                if (!list.isEmpty()) {
                    blockedVal = list;
                }
            }
            JsonNode distillNode = root.path("sourceDistill");
            if (distillNode.isBoolean()) {
                distillVal = distillNode.asBoolean();
            }
            JsonNode distillMaxNode = root.path("distillMaxChars");
            if (distillMaxNode.isInt() && distillMaxNode.asInt() > 1000) {
                distillMaxVal = distillMaxNode.asInt();
            }
            JsonNode distillConcNode = root.path("distillConcurrency");
            if (distillConcNode.isInt() && distillConcNode.asInt() >= 1 && distillConcNode.asInt() <= 8) {
                distillConcVal = distillConcNode.asInt();
            }
            JsonNode extractDistillNode = root.path("extractOnDistilled");
            if (extractDistillNode.isBoolean()) {
                extractDistillVal = extractDistillNode.asBoolean();
            }
            JsonNode langNode = root.path("language");
            if (langNode.isTextual() && !langNode.asText().isBlank()) {
                languageVal = normalizeLanguage(langNode.asText());
            }
            JsonNode reflectNode = root.path("planReflect");
            if (reflectNode.isBoolean()) {
                planReflectVal = reflectNode.asBoolean();
            }
            // 批 4-pre：覆盖机制档位（dimensions/legacy/off——非法值保持默认 dimensions）
            JsonNode coverNode = root.path("coverMode");
            if (coverNode.isTextual()) {
                String m = coverNode.asText().trim().toLowerCase(Locale.ROOT);
                if (m.equals("dimensions") || m.equals("legacy") || m.equals("off")) {
                    coverModeVal = m;
                }
            }
            // 批 4-pre：抓取配额档位（flat/linked——非法值保持默认 linked）
            JsonNode quotaNode = root.path("scrapeQuotaMode");
            if (quotaNode.isTextual()) {
                String m = quotaNode.asText().trim().toLowerCase(Locale.ROOT);
                if (m.equals("flat") || m.equals("linked")) {
                    quotaModeVal = m;
                }
            }
            JsonNode perQueryQuotaNode = root.path("scrapePerQueryQuota");
            if (perQueryQuotaNode.isInt() && perQueryQuotaNode.asInt() >= 1
                    && perQueryQuotaNode.asInt() <= 10) {
                perQueryQuotaVal = perQueryQuotaNode.asInt();
            }
            JsonNode minQuotaNode = root.path("scrapeMinQuota");
            if (minQuotaNode.isInt() && minQuotaNode.asInt() >= 1 && minQuotaNode.asInt() <= 40) {
                minQuotaVal = minQuotaNode.asInt();
            }
            JsonNode hardCapNode = root.path("scrapeHardCap");
            if (hardCapNode.isInt() && hardCapNode.asInt() >= 1 && hardCapNode.asInt() <= 40) {
                hardCapVal = hardCapNode.asInt();
            }
            // 批 4：直引归节开关 + 证据目录预算
            JsonNode citationNode = root.path("assignByCitation");
            if (citationNode.isBoolean()) {
                assignByCitationVal = citationNode.asBoolean();
            }
            JsonNode evIdxNode = root.path("evidenceIndexMaxChars");
            if (evIdxNode.isInt() && evIdxNode.asInt() >= 4000 && evIdxNode.asInt() <= 80000) {
                evidenceIndexCharsVal = evIdxNode.asInt();
            }
            JsonNode sectionNode = root.path("sectionWriting");
            if (sectionNode.isBoolean()) {
                sectionVal = sectionNode.asBoolean();
            }
            JsonNode sectionCharsNode = root.path("sectionContextChars");
            if (sectionCharsNode.isInt() && sectionCharsNode.asInt() > 500) {
                sectionCharsVal = sectionCharsNode.asInt();
            }
            // 2026-09-19：已写节注入预算。⚠️ 校验域是 >= 0（0 = 合法关闭档），不是 > 0
            JsonNode priorSectionsNode = root.path("priorSectionsMaxChars");
            if (priorSectionsNode.isInt() && priorSectionsNode.asInt() >= 0) {
                priorSectionsMaxCharsVal = priorSectionsNode.asInt();
            }
            JsonNode contextCharsNode = root.path("contextMaxChars");
            if (contextCharsNode.isInt() && contextCharsNode.asInt() > 1000) {
                contextCharsVal = contextCharsNode.asInt();
            }
            JsonNode maxSectionsNode = root.path("maxSections");
            if (maxSectionsNode.isInt() && maxSectionsNode.asInt() >= 2 && maxSectionsNode.asInt() <= 12) {
                maxSectionsVal = maxSectionsNode.asInt();
            }
            JsonNode sectionRetryNode = root.path("sectionRetryOnUnauthorized");
            if (sectionRetryNode.isBoolean()) {
                sectionRetryVal = sectionRetryNode.asBoolean();
            }
            // P0 mock 测试旋钮：仅 allowMock（显式测试配置）时生效（C3-S2 剥离）
            if (allowMock) {
                JsonNode mockNode = root.path("mock");
                JsonNode failNode = mockNode.path("failAtStage");
                if (failNode.isTextual()) {
                    fail = failNode.asText();
                }
                JsonNode delayNode = mockNode.path("stageDelayMs");
                if (delayNode.isIntegralNumber()) {
                    delay = delayNode.asLong();
                }
                JsonNode costNode = mockNode.path("costPerStage");
                if (costNode.isNumber()) {
                    cost = costNode.asDouble();
                }
            }
        } catch (Exception e) {
            // 【2026-09-14 修复】原为 `catch (Exception ignored)` + 空注释：一份打错的 config JSON
            // 会让**整份配置静默退回默认值**（depth / sourceDistill / distillMaxChars / curate …
            // 一起退），任务照跑、指标照算 —— 生产上表现为"我改了配置但没生效"，是最难查的一类问题。
            // 保留降级语义（不因配置解析失败而让任务失败），但必须留痕。
            LOG.warn("[config] engine config 解析失败，整份退回默认值: {}", e.toString());
        }
        this.maxSubQueries = maxQueries;
        this.deepResearch = deep;
        this.breadth = breadthVal;
        this.depth = depthVal;
        this.failAtStage = fail;
        this.stageDelayMs = delay;
        this.costPerStage = cost;
        this.retriever = retrieverVal;
        this.maxResults = maxResultsVal;
        this.fetchFullPage = fetchVal;
        this.maxScrapeUrls = scrapeVal;
        this.followUpDriven = followUpVal;
        this.breadthDecay = decayVal;
        this.clarifyQuestions = clarifyVal;
        this.perQueryExtract = perQueryVal;
        this.curateSources = curateVal;
        this.sourceRank = sourceRankVal;
        this.curatorMaxSources = curatorMaxVal;
        this.blockedUrls = blockedVal;
        this.sourceDistill = distillVal;
        this.distillMaxChars = distillMaxVal;
        this.distillConcurrency = distillConcVal;
        this.extractOnDistilled = extractDistillVal;
        this.language = languageVal;
        this.planReflect = planReflectVal;
        this.sectionWriting = sectionVal;
        this.sectionContextChars = sectionCharsVal;
        this.priorSectionsMaxChars = priorSectionsMaxCharsVal;
        this.maxSections = maxSectionsVal;
        this.contextMaxChars = contextCharsVal;
        this.sectionRetryOnUnauthorized = sectionRetryVal;
        this.coverMode = coverModeVal;
        this.scrapeQuotaMode = quotaModeVal;
        this.scrapePerQueryQuota = perQueryQuotaVal;
        this.scrapeMinQuota = minQuotaVal;
        // 上界不得低于下界（配置自相矛盾时以下界为准，防配额被压到 0）
        this.scrapeHardCap = Math.max(minQuotaVal, hardCapVal);
        this.assignByCitation = assignByCitationVal;
        this.evidenceIndexMaxChars = evidenceIndexCharsVal;
    }

    /** 三个派生预算的**唯一计算处**（原分散在 {@code ScrapeNode} 与 {@code DeepResearchGraph}
     *  的内联表达式中）。消费节点与快照日志共用本方法 ⇒ 不再有第二处声明。
     *  <p>表达式逐字搬自原位置，本方法只搬家不改数；两个默认值由
     *  {@code Budgets.defaults().extraction()} 供给。 */
    EffectiveBudgets budgets() {
        return EffectiveBudgets.of(Budgets.defaults().extraction(), sourceDistill, distillMaxChars);
    }

    /** 生效值快照（每逻辑组一行），供任务启动时打日志。
     *  <p>只读：不读 state、不调 LLM、无副作用。
     *  格式：每逻辑组一行，形如 {@code 组名 | k=v k=v}。 */
    List<String> snapshot() {
        EffectiveBudgets b = budgets();
        List<String> lines = new ArrayList<>();
        lines.add("图规模 | mode=" + (deepResearch ? "deep_research" : "flat")
                + " breadth=" + breadth + " depth=" + depth
                + " maxSubQueries=" + maxSubQueries
                + " clarifyQuestions=" + clarifyQuestions
                + " followUpDriven=" + followUpDriven + " breadthDecay=" + breadthDecay);
        lines.add("检索 | retriever=" + (retriever == null ? "(default)" : retriever)
                + " maxResults=" + (maxResults == null ? "(default)" : maxResults)
                + " blockedUrls=" + blockedUrls.size() + " fetchFullPage=" + fetchFullPage);
        lines.add("抓取 | maxScrapeUrls=" + maxScrapeUrls + " quotaMode=" + scrapeQuotaMode
                + " perQueryQuota=" + scrapePerQueryQuota
                + " minQuota=" + scrapeMinQuota + " hardCap=" + scrapeHardCap);
        lines.add("提炼 | perQueryExtract=" + perQueryExtract
                + " sourceDistill=" + sourceDistill + " distillMaxChars=" + distillMaxChars
                + " distillConcurrency=" + distillConcurrency + "(IGNORED)"
                + " extractOnDistilled=" + extractOnDistilled
                + " planReflect=" + planReflect + " coverMode=" + coverMode);
        lines.add("预算派生 | extraChars=" + b.extraChars()
                + " pageRawCap=" + b.pageRawCap() + " groupJoinCap=" + b.groupJoinCap());
        lines.add("写作 | sectionWriting=" + sectionWriting
                + " sectionContextChars=" + sectionContextChars
                        + " priorSectionsMaxChars=" + priorSectionsMaxChars + " maxSections=" + maxSections
                + " contextMaxChars=" + contextMaxChars + " assignByCitation=" + assignByCitation
                + " evidenceIndexMaxChars=" + evidenceIndexMaxChars
                + " sectionRetry=" + sectionRetryOnUnauthorized + " language=[" + language + "]");
        lines.add("其它 | curateSources=" + curateSources
                + " curatorMaxSources=" + curatorMaxSources + " sourceRank=" + sourceRank);
        return lines;
    }

    /** 语言归一化：zh/中文/Chinese→"中文"；en/英文/English→"English"；其它透传。 */
    static String normalizeLanguage(String raw) {
        String v = raw.trim().toLowerCase();
        if (v.equals("zh") || v.equals("中文") || v.equals("chinese") || v.startsWith("zh-")) {
            return "中文";
        }
        if (v.equals("en") || v.equals("english") || v.equals("英文") || v.startsWith("en-")) {
            return "English";
        }
        return raw.trim();
    }
}
