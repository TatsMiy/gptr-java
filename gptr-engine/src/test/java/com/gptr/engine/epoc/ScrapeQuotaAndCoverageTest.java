package com.gptr.engine.epoc;

import com.gptr.integration.client.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
  * 抓取配额（联动公式 + 组轮转）与维度覆盖（机械下界）。
 *
 * <p>覆盖的判定逻辑全部机械：配额是纯函数、轮转是纯函数、维度"是否有查询"由结构回答。
 * 这三处正是"名额被前几条查询吃满、覆盖判定随机"的修复点（抓取名额被前几条查询吃满、覆盖判定随机）。
 */
class ScrapeQuotaAndCoverageTest {

    // ---------------------------------------------------------------
    // A. 抓取配额：「配额公式边界」
    // ---------------------------------------------------------------

    @Test
    void flatQuotaKeepsCurrentBehaviour() {
        DeepResearchGraph.ScrapeQuota q = DeepResearchGraph.ScrapeQuota.flat(8);
        assertFalse(q.isLinked());
        for (int groups : new int[] {1, 3, 6, 20}) {
            assertEquals(8, q.quotaFor(groups), "flat 档与组数无关（现状语义）");
        }
    }

    @Test
    void linkedQuotaClampsBetweenMinAndHardCap() {
        DeepResearchGraph.ScrapeQuota q = DeepResearchGraph.ScrapeQuota.linked(2, 8, 16);
        assertTrue(q.isLinked());
        assertEquals(8, q.quotaFor(3), "2×3=6 → 保底 8（与现状一致，成本不降不升）");
        assertEquals(8, q.quotaFor(4), "2×4=8 → 命中");
        assertEquals(10, q.quotaFor(5));
        assertEquals(12, q.quotaFor(6), "legacy 补查到 6 组时的配额");
        assertEquals(16, q.quotaFor(9), "2×9=18 → 封顶 16");
        assertEquals(16, q.quotaFor(50), "极端组数仍封顶");
        assertEquals(8, q.quotaFor(0), "组数为 0 → 不崩，取下界");
    }

    // ---------------------------------------------------------------
    // 组轮转：「公平性 + visited 让位 + 跨组去重」
    // ---------------------------------------------------------------

    private static String item(String url) {
        return "Title: t\nURL: " + url + "\nSnippet: s";
    }

    private static List<String> group(String... urls) {
        return java.util.Arrays.stream(urls).map(ScrapeQuotaAndCoverageTest::item).toList();
    }

    @Test
    void roundRobinGivesEveryGroupAShare() {
        // 3 组，组 0 有 5 个 URL（现状下会吃满 8 个名额），组 1/2 各 2 个
        List<List<String>> groups = List.of(
                group("u0a", "u0b", "u0c", "u0d", "u0e"),
                group("u1a", "u1b"),
                group("u2a", "u2b"));
        List<Map.Entry<String, Integer>> picked =
                ScrapeQuotaScheduler.pickRoundRobin(groups, List.of(), 6);
        assertEquals(6, picked.size());
        assertEquals("0:2 1:2 2:2", ScrapeQuotaScheduler.groupDistribution(picked),
                "轮转后每组各 2 个（现状会变成 0:5 1:1 2:0 之类）");
        assertEquals("u0a", picked.get(0).getKey(), "组内保持 search 原序");
        assertEquals(1, picked.get(1).getValue(), "第二个名额给组 1");
    }

    @Test
    void roundRobinSkipsVisitedAndLetsOthersTakeTheQuota() {
        List<List<String>> groups = List.of(
                group("u0a", "u0b"),
                group("u1a"),
                group("u2a"));
        // 组 0 已被抓过 → 名额必须让给组 1/2，而不是留空
        List<Map.Entry<String, Integer>> picked =
                ScrapeQuotaScheduler.pickRoundRobin(groups, List.of("u0a", "u0b"), 3);
        assertEquals(2, picked.size(), "组 0 全 visited → 只取到组 1/2 的 2 个");
        assertEquals("1:1 2:1", ScrapeQuotaScheduler.groupDistribution(picked),
                "分布只统计实际入选的组（组 0 无 URL 可入选）");
    }

    @Test
    void roundRobinDeduplicatesUrlsSharedAcrossGroups() {
        // 同一 URL 出现在组 0 与组 2（DDG 对相近查询常返回同页）
        List<List<String>> groups = List.of(
                group("shared", "u0b"),
                group("u1a"),
                group("shared", "u2b"));
        List<Map.Entry<String, Integer>> picked =
                ScrapeQuotaScheduler.pickRoundRobin(groups, List.of(), 4);
        long distinct = picked.stream().map(Map.Entry::getKey).distinct().count();
        assertEquals(picked.size(), distinct, "同一 URL 只抓一次");
        assertEquals(4, picked.size(), "重复被跳过但配额仍由后续候选填满");
    }

    @Test
    void roundRobinDedupesSameArticleMirrors() {
        // q08 实测形态：组 0 的五条命中里三条是**同一篇 163 文章**的不同入口
        // （共享文号 KN6E4VRB0511AQHO，见 SourceMirror）。此前它们各占一个名额，
        // 导致该文号在素材中产出 40 条重复 note；去重后只占 1 个名额，省下的名额顺延给
        // 组内真实不同的来源。
        List<List<String>> groups = List.of(
                group("https://c.m.163.com/news/a/KN6E4VRB0511AQHO.html",
                        "https://www.163.com/dy/article/KN6E4VRB0511AQHO.html",
                        "https://m.163.com/dy/article/KN6E4VRB0511AQHO.html",
                        "https://news.qq.com/rain/a/20260304A05I4H00",
                        "https://www.toutiao.com/article/7613288452485939738/"),
                group("https://arxiv.org/abs/2602.04449",
                        "https://hub.baai.ac.cn/paper/a21cbb98-24de-4d7b-9b66-080ad1827564",
                        "https://blog.csdn.net/weixin_55357163/article/details/161971743",
                        "https://juejin.cn/post/7650719111397261354"));
        List<Map.Entry<String, Integer>> picked =
                ScrapeQuotaScheduler.pickRoundRobin(groups, List.of(), 4);
        assertEquals(4, picked.size());
        List<String> urls = picked.stream().map(Map.Entry::getKey).toList();
        assertEquals(1, urls.stream().filter(u -> u.contains("KN6E4VRB0511AQHO")).count(),
                "同一篇文章的多个入口只占 1 个名额: " + urls);
        assertTrue(urls.contains("https://news.qq.com/rain/a/20260304A05I4H00"),
                "组 0 的第二个名额顺延给真实来源，而不是第二个镜像: " + urls);
        assertEquals("0:2 1:2", ScrapeQuotaScheduler.groupDistribution(picked));
    }

    @Test
    void roundRobinSkipsMirrorOfVisitedUrl() {
        // 前层抓过该 163 文章的任一入口 → 本层另两个镜像都不应再占名额（跨层防重）
        List<List<String>> groups = List.of(
                group("https://c.m.163.com/news/a/KN6E4VRB0511AQHO.html",
                        "https://news.qq.com/rain/a/20260304A05I4H00"));
        List<Map.Entry<String, Integer>> picked = ScrapeQuotaScheduler.pickRoundRobin(groups,
                List.of("https://m.163.com/dy/article/KN6E4VRB0511AQHO.html"), 2);
        assertEquals(List.of("https://news.qq.com/rain/a/20260304A05I4H00"),
                picked.stream().map(Map.Entry::getKey).toList(),
                "同文章的另一个入口视为已抓，名额让给真实新来源");
    }

    @Test
    void sequentialKeepsLegacyOrderAndCap() {
        List<String> candidates = List.of("a", "b", "b", "c", "d");
        List<Map.Entry<String, Integer>> picked =
                ScrapeQuotaScheduler.pickSequential(candidates, List.of("b"), 3);
        assertEquals(List.of("a", "c", "d"), picked.stream().map(Map.Entry::getKey).toList(),
                "visited 过滤 + 去重 + 取前 3");
        assertEquals("flat:3", ScrapeQuotaScheduler.groupDistribution(picked));
    }

    // ---------------------------------------------------------------
    // B. 维度覆盖：「解析 + 机械下界」
    // ---------------------------------------------------------------

    @Test
    void parseDimensionsFlattensAndKeepsOrder() {
        String raw = "{\"researchGoal\":\"g\",\"dimensions\":["
                + "{\"dimension\":\"基准成绩\",\"queries\":[\"q1\",\"q2\"]},"
                + "{\"dimension\":\"成本效率\",\"queries\":[\"q3\"]}]}";
        DeepResearchPrompts.DimensionPlan plan = DeepResearchPrompts.parseDimensions(raw, 12);
        assertFalse(plan.isEmpty());
        assertEquals(2, plan.dimensions().size());
        assertEquals(List.of("q1", "q2", "q3"), plan.queries(), "展平保序（组序即维度序）");
        assertEquals("成本效率", plan.dimensions().get(1).name());
        assertTrue(DeepResearchPrompts.dimensionsWithoutQueries(plan).isEmpty(),
                "每维度都有查询 → 无缺口（不触发补齐调用）");
    }

    @Test
    void parseDimensionsDetectsDimensionWithoutQueries() {
        String raw = "{\"dimensions\":["
                + "{\"dimension\":\"基准成绩\",\"queries\":[\"q1\"]},"
                + "{\"dimension\":\"安全与幻觉\",\"queries\":[]},"
                + "\"只给名字的维度\"]}";
        DeepResearchPrompts.DimensionPlan plan = DeepResearchPrompts.parseDimensions(raw, 12);
        assertEquals(3, plan.dimensions().size());
        assertEquals(List.of("安全与幻觉", "只给名字的维度"),
                DeepResearchPrompts.dimensionsWithoutQueries(plan),
                "空查询维度 = 结构性缺口（机械可判，非 LLM 自证）");
        assertEquals(List.of("q1"), plan.queries());
    }

    @Test
    void parseDimensionsRejectsLegacySchema() {
        String legacy = "{\"queries\":[{\"query\":\"q1\"}],\"uncoveredDimensions\":[\"d\"]}";
        assertTrue(DeepResearchPrompts.parseDimensions(legacy, 12).isEmpty(),
                "旧 schema → 空 plan（调用方回退 legacy 路径）");
        assertTrue(DeepResearchPrompts.parseDimensions("not json", 12).isEmpty());
        assertTrue(DeepResearchPrompts.parseDimensions("[{\"query\":\"q\"}]", 12).isEmpty(),
                "旧裸数组 → 空");
    }

    @Test
    void dimensionJsonRoundTripsForStateKey() throws Exception {
        String json = DeepResearchPrompts.dimensionToJson("成本效率", List.of("q1", "q2"));
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertEquals("成本效率", node.path("dimension").asText());
        assertEquals(2, node.path("queries").size());
        assertEquals("q1", node.path("queries").get(0).asText());
    }

    // ---------------------------------------------------------------
    // 端到端：机械下界决定是否补齐（mock LLM 两次调用）
    // ---------------------------------------------------------------

    /** 第 1 次返回 plan，第 2 次（补齐）返回 fill；记录调用次数与第 2 次入参。 */
    private static final class ScriptedLlm implements LlmClient {
        private final String first;
        private final String second;
        int calls = 0;
        String secondUser = "";

        ScriptedLlm(String first, String second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public String chat(String system, String user) {
            calls++;
            if (calls == 1) {
                return first;
            }
            secondUser = user;
            return second;
        }

        @Override
        public String chatJson(String system, String user) {
            return chat(system, user);
        }

        @Override
        public double lastCallCostUsd() {
            return 0;
        }
    }

    private static DeepResearchState state(int breadth) {
        return new DeepResearchState(new java.util.HashMap<>(
                Map.of("query", "测试问题", "breadth", breadth)));
    }

    @SuppressWarnings("unchecked")
    private static List<String> queriesOf(Map<String, Object> updates) {
        return (List<String>) updates.get("queries");
    }

    @Test
    void dimensionsModeDoesNotCallFillWhenEveryDimensionCovered() {
        String plan = "{\"dimensions\":[{\"dimension\":\"A\",\"queries\":[\"q1\",\"q2\"]},"
                + "{\"dimension\":\"B\",\"queries\":[\"q3\"]}]}";
        ScriptedLlm llm = new ScriptedLlm(plan, "{\"queries\":[{\"query\":\"unused\"}]}");
        Map<String, Object> out = GenerateQueriesNode.runGenerateQueries(state(3), llm, c -> { }, "dimensions");
        assertEquals(1, llm.calls, "每维度都有查询且总数 ≥ breadth → 不补齐（省一次调用）");
        assertEquals(List.of("q1", "q2", "q3"), queriesOf(out));
        assertEquals(2, ((List<String>) out.get("dimensions")).size(), "维度清单入状态键");
    }

    @Test
    void dimensionsModeFillsMissingDimensionWithOneCall() {
        String plan = "{\"dimensions\":[{\"dimension\":\"A\",\"queries\":[\"q1\"]},"
                + "{\"dimension\":\"安全与幻觉\",\"queries\":[]}]}";
        ScriptedLlm llm = new ScriptedLlm(plan,
                "{\"queries\":[{\"query\":\"q2\"},{\"query\":\"q3\"}]}");
        Map<String, Object> out = GenerateQueriesNode.runGenerateQueries(state(3), llm, c -> { }, "dimensions");
        assertEquals(2, llm.calls, "有维度缺查询 → 恰好补一次");
        assertTrue(llm.secondUser.contains("安全与幻觉"), "补齐入参必须点名缺失维度");
        assertEquals(List.of("q1", "q2", "q3"), queriesOf(out), "补齐查询并入列表");
    }

    @Test
    void dimensionsModeFillsWhenTotalBelowBreadth() {
        // 每维度都有查询，但总数 2 < breadth 3 → 仍补（数量下界）
        String plan = "{\"dimensions\":[{\"dimension\":\"A\",\"queries\":[\"q1\"]},"
                + "{\"dimension\":\"B\",\"queries\":[\"q2\"]}]}";
        ScriptedLlm llm = new ScriptedLlm(plan, "{\"queries\":[{\"query\":\"q3\"}]}");
        Map<String, Object> out = GenerateQueriesNode.runGenerateQueries(state(3), llm, c -> { }, "dimensions");
        assertEquals(2, llm.calls, "总数不足 breadth → 补一次");
        assertEquals(3, queriesOf(out).size());
    }

    @Test
    void parseDimensionsCapsTotalQueries() {
        // 实测失控点：prompt 只说"至少 {numQueries}" → 模型给出 4 维度 × 3 查询 = 12 条，
        // 检索成本约翻 4 倍。上限 = max(2×breadth, 4) 强制收敛（展平时截断，保留维度声明）。
        String many = "{\"dimensions\":["
                + "{\"dimension\":\"A\",\"queries\":[\"a1\",\"a2\",\"a3\"]},"
                + "{\"dimension\":\"B\",\"queries\":[\"b1\",\"b2\",\"b3\"]},"
                + "{\"dimension\":\"C\",\"queries\":[\"c1\",\"c2\",\"c3\"]},"
                + "{\"dimension\":\"D\",\"queries\":[\"d1\",\"d2\",\"d3\"]}]}";
        DeepResearchPrompts.DimensionPlan capped = DeepResearchPrompts.parseDimensions(many, 6);
        assertEquals(6, capped.queries().size(), "总数被截到上限");
        assertEquals(4, capped.dimensions().size(), "维度声明保留（供大纲使用）");

        // 端到端：breadth=3 → 上限 6，模型给 8 条也只收 6 条
        String plan = "{\"dimensions\":[{\"dimension\":\"A\",\"queries\":[\"q1\",\"q2\",\"q3\",\"q4\"]},"
                + "{\"dimension\":\"B\",\"queries\":[\"q5\",\"q6\",\"q7\",\"q8\"]}]}";
        ScriptedLlm llm = new ScriptedLlm(plan, "{}");
        Map<String, Object> out = GenerateQueriesNode.runGenerateQueries(state(3), llm, c -> { }, "dimensions");
        assertEquals(1, llm.calls, "已满足下界 → 不再补调用");
        assertEquals(6, queriesOf(out).size(), "上限 2×breadth=6");
    }

    @Test
    void legacyAndOffModesKeepOldPromptsAndMakeOneCall() {
        String legacyRaw = "{\"queries\":[{\"query\":\"q1\"},{\"query\":\"q2\"},"
                + "{\"query\":\"q3\"}],\"uncoveredDimensions\":[]}";
        ScriptedLlm legacy = new ScriptedLlm(legacyRaw, "{}");
        Map<String, Object> outLegacy = GenerateQueriesNode.runGenerateQueries(state(3), legacy, c -> { }, "legacy");
        assertEquals(1, legacy.calls, "自检报全覆盖 → 不补查");
        assertEquals(3, queriesOf(outLegacy).size());
        assertFalse(outLegacy.containsKey("dimensions"), "legacy 不产出维度清单");

        ScriptedLlm off = new ScriptedLlm(legacyRaw, "{}");
        Map<String, Object> outOff = GenerateQueriesNode.runGenerateQueries(state(3), off, c -> { }, "off");
        assertEquals(1, off.calls, "off 档不补查（初始行为）");
        assertEquals(3, queriesOf(outOff).size());
    }

    @Test
    void legacyFallbackWhenDimensionsSchemaUnparsable() {
        // 模型没按新 schema 输出（返回旧格式）→ 回退 legacy 解析，不让覆盖机制阻断主流程
        String legacyRaw = "{\"queries\":[{\"query\":\"q1\"},{\"query\":\"q2\"},"
                + "{\"query\":\"q3\"}],\"uncoveredDimensions\":[]}";
        ScriptedLlm llm = new ScriptedLlm(legacyRaw, legacyRaw);
        Map<String, Object> out = GenerateQueriesNode.runGenerateQueries(state(3), llm, c -> { }, "dimensions");
        assertEquals(List.of("q1", "q2", "q3"), queriesOf(out), "回退后仍拿到查询");
        assertFalse(out.containsKey("dimensions"), "回退路径无维度清单");
    }

    // ---------------------------------------------------------------
    // 配置键解析见 com.gptr.engine.CoverageConfigKeysTest（EngineConfig 为包私有）
    // ---------------------------------------------------------------
}
