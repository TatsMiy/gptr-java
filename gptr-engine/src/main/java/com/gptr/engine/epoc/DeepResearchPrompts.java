package com.gptr.engine.epoc;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.engine.budget.Budgets;
import com.gptr.engine.budget.ExtractionBudget;
import com.gptr.engine.budget.WritingBudget;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * deep research prompt 资产加载（原版 Apache-2.0 搬运）与结构化 JSON 解析容错。
 */
public final class DeepResearchPrompts {

    private static final Properties PROPS = new Properties();
    private static final ObjectMapper MAPPER = new ObjectMapper();
        /** 写作域预算载体：原
     *  {@code QUOTE_RENDER_MAX_CHARS} 常量已并入。 */
    private static final WritingBudget WRITING = Budgets.defaults().writing();
    private static final Pattern JSON_ARRAY = Pattern.compile("\\[[\\s\\S]*\\]");
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");

    static {
        try (InputStream in = DeepResearchPrompts.class.getResourceAsStream("/prompts/deep-research.properties")) {
            // UTF-8 显式：properties 值含中文 prompt（source-distill 等），默认 ISO-8859-1 会乱码
            PROPS.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("failed to load deep-research prompts", e);
        }
    }

    private DeepResearchPrompts() {
    }

    public static String get(String key) {
        return PROPS.getProperty(key, "");
    }

    /**
     * 取 prompt 并替换 {@code {name}} 占位符 —— **全项目唯一的 prompt 出口**。
     *
     * <p><b>只替换 {@code vars} 里列出的 key</b>，不做"凡 {@code {x}} 都换"——
     * prompt 里含 JSON schema 字面量（如 {@code {"covered": ...}}），
     * 通配替换会把它们当成占位符吃掉。
     *
     * <p>存在意义：在此之前 {@link com.gptr.engine.write.ReportWriter} **自持一份
     * {@code Properties}** 并手工 {@code replace}，于是"报告语言"这类**跨 prompt 的公共变量**
     * 只能在它那一处生效（逐节写作路径整个漏掉）。收敛到本方法后，注入点不再分散。
     */
    public static String get(String key, Map<String, String> vars) {
        String text = PROPS.getProperty(key, "");
        for (Map.Entry<String, String> e : vars.entrySet()) {
            text = text.replace("{" + e.getKey() + "}", e.getValue());
        }
        return text;
    }

    /** 解析查询列表 JSON（容错：裸数组 / markdown 包裹 / 退化）。 */
    static List<String> parseQueryList(String raw, int maxQueries) {
        for (String candidate : candidates(raw)) {
            List<String> out = queryListOf(candidate, maxQueries);
            if (out != null) {
                return out;
            }
        }
        return List.of("fallback-query");
    }

    /** 单个候选的查询列表解析；非数组 / 空 / 坏 JSON → null（试下一个候选）。 */
    private static List<String> queryListOf(String candidate, int maxQueries) {
        try {
            JsonNode arr = MAPPER.readTree(candidate);
            if (!arr.isArray()) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String q = n.path("query").asText(null);
                if (q != null && !q.isBlank()) {
                    out.add(q.trim());
                }
                if (out.size() >= maxQueries) {
                    break;
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Exception ignored) {
            // next candidate
            return null;
        }
    }

    /** 查询规格（覆盖自检 schema：query + researchGoal + targetedDimension）。 */
    record QuerySpec(String query, String researchGoal, String targetedDimension) {
    }

    /**
     * 解析覆盖自检 schema（容错兼容旧格式）：
     * 新：{@code {"queries":[{"query","researchGoal","targetedDimension"}],"uncoveredDimensions":[]}}；
     * 旧：裸数组 {@code [{"query","researchGoal"}]} 或字符串数组。
     * 空/坏 → 返回空列表（调用方回退旧 parseQueryList 语义）。
     */
    static List<QuerySpec> parseQuerySpecs(String raw, int maxQueries) {
        for (String candidate : candidates(raw)) {
            List<QuerySpec> out = querySpecsOf(candidate, maxQueries);
            if (out != null) {
                return out;
            }
        }
        return List.of();
    }

    /** 单个候选的覆盖自检 schema 解析；非数组 / 空 / 坏 JSON → null（试下一个候选）。 */
    private static List<QuerySpec> querySpecsOf(String candidate, int maxQueries) {
        try {
            JsonNode root = MAPPER.readTree(candidate);
            JsonNode arr = root.isArray() ? root : root.path("queries");
            if (!arr.isArray()) {
                return null;
            }
            List<QuerySpec> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String q = n.isTextual() ? n.asText("") : n.path("query").asText("");
                if (q.isBlank()) {
                    continue;
                }
                out.add(new QuerySpec(q.trim(),
                        n.isTextual() ? "" : n.path("researchGoal").asText("").trim(),
                        n.isTextual() ? "" : n.path("targetedDimension").asText("").trim()));
                if (out.size() >= maxQueries) {
                    break;
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Exception ignored) {
            // next candidate
            return null;
        }
    }

        /** 维度规格——一个维度 + 该维度下的查询（至少 1 条才算已覆盖）。 */
    record Dimension(String name, List<String> queries) {
    }

    /** 维度清单解析结果：展平的查询（保序）+ 维度（含各自查询）。 */
    record DimensionPlan(String researchGoal, List<Dimension> dimensions, List<String> queries) {
        boolean isEmpty() {
            return queries.isEmpty();
        }
    }

    /**
     * 解析维度清单 schema（覆盖机制新形态）：
     * {@code {"researchGoal": "...", "dimensions": [{"dimension": "...", "queries": ["..."]}]}}。
     *
     * <p>机械性：只做结构解析（去掉空维度/空查询/超量查询），**不做语义判断**——"某维度是否有
     * 查询"由结构本身回答，这正是替换"LLM 自证缺失"判定方式的关键：判定可复现。
     * 兼容：维度项可为字符串（视为只有名字、无查询 → 会被判为待补）；旧 schema（queries[] 扁平）
     * → 返回空 plan（调用方回退 legacy 路径）。
     */
    static DimensionPlan parseDimensions(String raw, int maxQueries) {
        for (String candidate : candidates(raw)) {
            DimensionPlan plan = dimensionPlanOf(candidate, maxQueries);
            if (plan != null) {
                return plan;
            }
        }
        return new DimensionPlan("", List.of(), List.of());
    }

    /** 单个候选的维度清单解析；旧 schema / 坏 JSON / 空 plan → null（调用方回退 legacy 路径）。 */
    private static DimensionPlan dimensionPlanOf(String candidate, int maxQueries) {
        try {
            JsonNode root = MAPPER.readTree(candidate);
            if (root.isArray()) {
                return null; // 旧裸数组 schema：非本形态
            }
            JsonNode dims = root.path("dimensions");
            if (!dims.isArray()) {
                return null;
            }
            List<Dimension> out = new ArrayList<>();
            List<String> flat = new ArrayList<>();
            for (JsonNode d : dims) {
                addDimension(d, out, flat, maxQueries);
            }
            if (out.isEmpty()) {
                return null;
            }
            return new DimensionPlan(root.path("researchGoal").asText("").trim(),
                    List.copyOf(out), List.copyOf(flat));
        } catch (Exception ignored) {
            // next candidate
            return null;
        }
    }

    /** 解析单个维度项追加进 {@code out}，并把该维度的查询去重展平进 {@code flat}。 */
    private static void addDimension(JsonNode d, List<Dimension> out, List<String> flat, int maxQueries) {
        String name = d.isTextual() ? d.asText("").trim() : d.path("dimension").asText("").trim();
        List<String> qs = new ArrayList<>();
        JsonNode qArr = d.path("queries");
        if (qArr.isArray()) {
            for (JsonNode q : qArr) {
                String qt = q.isTextual() ? q.asText("") : q.path("query").asText("");
                if (!qt.isBlank()) {
                    qs.add(qt.trim());
                }
            }
        }
        if (name.isBlank() && qs.isEmpty()) {
            return;
        }
        Dimension dim = new Dimension(name.isBlank() ? "(unnamed)" : name,
                List.copyOf(qs));
        out.add(dim);
        for (String q : qs) {
            if (flat.size() >= maxQueries) {
                break; // 只结束本维度的展平，外层维度循环继续
            }
            if (!flat.contains(q)) {
                flat.add(q);
            }
        }
    }

    /** 维度清单中**没有任何查询**的维度名（机械下界的缺口集合）。 */
    static List<String> dimensionsWithoutQueries(DimensionPlan plan) {
        List<String> out = new ArrayList<>();
        for (Dimension d : plan.dimensions()) {
            if (d.queries().isEmpty()) {
                out.add(d.name());
            }
        }
        return out;
    }

    /** 维度 → JSON 单对象字符串（状态键 {@code dimensions} 的载体，checkpoint 兼容）。 */
    static String dimensionToJson(String name, List<String> queries) {
        try {
            var node = MAPPER.createObjectNode();
            node.put("dimension", name == null ? "" : name);
            var arr = node.putArray("queries");
            if (queries != null) {
                queries.forEach(arr::add);
            }
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            // 序列化失败 → 返回空维度结构（调用方按"无查询"处理，不中断生成）
            return "{\"dimension\":\"\",\"queries\":[]}";
        }
    }

    /** 解析覆盖自检的未覆盖维度列表（{@code uncoveredDimensions: []}；旧 schema/坏 → 空）。 */
    static List<String> parseUncoveredDimensions(String raw) {
        for (String candidate : candidates(raw)) {
            List<String> out = uncoveredDimensionsOf(candidate);
            if (out != null) {
                return out;
            }
        }
        return List.of();
    }

    /** 单个候选的未覆盖维度解析；旧 schema / 无该字段 / 坏 JSON → null（试下一个候选）。 */
    private static List<String> uncoveredDimensionsOf(String candidate) {
        try {
            JsonNode root = MAPPER.readTree(candidate);
            if (root.isArray()) {
                return null; // 旧 schema 无此字段
            }
            JsonNode arr = root.path("uncoveredDimensions");
            if (!arr.isArray()) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String s = n.asText("");
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
            return out; // 空数组亦为有效信号（自检全覆盖）
        } catch (Exception ignored) {
            // next candidate
            return null;
        }
    }

    /** 解析 plan_reflect 缺口列表（{@code {"covered": "...", "gaps": []}}；缺/坏 → 空）。 */
    static List<String> parseGaps(String raw) {
        for (String candidate : candidates(raw)) {
            List<String> out = gapsOf(candidate);
            if (out != null) {
                return out;
            }
        }
        return List.of();
    }

    /** 单个候选的缺口列表解析；非对象 / 无 gaps 数组 / 坏 JSON → null（试下一个候选）。 */
    private static List<String> gapsOf(String candidate) {
        try {
            JsonNode root = MAPPER.readTree(candidate);
            if (!root.isObject()) {
                return null;
            }
            JsonNode arr = root.path("gaps");
            if (!arr.isArray()) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String s = n.asText("");
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
            return out;
        } catch (Exception ignored) {
            // next candidate
            return null;
        }
    }

    /** 解析 follow-up questions 列表（{"questions": [...]}，容错）。 */
    static List<String> parseQuestionList(String raw, int maxQuestions) {
        for (String candidate : candidates(raw)) {
            List<String> out = questionListOf(candidate, maxQuestions);
            if (out != null) {
                return out;
            }
        }
        return List.of();
    }

    /** 单个候选的问题列表解析；非数组 / 空 / 坏 JSON → null（试下一个候选）。 */
    private static List<String> questionListOf(String candidate, int maxQuestions) {
        try {
            JsonNode root = MAPPER.readTree(candidate);
            JsonNode arr = root.isArray() ? root : root.path("questions");
            if (!arr.isArray()) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String q = n.asText(null);
                if (q != null && !q.isBlank()) {
                    out.add(q.trim());
                }
                if (out.size() >= maxQuestions) {
                    break;
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Exception ignored) {
            // next candidate
            return null;
        }
    }

    /** 解析来源分档的 high 序号（{"high": [1, 3]} 或裸数组）；**转为 0-based**。
     *  越界/重复/非整数一律剔除；无可解析项 → **空列表**（调用方据此回退原序）。
     *  <p>只认 {@code high} 键：{@code {"kept": …}} 等其它键视为不可解析（防串用 curate 的输出）。 */
    static List<Integer> parseHighIndices(String raw, int size) {
        // 串用 curate 的 schema（{"kept": …}）→ 视为不可解析：两个 prompt 的契约不同，
        // 混用是真实错误；而 candidates() 会把内嵌的 [..] 当裸数组提取，故必须先拦。
        if (raw == null || raw.contains("\"kept\"")) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (String candidate : candidates(raw)) {
            JsonNode root;
            try {
                root = MAPPER.readTree(candidate);
            } catch (Exception ignored) {
                continue; // next candidate
            }
            JsonNode arr = root.isArray() ? root : root.path("high");
            if (!arr.isArray()) {
                continue;
            }
            for (JsonNode n : arr) {
                if (!n.isIntegralNumber()) { continue; }
                int zeroBased = n.asInt() - 1; // 输入是 1-based
                boolean usable = zeroBased >= 0 && zeroBased < size && !out.contains(zeroBased);
                if (usable) { out.add(zeroBased); }
            }
        }
        return out;
    }

    /** 解析来源质量闸的保留序号（{"kept": [2, 0, 5]} 或裸数组；顺序 = 优先级）。
     * 返回 null 表示不可解析（调用方回退原文，防误杀）。序号越界/重复会被剔除。
     * public：flat 引擎（com.gptr.engine）与 epoc 图节点共用。 */
    public static List<Integer> parseKeptIndices(String raw) {
        for (String candidate : candidates(raw)) {
            List<Integer> out = keptIndicesOf(candidate);
            if (out != null) {
                return out;
            }
        }
        return null;
    }

    /** 单个候选的保留序号解析；非数组 / 空 / 坏 JSON → null（试下一个候选）。 */
    private static List<Integer> keptIndicesOf(String candidate) {
        try {
            JsonNode root = MAPPER.readTree(candidate);
            JsonNode arr = root.isArray() ? root : root.path("kept");
            if (!arr.isArray()) {
                return null;
            }
            List<Integer> out = new ArrayList<>();
            for (JsonNode n : arr) {
                if (n.isIntegralNumber()) {
                    out.add(n.asInt());
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Exception ignored) {
            // next candidate
            return null;
        }
    }

    /** 解析中央研究状态（{"researchState": "..."} 等键容错）；不可解析 → null。 */
    public static String parseResearchState(String raw) {
        for (String candidate : candidates(raw)) {
            String state = researchStateOf(candidate);
            if (state != null) {
                return state;
            }
        }
        return null;
    }

    /** 单个候选的研究状态解析；非对象 / 无可用键 / 坏 JSON → null（试下一个候选）。 */
    private static String researchStateOf(String candidate) {
        try {
            JsonNode root = MAPPER.readTree(candidate);
            if (!root.isObject()) {
                return null;
            }
            for (String key : List.of("researchState", "state", "plan", "focus")) {
                String v = root.path(key).asText("");
                if (!v.isBlank()) {
                    return v.trim();
                }
            }
            return null;
        } catch (Exception ignored) {
            // next candidate
            return null;
        }
    }

    /**
     * 解析 learnings + followUpQuestions（容错：字段缺失时退化）。
     * quote 渲染截断 120 编码进文本（[quote:] 置于 [source:] 前）。
     * 完整 quote 的解析走 {@link #parseNotes}（note 保真存储，不在此截断）。
     */
    static ParsedLearnings parseLearnings(String raw) {
                // 兼容入口：无预算上下文时用出厂默认
        return parseLearnings(raw, Budgets.defaults().extraction());
    }

    /** 带预算的解析（生产路径经此传入；{@link #parseLearnings(String)} 是默认值委托）。 */
    static ParsedLearnings parseLearnings(String raw, ExtractionBudget extraction) {
        ParsedNotes parsed = parseNotes(raw, extraction);
        List<String> learnings = new ArrayList<>();
        for (EvidenceNote.Raw r : parsed.raws()) {
            StringBuilder text = new StringBuilder(r.insight().trim());
            if (!r.quote().isBlank() && !r.sourceUrl().isBlank()) {
                text.append(" [quote: ").append(truncateQuote(r.quote())).append("]");
            }
            if (!r.sourceUrl().isBlank()) {
                text.append(" [source: ").append(r.sourceUrl()).append("]");
            }
            learnings.add(text.toString());
        }
        return new ParsedLearnings(learnings, parsed.followUpQuestions());
    }

    /** quote 渲染进 learnings 文本时的截断长度。 */
    /** quote 渲染截断（[ ] 清洗防格式破坏）——上限取 {@code WritingBudget#quoteRenderMaxChars}。 */
    public static String truncateQuote(String quote) {
        String clean = quote.trim().replaceAll("[\\[\\]]", " ");
        if (clean.length() > WRITING.quoteRenderMaxChars()) {
            clean = clean.substring(0, WRITING.quoteRenderMaxChars()).trim() + "…";
        }
        return clean;
    }

    /**
     * 解析 evidenceBank 的原始 note 列表（insight/quote 完整/url）+ followUps。
     * quote 按 {@code extraction.quoteStoreMax()} 截断存放（保真存储，非渲染截断）。
     */
    static ParsedNotes parseNotes(String raw, ExtractionBudget extraction) {
        List<EvidenceNote.Raw> raws = new ArrayList<>();
        List<String> followUps = new ArrayList<>();
        for (String candidate : candidates(raw)) {
            if (collectNotes(candidate, extraction, raws, followUps)) {
                break;
            }
        }
        return new ParsedNotes(raws, followUps);
    }

    /** 收集单个候选的 note / followUp（累积进传入列表）；已取到内容 → true（停止试下一个候选）。 */
    private static boolean collectNotes(String candidate, ExtractionBudget extraction,
            List<EvidenceNote.Raw> raws, List<String> followUps) {
        try {
            JsonNode root = MAPPER.readTree(candidate);
            collectLearnings(root.path("learnings"), extraction, raws);
            collectFollowUps(root.path("followUpQuestions"), followUps);
            return !raws.isEmpty() || !followUps.isEmpty();
        } catch (Exception ignored) {
            // next candidate
            return false;
        }
    }

    /** learnings 数组 → 原始 note（insight 为空的条目剔除）；非数组则不作为。 */
    private static void collectLearnings(JsonNode l, ExtractionBudget extraction, List<EvidenceNote.Raw> raws) {
        if (!l.isArray()) {
            return;
        }
        for (JsonNode n : l) {
            String insight = n.path("insight").asText(null);
            if (insight == null || insight.isBlank()) {
                continue;
            }
            String quote = n.path("evidenceQuote").asText("");
            raws.add(new EvidenceNote.Raw(insight.trim(),
                    quote.isBlank() ? ""
                            : truncateStore(quote.trim(), extraction.quoteStoreMax()),
                    n.path("sourceUrl").asText("").trim(), false));
        }
    }

    /** followUpQuestions 数组 → 文本列表；非数组则不作为。 */
    private static void collectFollowUps(JsonNode f, List<String> followUps) {
        if (!f.isArray()) {
            return;
        }
        for (JsonNode n : f) {
            String q = n.asText(null);
            if (q != null && !q.isBlank()) {
                followUps.add(q.trim());
            }
        }
    }

    /** 存储层截断（保真上限 evidenceQuoteMaxChars=2000；仅防失控长文本）。
     *  上限原为 {@code EvidenceNote.QUOTE_STORE_MAX}，现由
          *  {@code ExtractionBudget#quoteStoreMax} 经参数传入。 */
    static String truncateStore(String quote, int max) {
        if (quote.length() <= max) {
            return quote;
        }
        return quote.substring(0, max).trim() + "…";
    }

    record ParsedNotes(List<EvidenceNote.Raw> raws, List<String> followUpQuestions) {
    }

    record ParsedLearnings(List<String> learnings, List<String> followUpQuestions) {
    }

    private static List<String> candidates(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            out.add(raw.trim());
            Matcher m = JSON_ARRAY.matcher(raw);
            if (m.find()) {
                out.add(m.group());
            }
            Matcher o = JSON_OBJECT.matcher(raw);
            if (o.find()) {
                out.add(o.group());
            }
        }
        return out;
    }
}
