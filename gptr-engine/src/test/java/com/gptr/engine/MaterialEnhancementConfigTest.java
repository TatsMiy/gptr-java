package com.gptr.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 素材增强新键：默认值纪律 + JSON 注入（默认=现状；评测批次注入覆盖）。
 */
class MaterialEnhancementConfigTest {

    private static EngineConfig of(String json) {
        return new EngineConfig(json, false);
    }

    @Test
    void defaultsPreserveCurrentBehavior() {
        EngineConfig cfg = of("{}");
        assertEquals(3, cfg.distillConcurrency, "蒸馏并发默认 3");
        assertFalse(cfg.extractOnDistilled,
                "extractOnDistilled 默认 false（Y 臂直通——2026-09-10 验收后翻转默认值）");
        assertEquals(12000, cfg.contextMaxChars, "回退/单遍上下文预算默认 12000（原硬编码值）");
        assertEquals(6000, cfg.sectionContextChars);
        assertFalse(cfg.sourceDistill, "sourceDistill 默认仍关");
        assertEquals(6, cfg.maxSections, "大纲节数上限默认 6（现状）");
    }

    @Test
    void jsonInjectionOverrides() {
        EngineConfig cfg = of("{\"distillConcurrency\":2,\"extractOnDistilled\":true,"
                + "\"contextMaxChars\":25000,\"sourceDistill\":true,\"maxSections\":8}");
        assertEquals(2, cfg.distillConcurrency);
        assertTrue(cfg.extractOnDistilled, "X 臂显式开启（提炼路径，回退/对照配置）");
        assertEquals(25000, cfg.contextMaxChars);
        assertTrue(cfg.sourceDistill);
        assertEquals(8, cfg.maxSections, "评测注入 8");
    }

    @Test
    void maxSectionsBoundsClamped() {
        assertEquals(6, of("{\"maxSections\":99}").maxSections, "越界（>12）拒收 → 默认 6");
        assertEquals(6, of("{\"maxSections\":1}").maxSections, "过小（<2）拒收 → 默认 6");
    }

    @Test
    void concurrencyBoundsClamped() {
        EngineConfig cfg = of("{\"distillConcurrency\":99,\"contextMaxChars\":50}");
        assertEquals(3, cfg.distillConcurrency, "越界并发 → 保持默认（1..8 外拒收）");
        assertEquals(12000, cfg.contextMaxChars, "过小预算拒收（>1000 才接受）");
    }
}
