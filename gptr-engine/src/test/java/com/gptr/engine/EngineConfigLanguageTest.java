package com.gptr.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎配置：研报语言归一化 + sourceDistill/distillMaxChars 键解析与默认值。
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
}
