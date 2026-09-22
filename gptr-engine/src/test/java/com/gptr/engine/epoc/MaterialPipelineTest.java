package com.gptr.engine.epoc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.engine.budget.Budgets;
import com.gptr.engine.epoc.EvidenceNote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 素材增强：蒸馏保真前检 + [DISTILLED] 块直通解析（Y 臂核心，纯函数级）。
 */
class MaterialPipelineTest {

    // ---------------------------------------------------------------
    // 保真前检 containsNormalized（与 ReportText.containsNormalized 同语义）
    // ---------------------------------------------------------------

    @Test
    void verbatimSentencePasses() {
        String page = "DeepSeek-R1 的推理模式在数学任务上提升显著，但代价是延迟增加约 40%。"
                + "Benchmark 显示 AIME 2024 从 39.2% 提升到 79.8%。";
        assertTrue(EvidenceText.containsNormalized(page, "DeepSeek-R1 的推理模式在数学任务上提升显著"),
                "原文原句应通过");
        assertTrue(EvidenceText.containsNormalized(page,
                        "AIME 2024 从 39.2% 提升到 79.8%"),
                "跨标点归一化也应通过（句内截断子串）");
    }

    @Test
    void paraphrasedSentenceRejected() {
        String page = "The model achieves 79.8% on AIME 2024, up from 39.2%.";
        // 改写：换词（achieves → gets）+ 丢数字精度
        assertFalse(EvidenceText.containsNormalized(page, "The model gets around 80% on AIME"),
                "改写句必须被前检拒绝");
        assertFalse(EvidenceText.containsNormalized(page, "模型在 AIME 上得分提升"),
                "语言翻译改写必须被拒绝");
    }

    @Test
    void unicodePunctuationDoesNotBreakVerbatimMatch() {
        // 回归（实测翻车）：网页原文用弯引号/弯撇号（U+2018/2019/201C/201D），LLM 输出转 ASCII。
        // (?U) 前归一化必须把两类标点都去掉，否则逐字摘句被误判为改写（蒸馏全灭根因）。
        String page = "DeepSeek\u2019s R1 \u201cmatches\u201d OpenAI o1 on math, but it\u2019s cheaper.";
        assertTrue(EvidenceText.containsNormalized(page, "DeepSeek's R1 \"matches\" OpenAI o1 on math"),
                "弯撇号/弯引号 vs ASCII 不得影响逐字匹配");
        String zh = "模型宣称\u201c大幅提升\u201d，但延迟增加约40%。";
        assertTrue(EvidenceText.containsNormalized(zh, "模型宣称“大幅提升”，但延迟增加约40%"),
                "中文弯引号归一化后应匹配");
    }

    @Test
    void realWorldOpenR1SentencesMatchSource() {
        // 复现（B 臂实测 kept=0 全灭）：同输入手动链路 20/20 过、引擎 0——此处用与真实页面
        // 同构的 source 与 LLM 摘句验证 Java 归一化匹配。
        String source = "Open-R1: a fully open reproduction of DeepSeek-R1\n"
                + "Back to Articles\n"
                + "Open-R1: a fully open reproduction of DeepSeek-R1\n"
                + "Published January 28, 2025\n"
                + "Today, we are excited to announce the Open R1 project, an open-source effort "
                + "to reproduce the R1 pipeline.\n"
                + "The project has three main goals: 1) distill the knowledge of DeepSeek-R1 "
                + "into open models, 2) replicate the pure RL pipeline, and 3) show the transition "
                + "from base model to SFT to RL via multi-stage training.\n"
                + "OpenAI\u2019s o1 model showed that when LLMs are trained to do the same\u2014"
                + "by using more compute during inference\u2014they get significantly better "
                + "at complex tasks.";
        assertTrue(EvidenceText.containsNormalized(source,
                        "Today, we are excited to announce the Open R1 project, an open-source effort "
                                + "to reproduce the R1 pipeline."),
                "真实句 1 应匹配");
        assertTrue(EvidenceText.containsNormalized(source,
                        "OpenAI\u2019s o1 model showed that when LLMs are trained to do the same\u2014"
                                + "by using more compute during inference\u2014they get significantly better "
                                + "at complex tasks"),
                "带弯撇号/em-dash 句应匹配（(?U) 移除 U+2019/U+2014）");
    }

    // ---------------------------------------------------------------
    // [DISTILLED] 块直通解析（Y 臂：Java 程序化注入 URL，不经 LLM）
    // ---------------------------------------------------------------

    @Test
    void distilledBlockParsesToNotesWithInjectedUrl() {
        String block = EvidenceText.DISTILLED_MARKER + "https://example.com/page1\n"
                + "- 逐字原句甲，包含机制因果与数字 40%。\n"
                + "- 逐字原句乙，说明训练细节与局限边界，含多个限定词表述。\n";
        List<EvidenceNote.Raw> notes = EvidenceText.parseDistilledBlock(block);
        assertEquals(2, notes.size());
        assertEquals("逐字原句甲，包含机制因果与数字 40%。", notes.get(0).insight());
        assertEquals("https://example.com/page1", notes.get(0).sourceUrl(), "URL 由 Java 注入");
        assertEquals("", notes.get(0).quote(), "直通 note 无独立 quote（insight 即原句）");
        assertEquals("逐字原句乙，说明训练细节与局限边界，含多个限定词表述。", notes.get(1).insight());
    }

    @Test
    void distilledBlockWithoutUrlHeaderYieldsNothing() {
        String block = "- 无 URL 头的孤立句，即使长度足够也不得直通（防孤儿）。\n";
        List<EvidenceNote.Raw> notes = EvidenceText.parseDistilledBlock(block);
        assertTrue(notes.isEmpty(), "无 URL 锚的块不直通");
    }

    @Test
    void shortFragmentFiltered() {
        String block = EvidenceText.DISTILLED_MARKER + "https://example.com/p\n"
                + "- 短碎片。\n"            // <20 字符应被过滤
                + "- 这是一条长度足够的原文句子，用于验证过滤边界是否工作正常。\n";
        List<EvidenceNote.Raw> notes = EvidenceText.parseDistilledBlock(block);
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).insight().length() >= 20);
    }

    @Test
    void sentenceCountHelper() {
        String block = EvidenceText.DISTILLED_MARKER + "https://e.com/p\n"
                + "- 句一。\n- 句二。\n前言不搭后语的行\n";
        assertEquals(2, EvidenceText.sentencesIn(block));
    }

    @Test
    void yArmDistilledDirectDedupAcrossGroups() throws Exception {
        // 实测驱动（q04-Y 单 URL max 248 条）：同 URL 蒸馏块经 appendToGroupsReferencing
        // 进多个查询组 → Y 臂每组件都直通同一批句 → 跨组重复。页级蒸馏无查询视角，跨组复制
        // 零信息增益——合并处 (sourceUrl, insight) 去重，保留首组归属。
        String url = "https://example.com/p1";
        String block = EvidenceText.DISTILLED_MARKER + url + "\n"
                + "- 蒸馏句甲内容足够长超过二十字符并且包含实质信息。\n"
                + "- 蒸馏句乙内容同样足够长用于验证跨组去重逻辑是否生效。\n";
        // 两个查询组都引用该 URL（各自含同一蒸馏块）；同组内再放一次重复块（组内去重同路径）
        List<List<String>> items = List.of(List.of(block), List.of(block, block));
        DeepResearchState state = new DeepResearchState(new java.util.HashMap<>(java.util.Map.of(
                "queries", java.util.List.of("queryA", "queryB"),
                "queryItems", items,
                "collectedUrls", java.util.List.of(url),
                "currentDepth", 0)));
        // 本用例走 [DISTILLED] 直通（不进 LLM），joinCap 不参与该路径，
        // 故直接用出厂预算即可（原第三参为字面量 10000）
        java.util.Map<String, Object> updates = ExtractNode.runPerQueryExtract(state, null, cost -> { },
                Budgets.defaults().extraction(), false);
        @SuppressWarnings("unchecked")
        java.util.List<String> bank = (java.util.List<String>) updates.get("evidenceBank");
        assertEquals(2, bank.size(), "双组×重复块直通应去重为 2 句（原 6）");
        assertEquals(2, ((Number) updates.get("roundLearnings")).intValue(),
                "守卫计数=去重后实际新增");
        String q0 = new ObjectMapper().readTree(bank.get(0)).path("queryText").asText("");
        assertEquals("queryA", q0, "保留首组归属");
    }
}
