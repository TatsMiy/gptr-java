package com.gptr.engine.write;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.engine.budget.Budgets;
import com.gptr.engine.budget.WritingBudget;
import com.gptr.engine.epoc.DeepResearchPrompts;
import com.gptr.engine.epoc.EvidenceNote;
import com.gptr.integration.client.LlmClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * 逐节写作器（outline 先行 → 逐节独立写作 → 机械 References；FS-Researcher/WebWeaver 背书）：
 * outline 先行（1 调）→ 每节独立写作（该节证据子集 + 该节授权 URL 集，节级引用闸门，
 * 上下文隔离不带已写节）→ Key Takeaways（1 调）→ References 机械生成。
 *
 * <p>证据归属 = 机械打标（note.queryText 与节 queries 最长公共子串 ≥8 匹配，兜底
 * queryIdx/未归组节），不学 LLM 自主归档与写作时检索（红线 #3）。
 *
 * <p>节级引用闸门（红线 #1）：每节授权 URL = 该节证据 note 的 sourceUrl 集（canonical）；
 * 节写后立即校验，违规计数（可选重写 1 次）。报告级全局校验由引擎 writePayload 保留
 * （双层闸）。
 */
public class SectionWriter {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(SectionWriter.class);

    /** 归节匹配的最低公共子串长度（queryText 与节 queries 都是 ≤400 截断的同源文本）。 */
    public static final int MATCH_MIN = 8;

    /** 批 2：大纲节数默认上限（解析 cap 与 prompt 同源；EngineConfig.maxSections 可配）。 */
    public static final int DEFAULT_MAX_SECTIONS = 6;

    /** 已写节注入的"关闭"值（= 现状等价；兼容构造器与 A/B 对照档使用）。 */
    public static final int NO_PRIOR_SECTIONS = 0;

        /** 写作域预算载体——原
     *  {@code DEFAULT_EVIDENCE_INDEX_CHARS} / {@code INDEX_SUMMARY_CHARS} /
     *  {@code SECTION_QUOTE_CHARS} 三个常量已并入，本类不再声明它们的值。
     *  <p>取值理由随常量迁至 {@link WritingBudget}：目录上限曾于 2026-09-13 由 12000 提到
     *  40000（实测 195 条证据 ≈21k 字符，12000 时截断 31%、被截证据无法被直引）。 */
    private static final WritingBudget WRITING = Budgets.defaults().writing();

    /** {@code maxSections} 二次收窄的下界。 */
    private static final int MIN_SECTIONS = 2;

    /** {@code maxSections} 二次收窄的上界（与 {@code EngineConfig} 的校验域同值）。 */
    private static final int MAX_SECTIONS_LIMIT = 12;

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");

    /** 节内 markdown 链接（文本 + URL，括号配对提取）。 */
    public record Link(String label, String url) {
    }

    private final LlmClient llm;
    private final int sectionContextChars;
    private final boolean retryOnUnauthorized;
    private final int maxSections;
    /** 已写节注入的字符预算（**≤0 = 关闭注入 = 现状等价**）。
          *  <p>把已写完的小节注入后续小节的写作上下文，抑制节间重复。 */
    private final int priorSectionsMaxChars;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 大纲节。subQueryIdx：该节覆盖的输入子查询下标（0-based；LLM 坏输出=空 → 弱锚）。
     *  批 4（WebWeaver 直引）：evidenceIdx = 该节**直引的证据下标**（对应证据目录的 [idx]，
     *  与 {@link #buildEvidenceIndex} 的编号一致；越界由消费端静默丢弃）。 */
    public record Section(String title, String goal, List<String> queries,
                          List<Integer> subQueryIdx, List<Integer> evidenceIdx) {

        /** 兼容构造（无 idx → 弱锚归节）。 */
        public Section(String title, String goal, List<String> queries) {
            this(title, goal, queries, List.of(), List.of());
        }

        /** 兼容构造（批 4 之前：有 subQueryIdx、无 evidenceIdx）。 */
        public Section(String title, String goal, List<String> queries,
                       List<Integer> subQueryIdx) {
            this(title, goal, queries, subQueryIdx, List.of());
        }
    }

    /** 单节写作结果。 */
    public record SectionOutcome(String markdown, int unauthorized, boolean retried) {
    }

    /** 总结果（references 已机械并入 report）。 */
    public record WriteResult(String report, int sectionCount, int unauthorizedTotal,
                              int retriedSections, int fallbackNotes) {
    }

    public SectionWriter(LlmClient llm, int sectionContextChars, boolean retryOnUnauthorized) {
        this(llm, sectionContextChars, retryOnUnauthorized, DEFAULT_MAX_SECTIONS);
    }

    /** 批 2：maxSections 可配（大纲 prompt "at most {maxSections}" 与解析 cap 同源）。
     *  <p>兼容构造：**不启用已写节注入**（{@link #NO_PRIOR_SECTIONS}）——仅测试与回退档使用。 */
    public SectionWriter(LlmClient llm, int sectionContextChars, boolean retryOnUnauthorized,
                         int maxSections) {
        this(llm, sectionContextChars, retryOnUnauthorized, maxSections, NO_PRIOR_SECTIONS);
    }

        /** 生产构造（2026-09-19）：{@code priorSectionsMaxChars ≤ 0} ⇒ 关闭已写节注入。 */
    public SectionWriter(LlmClient llm, int sectionContextChars, boolean retryOnUnauthorized,
                         int maxSections, int priorSectionsMaxChars) {
        this.llm = llm;
        this.sectionContextChars = sectionContextChars;
        this.retryOnUnauthorized = retryOnUnauthorized;
        this.maxSections = Math.max(MIN_SECTIONS, Math.min(MAX_SECTIONS_LIMIT, maxSections));
        this.priorSectionsMaxChars = priorSectionsMaxChars;
    }

    // ------------------------------------------------------------------
    // outline
    // ------------------------------------------------------------------

    /** outline 生成（1 调）；坏输出/空 → null（调用方回退单遍 writer）。
     *  批 4：{@code evidenceIndex} 为空 → 走"无目录"文案（保持旧行为，便于对照）。 */
    public OutlineResult writeOutline(String query, String researchState, List<String> subQueries) {
        return writeOutline(query, researchState, subQueries, null);
    }

    /** 批 4：带证据目录的 outline（目录由 {@link #buildEvidenceIndex} 构造，LLM 可据其直引）。 */
    public OutlineResult writeOutline(String query, String researchState, List<String> subQueries,
                                      String evidenceIndex) {
        StringBuilder qb = new StringBuilder();
        for (String q : subQueries) {
            qb.append("- ").append(q).append("\n");
        }
        String system = DeepResearchPrompts.get("report-outline.system");
        String user = DeepResearchPrompts.get("report-outline.user")
                .replace("{query}", query == null ? "" : query)
                .replace("{researchState}", researchState == null || researchState.isBlank()
                        ? "(none)" : researchState)
                .replace("{maxSections}", String.valueOf(maxSections))
                .replace("{evidenceIndex}", evidenceIndex == null || evidenceIndex.isBlank()
                        ? "(no evidence index available)" : evidenceIndex)
                .replace("{queries}", qb.toString());
        try {
            String raw = llm.chatJson(system, user);
            JsonNode root = parseJson(raw);
            if (root == null) {
                return null;
            }
            String title = root.path("title").asText("");
            JsonNode arr = root.path("sections");
            if (!arr.isArray() || arr.isEmpty()) {
                return null;
            }
            int planned = arr.size();
            int candidates = 0;
            List<Section> sections = new ArrayList<>();
            for (JsonNode s : arr) {
                String t = s.path("title").asText("");
                if (t.isBlank()) {
                    continue;
                }
                candidates++;
                if (sections.size() >= maxSections) {
                    continue;   // 已满：继续数（截断判定需要真实候选数），但不再收
                }
                List<String> qs = new ArrayList<>();
                for (JsonNode q : s.path("queries")) {
                    String qt = q.asText("");
                    if (!qt.isBlank()) {
                        qs.add(qt.trim());
                    }
                }
                // subQueryIdx 解析（0-based；缺/非数组/坏值 → 空 → 弱锚回退）。
                // 越界下标消费时静默丢弃（assignNotes 内校验），绝不让坏输出中断成稿。
                List<Integer> idx = new ArrayList<>();
                JsonNode idxArr = s.path("subQueryIdx");
                if (idxArr.isArray()) {
                    for (JsonNode it : idxArr) {
                        if (it.isIntegralNumber()) {
                            idx.add(it.asInt());
                        } else if (it.isTextual()) {
                            try {
                                idx.add(Integer.parseInt(it.asText().trim()));
                            } catch (NumberFormatException ignored) {
                                // 非数字文本：跳过
                            }
                        }
                    }
                }
                sections.add(new Section(t.trim(), s.path("goal").asText("").trim(),
                        qs, idx, parseEvidenceIdx(s)));
            }
            if (sections.isEmpty()) {
                return null;
            }
            // 批 2：maxSections 是否真的截断（candidates > cap 才算参数生效，否则是模型自己少给；
            // candidates 已剔除空标题，blank 跳过不算截断）
            LOG.info("[batch2] outline: raw={} candidates={} cap={} kept={}{}", planned, candidates,
                    maxSections, sections.size(), candidates > maxSections ? " TRUNCATED" : "");
            return new OutlineResult(title.isBlank() ? null : title.trim(), sections);
        } catch (Exception e) {
            // 【2026-09-14 修复】原为静默 `return null`：outline 是逐节写作的入口，它失败会让
            // 整份报告静默回退到单遍 writer（运维只看到 writingMode: single），不留任何痕迹，
            // 而本文件其他失败路径（:180/:328/:330）都打了日志。此处补 warn。
            LOG.warn("[section] outline 生成失败 → 将回退单遍 writer: {}", e.toString());
            return null;
        }
    }

    /**
          * 构造**证据目录**文本，注入 outline prompt，让大纲能"看见"证据。
     *
     * <p>对标 WebWeaver：planner 看得见每页 summary + 能写证据 id。此处每条证据一行
     * {@code [idx] 摘要}，idx 即 notes 列表下标（与 {@link #groupByCitation} 消费的编号一致）。
     *
     * <p>两点设计：
     * <ul>
     *   <li><b>按查询方向分组</b>（queryText 首次出现序）——批 4-pre 的维度清单模式下，
          *       一个维度通常对应一条查询，故分组天然接近"按维度组织证据"，
     *       且无需额外状态；</li>
     *   <li><b>组间轮转取用</b>——预算有限时每组都能露脸，避免前半段证据吃满目录
     *       （复用抓取配额轮转的同一思想）；截断处注明省略条数。</li>
     * </ul>
     *
     * @param maxChars 目录字符预算（≤0 → 用默认 {@code WritingBudget#evidenceIndexMaxChars}）
     */
    public static String buildEvidenceIndex(List<EvidenceNote> notes, int maxChars) {
        return buildEvidenceIndexWithStats(notes, maxChars).text();
    }

        /** 目录统计——截断率是"抓取改善是否转化为素材"的直接观测量。 */
    public record EvidenceIndexStats(String text, int total, int included, int omitted, int chars) {
    }

        /** 带统计的目录构造（正文同 {@link #buildEvidenceIndex}）。 */
    public static EvidenceIndexStats buildEvidenceIndexWithStats(List<EvidenceNote> notes,
                                                                 int maxChars) {
        if (notes == null || notes.isEmpty()) {
            return new EvidenceIndexStats("(no evidence collected)", 0, 0, 0, 0);
        }
        int budget = maxChars > 0 ? maxChars : WRITING.evidenceIndexMaxChars();
        LinkedHashMap<String, List<Integer>> byQuery = new LinkedHashMap<>();
        for (int i = 0; i < notes.size(); i++) {
            String qt = notes.get(i).queryText();
            String key = (qt == null || qt.isBlank()) ? "(unknown direction)" : qt.trim();
            byQuery.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        StringBuilder sb = new StringBuilder(Math.min(budget, 4096));
        List<List<Integer>> groups = new ArrayList<>(byQuery.values());
        List<String> titles = new ArrayList<>(byQuery.keySet());
        int[] cursor = new int[groups.size()];
        int included = 0;
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int g = 0; g < groups.size(); g++) {
                if (cursor[g] >= groups.get(g).size()) {
                    continue;
                }
                int noteIdx = groups.get(g).get(cursor[g]++);
                String line = "[" + noteIdx + "] " + indexSummary(notes.get(noteIdx).insight());
                String header = (cursor[g] == 1) ? titles.get(g) + "\n" : "";
                if (sb.length() + header.length() + line.length() + 2 > budget) {
                    continue;   // 超上限 → 跳过该条（继续试更短的）
                }
                sb.append(header).append(line).append('\n');
                included++;
                progress = true;
            }
        }
        int omitted = notes.size() - included;
        if (omitted > 0) {
            sb.append("... (").append(omitted).append(" more evidence omitted for brevity)\n");
        }
        return new EvidenceIndexStats(sb.toString(), notes.size(), included, omitted, sb.length());
    }

    /** 目录行摘要：压平空白 + 截断到 {@code WritingBudget#indexSummaryChars}。 */
    static String indexSummary(String insight) {
        if (insight == null) {
            return "";
        }
        String flat = insight.replaceAll("\\s+", " ").trim();
        return flat.length() <= WRITING.indexSummaryChars()
                ? flat : flat.substring(0, WRITING.indexSummaryChars()) + "…";
    }

    /**
          * **直引优先**归节——多对多，允许同一条证据被多节引用。
     *
     * <p>语义（干净版，不做文本匹配兜底）：
     * <ol>
     *   <li>note 下标 ∈ 节.evidenceIdx → 归该节（同 note 可归多节）；越界/负值静默丢弃；</li>
     *   <li>**未被任何节直引**的 note → 返回值的最后一组（兜底组），由调用方决定去留；</li>
     *   <li>直引为空的节 → 该节证据列表为空 → 上层走 F10 占位路径（诚实，不再猜）。</li>
     * </ol>
     *
     * @return 长度 = sections.size() + 1 的列表；前 N 组对应各节，**最后一组为未直引的兜底组**
     */
    public static List<List<Integer>> groupByCitation(List<EvidenceNote> notes,
                                                      List<Section> sections) {
        return groupByCitationWithStats(notes, sections).grouped();
    }

        /** 归节统计——{@code emptySections} 是 **F10 的唯一前兆**（该节无证据 → 占位）；
     *  {@code sharedNotes} 为被 ≥2 节引用的证据数（多对多生效的直接证据）。 */
    public record CitationStats(List<List<Integer>> grouped, int emptySections, int sharedNotes,
                                int droppedIdx, int fallbackNotes) {
    }

        /** 带统计的直引归节（语义同 {@link #groupByCitation}）。 */
    public static CitationStats groupByCitationWithStats(List<EvidenceNote> notes,
                                                         List<Section> sections) {
        List<List<Integer>> bySection = new ArrayList<>();
        for (int s = 0; s < sections.size(); s++) {
            bySection.add(new ArrayList<>());
        }
        Map<Integer, Integer> citeCount = new LinkedHashMap<>();
        int dropped = 0;
        for (int s = 0; s < sections.size(); s++) {
            for (Integer idx : sections.get(s).evidenceIdx()) {
                if (idx == null || idx < 0 || idx >= notes.size()) {
                    dropped++;
                    continue;   // 越界/坏值：静默丢弃（证据不丢，见兜底组）
                }
                bySection.get(s).add(idx);
                citeCount.merge(idx, 1, Integer::sum);
            }
        }
        List<Integer> unassigned = new ArrayList<>();
        for (int i = 0; i < notes.size(); i++) {
            if (!citeCount.containsKey(i)) {
                unassigned.add(i);
            }
        }
        int emptySections = 0;
        for (List<Integer> sec : bySection) {
            if (sec.isEmpty()) {
                emptySections++;
            }
        }
        int shared = 0;
        for (Integer c : citeCount.values()) {
            if (c >= 2) {
                shared++;
            }
        }
        bySection.add(unassigned);
        if (dropped > 0) {
            LOG.info("[batch4] citation: dropped {} out-of-range evidenceIdx (kept in fallback)", dropped);
        }
        LOG.info("[batch4] citation: {} notes → sections {} + fallback {} | emptySections={}"
                        + " sharedNotes={} (F10 前兆：emptySections>0 表示该节将走占位)",
                notes.size(), bySection.subList(0, sections.size()).stream()
                        .map(List::size).toList(), unassigned.size(), emptySections, shared);
        return new CitationStats(bySection, emptySections, shared, dropped, unassigned.size());
    }

    public record OutlineResult(String title, List<Section> sections) {
    }

    /**
     * 批 4：解析节的 {@code evidenceIdx}（直引证据下标）。
     *
          * <p>容错：非整数/负值静默剔除；**重复去重保序**；整字段缺失/坏 →
     * 空列表（该节走兜底，不整条拒收）。越界值此处不判（不知道 notes 规模），
     * 由 {@link #groupByCitation} 消费时静默丢弃。
     */
    static List<Integer> parseEvidenceIdx(JsonNode sectionNode) {
        List<Integer> out = new ArrayList<>();
        JsonNode arr = sectionNode.path("evidenceIdx");
        if (!arr.isArray()) {
            return out;
        }
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        for (JsonNode it : arr) {
            int v = Integer.MIN_VALUE;
            if (it.isIntegralNumber()) {
                v = it.asInt();
            } else if (it.isTextual()) {
                try {
                    v = Integer.parseInt(it.asText().trim());
                } catch (NumberFormatException ignored) {
                    // 非数字文本：剔除
                }
            }
            if (v >= 0) {
                seen.add(v);
            }
        }
        out.addAll(seen);
        return out;
    }

    // ------------------------------------------------------------------
    // 归节（机械打标）
    // ------------------------------------------------------------------

    /**
     * note → 节分配（两级锚）：
     * 1. <b>硬锚</b>：note.queryIdx ∈ 节.subQueryIdx → 直归该节（下标越界/负值静默丢弃，
     *    防 LLM 1-based 混淆与越界输出中断成稿）；
     * 2. <b>弱锚</b>：归一化后（小写+剥标点+去中英停用词，模板虚词不再共享）找最长
     *    公共子串 ≥ {@link #MATCH_MIN} 的节；无命中 → -1（未归组 → 兜底节）。
     */
    public int[] assignNotes(List<EvidenceNote> notes, List<Section> sections) {
        int[] out = new int[notes.size()];
        for (int i = 0; i < notes.size(); i++) {
            EvidenceNote note = notes.get(i);
            // 1) 硬锚
            int anchor = -1;
            if (note.queryIdx() >= 0) {
                for (int s = 0; s < sections.size(); s++) {
                    for (Integer qi : sections.get(s).subQueryIdx()) {
                        if (qi != null && qi >= 0 && qi.equals(note.queryIdx())) {
                            anchor = s;
                            break;
                        }
                    }
                    if (anchor >= 0) {
                        break;
                    }
                }
            }
            if (anchor >= 0) {
                out[i] = anchor;
                continue;
            }
            // 2) 弱锚（归一化匹配）
            String qt = normalizeMatch(notes.get(i).queryText());
            int bestSection = -1;
            int bestScore = MATCH_MIN - 1;
            for (int s = 0; s < sections.size(); s++) {
                for (String q : sections.get(s).queries()) {
                    int score = longestCommonSubstring(qt, normalizeMatch(q));
                    if (score > bestScore) {
                        bestScore = score;
                        bestSection = s;
                    }
                }
            }
            out[i] = bestSection;
        }
        return out;
    }

    /**
     * 匹配归一：英文按词过滤停用词（"What is the " 模板不再制造伪匹配），
     * 其余字符（中文/数字/ASCII 字母词干）按字母数字保留、小写、紧凑。
     * 中文不做停用词删除（单字虚词子串替换会误伤实词如"是非/但是"，保留字面更稳——
     * 中文同义改写漏配由兜底节吸收，设计红线：不引 embedding）。
     */
    static String normalizeMatch(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (isAsciiLetter(c)) {
                int j = i;
                while (j < n && isAsciiLetter(s.charAt(j))) {
                    j++;
                }
                String word = s.substring(i, j).toLowerCase();
                if (!EN_STOP_WORDS.contains(word)) {
                    sb.append(word);
                }
                i = j;
            } else {
                if (Character.isLetterOrDigit(c)) {
                    sb.append(Character.toLowerCase(c));
                }
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** 英文停用词（弱锚去模板共享虚词）。 */
    private static final Set<String> EN_STOP_WORDS = Set.of(
            "what", "is", "the", "of", "to", "in", "for", "on", "with", "and", "or",
            "are", "was", "were", "be", "been", "a", "an", "how", "why", "who",
            "which", "where", "when", "do", "does", "did", "main", "their", "it",
            "its", "as", "by", "at", "from", "that", "this", "these", "those",
            "give", "list", "explain", "describe", "about", "between", "vs", "versus");



    /** 最长公共子串长度（O(n*m)；输入均 ≤400，任务内一次可接受）。 */
    static int longestCommonSubstring(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        int n = a.length();
        int m = b.length();
        int[][] dp = new int[2][m + 1];
        int best = 0;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i % 2][j] = dp[(i - 1) % 2][j - 1] + 1;
                    best = Math.max(best, dp[i % 2][j]);
                } else {
                    dp[i % 2][j] = 0;
                }
            }
        }
        return best;
    }

    /** 覆盖校验：未被子查询文本覆盖的节 queries 与输入 subQueries 的差（防 LLM 漏节）。 */
    public List<String> uncoveredSubQueries(List<String> subQueries, List<Section> sections) {
        List<String> uncovered = new ArrayList<>();
        for (String sq : subQueries) {
            boolean covered = false;
            for (Section s : sections) {
                for (String q : s.queries()) {
                    if (containsOrFuzzy(sq, q)) {
                        covered = true;
                        break;
                    }
                }
                if (covered) {
                    break;
                }
            }
            if (!covered) {
                uncovered.add(sq);
            }
        }
        return uncovered;
    }

    private static boolean containsOrFuzzy(String a, String b) {
        // 覆盖校验同用归一化（模板虚词不参与判定）
        String na = normalizeMatch(a);
        String nb = normalizeMatch(b);
        return (na.contains(nb) || nb.contains(na))
                || longestCommonSubstring(na, nb) >= MATCH_MIN;
    }

    // ------------------------------------------------------------------
    // 逐节写作 + 节级引用闸门
    // ------------------------------------------------------------------

    /** 写一节（1 调；可选违规重写 1 次）。authorizedUrls = 该节 note sourceUrl 集。
     *  0：空证据节不调 LLM——占位（节标题层级 ## 与 report-section.user 约定一致）。 */
    public SectionOutcome writeSection(Section section, List<EvidenceNote> notes,
                                       List<String> authorizedUrls) {
        return writeSection(section, notes, authorizedUrls, null);
    }

    /** 写一节（含"已写节"注入；{@code priorSections} = 前面各节正文，null/blank = 无前文）。
          *  <p>注入前做净化（剥 URL）与截断处理。 */
    public SectionOutcome writeSection(Section section, List<EvidenceNote> notes,
                                       List<String> authorizedUrls, String priorSections) {
        String notesText = joinForSection(notes);
        if (notesText.isBlank()) {
            String title = section.title() == null ? "" : section.title().trim();
            return new SectionOutcome("## " + title + "\n\n"
                    + "（该节对应方面未检索到足够证据，本节从略）", 0, false);
        }
        String system = DeepResearchPrompts.get("report-section.system");
        String authorized = authorizedUrls.isEmpty() ? "(none)" : String.join("\n", authorizedUrls);
        String user = DeepResearchPrompts.get("report-section.user")
                .replace("{title}", section.title())
                .replace("{goal}", section.goal())
                .replace("{notes}", notesText)
                .replace("{authorizedUrls}", authorized)
                .replace("{priorSections}", buildPriorBlock(priorSections));
        String markdown = llm.chat(system, user);
        int unauthorized = checkSection(markdown, authorizedUrls);
        boolean retried = false;
        if (unauthorized > 0 && retryOnUnauthorized) {
            String retryUser = user + "\n\nYour previous version cited sources outside the "
                    + "authorized list. Rewrite the section citing ONLY the authorized URLs.";
            String retry = llm.chat(system, retryUser);
            int retryUnauthorized = checkSection(retry, authorizedUrls);
            if (retryUnauthorized <= unauthorized) {
                markdown = retry;
                unauthorized = retryUnauthorized;
                retried = true;
            }
        }
        return new SectionOutcome(markdown, unauthorized, retried);
    }

    /** 节级引用闸门：节内 in-text 引用 URL 必须 ∈ 节授权集（canonical 校验）。 */
    public int checkSection(String markdown, List<String> authorizedUrls) {
        CitationVerifier verifier = new CitationVerifier(authorizedUrls);
        List<String> cited = verifier.citedUrls(markdown);
        List<String> verified = verifier.verify(markdown);
        return Math.max(0, cited.size() - verified.size());
    }

    // ------------------------------------------------------------------
    // 已写节注入（2026-09-19；规格 design-prior-sections-injection-20260919.md）
    // ------------------------------------------------------------------

    /**
          * 组装注入块（五步）：拼装 → 净化（剥 URL）→ 截断 → 空判定 → 注入。
     *
     * @param priorSections 前面各节正文（调用方拼好）；null/blank 或预算 ≤0 ⇒ 返回空串
     * @return 可直接替换 {@code {priorSections}} 的整段；无前文时返回 {@code ""}
     */
    String buildPriorBlock(String priorSections) {
        if (priorSectionsMaxChars <= 0 || priorSections == null || priorSections.isBlank()) {
            return "";
        }
        try {
            String kept = truncatePrior(stripUrls(priorSections), priorSectionsMaxChars);
            if (kept.isBlank()) {
                return "";
            }
            return "The following sections of this report have ALREADY been written. Use them ONLY "
                    + "to stay consistent and avoid repetition: do NOT restate facts, examples, "
                    + "numbers or arguments already covered there, and do not repeat the same "
                    + "citation for the same fact. Do NOT open your section with a recap of the "
                    + "previous sections (no \"as discussed earlier\" preamble) — start directly "
                    + "with your own content. Their URLs were removed on purpose and their [labels] "
                    + "are NOT a formatting model: every citation YOU write must still be a full "
                    + "markdown link ([name](url)) from the authorized list below.\n"
                    + "<already_written>\n" + kept + "\n</already_written>";
        } catch (Exception e) {
                        // 注入失败不得让写作失败 ⇒ 降级为"无前文"
            LOG.warn("[section] prior-sections build failed → inject nothing: {}", e.toString());
            return "";
        }
    }

        /** 净化：剥 markdown 链接与裸 URL —— 防止模型照抄前文链接而撞上节级引用闸门。 */
    static String stripUrls(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 图片 ![alt](url) → alt
        String s = text.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        // 链接 [label](url) → [label]：**保留方括号**——剥成裸文本会让模型把"无链接"当引用范式
        // （2026-09-19 实测：两轮独立审计各发现 29 处裸标签引用退化）
        s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "[$1]");
        // 裸 URL → 删除（避免污染节级引用闸门）
        s = s.replaceAll("https?://\\S+", "");
        return s;
    }

        /** 截断：超预算时**从最早的节开始整节丢弃**（保留靠近当前的节）；并 warn。 */
    static String truncatePrior(String prior, int budget) {
        if (prior == null) {
            return "";
        }
        if (prior.length() <= budget) {
            return prior;
        }
        String[] secs = prior.split("(?m)(?=^## )");
        StringBuilder kept = new StringBuilder();
        int dropped = 0;
        for (int i = secs.length - 1; i >= 0; i--) {   // 从最后一节往前累加 = 优先保留最近的节
            String sec = secs[i];
            if (sec.isBlank()) {
                continue;
            }
            if (kept.length() + sec.length() > budget) {
                dropped++;
                continue;
            }
            kept.insert(0, sec);
        }
        LOG.warn("[section] prior truncated: keptSections={} droppedSections={} budget={}",
                secs.length - dropped, dropped, budget);
        return kept.toString();
    }

    /** 节证据渲染：insight + [quote: ≤600 节内完整] + [source: url]（节内 quote 比渲染
     *  120 长——写作需要数据细节；全文锚仍存 note.quote 供复核）。 */
    private String joinForSection(List<EvidenceNote> notes) {
        StringBuilder sb = new StringBuilder();
        for (EvidenceNote n : notes) {
            if (n.sourceUrl() == null || n.sourceUrl().isBlank()) {
                continue; // 空锚 note 不进节证据（无授权引用资格，P2-2 红线）
            }
            String line = n.insight();
            if (n.quote() != null && !n.quote().isBlank()) {
                line += " [quote: " + truncate(n.quote().trim().replaceAll("[\\[\\]]", " "),
                        WRITING.sectionQuoteChars()) + "]";
            }
            line += " [source: " + n.sourceUrl() + "]";
            String block = "- " + line + "\n";
            if (sb.length() + block.length() > sectionContextChars) {
                sb.append("- ...[证据预算截断]\n");
                break;
            }
            sb.append(block);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // merge（Key Takeaways + 机械 References）
    // ------------------------------------------------------------------

    /** 组装终稿：标题 + takeaways + 各节正文 + 机械 References。 */
    public String merge(String title, List<String> sectionMarkdowns, String takeaways) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append("# ").append(title).append("\n\n");
        }
        if (takeaways != null && !takeaways.isBlank()) {
            sb.append("## Key Takeaways\n\n").append(takeaways.trim()).append("\n\n");
        }
        for (String md : sectionMarkdowns) {
            sb.append(md.trim()).append("\n\n");
        }
        // References 机械生成：全部节正文 in-text markdown 链接（原文顺序去重）。
        // 配对扫描（URL 内括号如 wiki (deep_learning) 完整保留），与
        // CitationVerifier 扫描同构——不再用截断正则产出畸形 URL。
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (String md : sectionMarkdowns) {
            for (Link link : extractMarkdownLinks(md)) {
                refs.add("- [" + link.label() + "](" + link.url() + ")");
            }
        }
        if (!refs.isEmpty()) {
            sb.append("## References\n\n");
            for (String r : refs) {
                sb.append(r).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 提取节文本中的 markdown 链接 {@code [label](url)}（包可见：测试用）。
     *
     * <p>URL 段括号配对（与 {@link CitationVerifier#extractUrls} 同构）：URL 内含
     * {@code (deep_learning)} 时其 {@code )} 属 URL；配平后的第一个未配对 {@code )}
     * 才是链接闭合；终止字符 = 空白/引号/反引号/{@code ]} 等。仅收 http(s) 链接。
     */
    static List<Link> extractMarkdownLinks(String text) {
        List<Link> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        int n = text.length();
        for (int i = 0; i < n; ) {
            if (text.charAt(i) != '[') {
                i++;
                continue;
            }
            int close = text.indexOf(']', i + 1);
            if (close < 0 || close + 1 >= n || text.charAt(close + 1) != '(') {
                i++;
                continue;
            }
            String label = text.substring(i + 1, close).trim();
            int j = close + 2;
            int open = 0;
            while (j < n) {
                char c = text.charAt(j);
                if (c == '(') {
                    open++;
                } else if (c == ')') {
                    if (open == 0) {
                        break; // markdown 链接闭合
                    }
                    open--;
                } else if (c == ']' || c == '>' || c == '"' || c == '\'' || c == '`'
                        || Character.isWhitespace(c)) {
                    break;
                }
                j++;
            }
            String url = text.substring(close + 2, j).trim();
            boolean http = url.regionMatches(true, 0, "https://", 0, 8)
                    || url.regionMatches(true, 0, "http://", 0, 7);
            if (!label.isEmpty() && http) {
                out.add(new Link(label, url));
            }
            i = Math.max(j + 1, i + 1);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private JsonNode parseJson(String raw) {
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
                Matcher m = JSON_OBJECT.matcher(raw);
                if (m.find()) {
                    return mapper.readTree(m.group());
                }
            } catch (Exception ignored) {
                // fallthrough
            }
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).trim() + "…";
    }
}
