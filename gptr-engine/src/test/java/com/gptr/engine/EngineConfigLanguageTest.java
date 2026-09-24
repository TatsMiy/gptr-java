package com.gptr.engine;

import com.gptr.engine.epoc.DeepResearchPrompts;
import com.gptr.engine.write.SectionWriter;
import com.gptr.integration.client.LlmClient;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎配置：研报语言归一化 + sourceDistill/distillMaxChars 键解析与默认值
 * + **报告语言由配置驱动的机械判据**。
 */
class EngineConfigLanguageTest {

    @Test
    void languageNormalization() {
        assertEquals("中文", EngineConfig.normalizeLanguage("zh"));
        assertEquals("中文", EngineConfig.normalizeLanguage("zh-CN"));
        assertEquals("中文", EngineConfig.normalizeLanguage("中文"));
        assertEquals("English", EngineConfig.normalizeLanguage("en"));
        assertEquals("English", EngineConfig.normalizeLanguage("english"));
        assertEquals("English", EngineConfig.normalizeLanguage("英文"));
        assertEquals("日本語", EngineConfig.normalizeLanguage("日本語"), "未知名语言透传");
    }

    @Test
    void defaultsKeepChineseAndTruncationBaseline() {
        EngineConfig cfg = new EngineConfig("{}");
        assertEquals("中文", cfg.language, "默认中文");
        assertFalse(cfg.sourceDistill, "默认关（截断兜底）");
        assertEquals(20000, cfg.distillMaxChars);
        assertTrue(cfg.sectionWriting, "逐节写作默认 true（A/B 通过后翻转）");
    }

    @Test
    void parsesLanguageAndDistillKeys() {
        EngineConfig cfg = new EngineConfig(
                "{\"language\":\"en\",\"sourceDistill\":true,\"distillMaxChars\":30000}");
        assertEquals("English", cfg.language);
        assertTrue(cfg.sourceDistill);
        assertEquals(30000, cfg.distillMaxChars);
    }

    // ------------------------------------------------------------------
    // 报告语言由配置驱动：进报告的文本必须受 language 约束，中间产物不受
    // ------------------------------------------------------------------

    /** 判据：**进报告的文本**所对应的 prompt 必须带 {@code {language}} 占位符。 */
    @Test
    void reportPromptsCarryLanguagePlaceholder() {
        for (String key : List.of("report-section.system", "report-outline.system",
                "report-takeaways.user", "plan-reflect.user")) {
            assertTrue(DeepResearchPrompts.get(key).contains("{language}"),
                    key + " 必须含 {language} 占位符，否则该段文本的语言不受配置驱动");
        }
        assertFalse(DeepResearchPrompts.get("plan-reflect.user").contains("in Chinese"),
                "不得写死中文倾向 —— 那会让 query 语言压过配置");
    }

    /** 判据：配置值真的被替换进去（用 mock 捕获**实际发出**的 prompt）。 */
    @Test
    void outlinePromptInjectsConfiguredLanguage() {
        List<String> captured = new ArrayList<>();
        LlmClient capture = new LlmClient() {
            @Override
            public String name() {
                return "capture";
            }

            @Override
            public String chat(String systemPrompt, String userPrompt) {
                captured.add(systemPrompt);
                return "ok";
            }

            @Override
            public String chatJson(String systemPrompt, String userPrompt) {
                captured.add(systemPrompt);
                return "{}";
            }
        };
        // 必须用**生产构造器**（6 参）—— 短构造器是测试便利通道，其语言是夹具
        new SectionWriter(capture, 6000, false, 6, 0, "English")
                .writeOutline("q", "s", List.of("sub"));
        assertFalse(captured.isEmpty(), "大纲应发出 prompt");
        assertTrue(captured.get(0).contains("in English"),
                "配置的 language 必须替换进 prompt，实际: " + captured.get(0));
        assertFalse(captured.get(0).contains("{language}"), "占位符不得残留");
    }

    /**
     * 边界：{@code language} **不约束中间产物** ——
     * 检索查询必须跟随源材料语言（否则召回崩），提炼/评分的 quote 必须逐字。
     */
    @Test
    void intermediatePromptsAreNotLanguageBound() {
        for (String key : List.of("generate-search-queries.user", "generate-followup-queries.user",
                "process-results.user", "curate-sources.user", "source-distill.user")) {
            assertFalse(DeepResearchPrompts.get(key).contains("{language}"),
                    key + " 是中间产物：语言须跟随源材料，绑定报告语言会损害召回与 quote 保真");
        }
    }
}
