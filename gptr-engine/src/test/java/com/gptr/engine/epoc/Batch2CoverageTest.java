package com.gptr.engine.epoc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gptr.integration.client.LlmClient;
import com.gptr.engine.write.SectionWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批 2：覆盖自检解析 / 缺口驱动数量 / maxSections 参数化（纯函数级）。
 */
class Batch2CoverageTest {

    private static final ObjectMapper M = new ObjectMapper();

    // ---------------------------------------------------------------
    // 解析：新 schema + 旧格式兼容
    // ---------------------------------------------------------------

    @Test
    void parseQuerySpecsNewSchema() {
        String raw = "{\"queries\":[{\"query\":\"q1\",\"researchGoal\":\"g1\","
                + "\"targetedDimension\":\"教学机制\"},{\"query\":\"q2\"}],"
                + "\"uncoveredDimensions\":[\"X 演进的未来方向\"]}";
        List<DeepResearchPrompts.QuerySpec> specs = DeepResearchPrompts.parseQuerySpecs(raw, 3);
        assertEquals(2, specs.size());
        assertEquals("q1", specs.get(0).query());
        assertEquals("教学机制", specs.get(0).targetedDimension());
        assertEquals("", specs.get(1).targetedDimension(), "缺字段容错为空");
        assertEquals(List.of("X 演进的未来方向"),
                DeepResearchPrompts.parseUncoveredDimensions(raw));
    }

    @Test
    void parseQuerySpecsLegacyArrayCompatible() {
        String legacy = "[{\"query\":\"q1\",\"researchGoal\":\"g1\"},"
                + "{\"query\":\"q2\",\"researchGoal\":\"g2\"}]";
        List<DeepResearchPrompts.QuerySpec> specs = DeepResearchPrompts.parseQuerySpecs(legacy, 5);
        assertEquals(2, specs.size(), "旧裸数组 schema 兼容");
        assertEquals("q1", specs.get(0).query());
        assertTrue(DeepResearchPrompts.parseUncoveredDimensions(legacy).isEmpty(),
                "旧 schema 无 uncoveredDimensions → 空（不触发补查）");
    }

    @Test
    void parseQuerySpecsBadAndCap() {
        assertEquals(0, DeepResearchPrompts.parseQuerySpecs("not json", 3).size());
        assertEquals(0, DeepResearchPrompts.parseQuerySpecs("{\"queries\":[]}", 3).size());
        String many = "{\"queries\":[{\"query\":\"a\"},{\"query\":\"b\"},{\"query\":\"c\"}]}";
        assertEquals(2, DeepResearchPrompts.parseQuerySpecs(many, 2).size(), "上限裁剪");
    }

    @Test
    void parseUncoveredEmptyArrayIsValidSignal() {
        assertEquals(List.of(), DeepResearchPrompts.parseUncoveredDimensions(
                "{\"queries\":[{\"query\":\"q\"}],\"uncoveredDimensions\":[]}"),
                "空数组 = 自检全覆盖（有效信号，不触发补查）");
        assertEquals(2, DeepResearchPrompts.parseUncoveredDimensions(
                        "{\"uncoveredDimensions\":[\"a\",\"  \",\"b\"]}").size(),
                "空白项剔除");
    }

    @Test
    void parseGapsFromPlanReflect() {
        String raw = "{\"covered\":\"已覆盖 A/B\",\"gaps\":[\"为什么 X 会失效？\",\"  \",\"Y 的成本是多少？\"]}";
        List<String> gaps = DeepResearchPrompts.parseGaps(raw);
        assertEquals(2, gaps.size());
        assertEquals("为什么 X 会失效？", gaps.get(0));
        assertTrue(DeepResearchPrompts.parseGaps("{\"covered\":\"x\"}").isEmpty(), "无 gaps 字段 → 空");
        assertTrue(DeepResearchPrompts.parseGaps("旧纯文本状态").isEmpty(), "旧纯文本 → 空（回退旧公式）");
    }

    // ---------------------------------------------------------------
    // 缺口驱动数量：缺口为下限、衰减为上限、无缺口回退旧公式
    // ---------------------------------------------------------------

    @Test
    void followUpCountGapDrivenWithinDecayCap() {
        List<String> gaps3 = List.of("g1", "g2", "g3");
        // breadth=3, decay=0.5, layer=1 → cap=ceil(1.5)=2 → clamp(3,1,2)=2
        assertEquals(2, FollowUpNode.followUpCount(3, 0.5, 1, gaps3));
        // layer=2 → cap=ceil(0.75)=1 → 1
        assertEquals(1, FollowUpNode.followUpCount(3, 0.5, 2, gaps3));
        // 单缺口 → 下限 1
        assertEquals(1, FollowUpNode.followUpCount(3, 0.5, 1, List.of("g1")));
        // 大 breadth 高层仍受衰减约束而非缺口数
        assertEquals(4, FollowUpNode.followUpCount(8, 0.5, 1, List.of("a", "b", "c", "d", "e")));
    }

    @Test
    void followUpCountFallsBackWithoutGaps() {
        assertEquals(2, FollowUpNode.followUpCount(3, 0.5, 1, List.of()), "无缺口 → 旧公式");
        assertEquals(2, FollowUpNode.followUpCount(4, 0.5, 1, null), "null → 旧公式");
        // 旧公式 = max(2, ceil(breadth×decay))：breadth=2/decay=0.5 → ceil(1)=1 → 下限抬到 2
        assertEquals(2, FollowUpNode.followUpCount(2, 0.5, 1, List.of()),
                "旧公式下限 2（与实现 max(2,...) 一致）");
    }

    // ---------------------------------------------------------------
    // maxSections：prompt 注入 + 解析 cap 同源
    // ---------------------------------------------------------------

    private static String outlineJson(int n) {
        ObjectNode root = M.createObjectNode();
        root.put("title", "T");
        ArrayNode arr = root.putArray("sections");
        for (int i = 0; i < n; i++) {
            ObjectNode s = arr.addObject();
            s.put("title", "节" + i);
            s.put("goal", "g" + i);
        }
        return root.toString();
    }

    private static LlmClient fixed(String json) {
        return new LlmClient() {
            @Override
            public String name() {
                return "fixed";
            }

            @Override
            public String chat(String system, String user) {
                return json;
            }

            @Override
            public String chatJson(String system, String user) {
                return json;
            }

            @Override
            public double lastCallCostUsd() {
                return 0;
            }
        };
    }

    @Test
    void maxSectionsControlsPromptAndParseCap() {
        // maxSections=8 + LLM 返回 8 节 → 全部保留（旧默认 6 会截掉 2 节）
        SectionWriter w8 = new SectionWriter(fixed(outlineJson(8)), 6000, false, 8);
        SectionWriter.OutlineResult r8 = w8.writeOutline("q", "state",
                List.of("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8"));
        assertEquals(8, r8.sections().size(), "maxSections=8 保留 8 节");

        // 默认（6）→ cap 6
        SectionWriter w6 = new SectionWriter(fixed(outlineJson(8)), 6000, false);
        SectionWriter.OutlineResult r6 = w6.writeOutline("q", "state", List.of("s1"));
        assertEquals(6, r6.sections().size(), "默认 cap 6");

        // prompt 注入 maxSections（user 含 "at most 8"）
        final String[] seen = {null};
        LlmClient spy = new LlmClient() {
            @Override
            public String name() {
                return "spy";
            }

            @Override
            public String chat(String system, String user) {
                seen[0] = user;
                return outlineJson(2);
            }

            @Override
            public String chatJson(String system, String user) {
                return chat(system, user);
            }

            @Override
            public double lastCallCostUsd() {
                return 0;
            }
        };
        new SectionWriter(spy, 6000, false, 9).writeOutline("q", "s", List.of("a"));
        assertTrue(seen[0] != null && seen[0].contains("at most 9 sections"),
                "prompt 注入 maxSections： " + seen[0]);
        assertFalse(seen[0].contains("{maxSections}"), "占位符必须被替换");
    }
}
