package com.gptr.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认值转正回归：把「实验期默认值 → 正式默认值」的结论**钉住**。
 *
 * <p>这些断言的存在意义是**防静默回退**：若有人把默认值改回实验期状态，
 * 本测试必须一起改（改动可见、有据），而不是悄悄改变生产行为。
 *
  * <p>本测试锁定各实验键结案后的默认值，防止静默回退。
 */
class DefaultValueFlipTest {

    @Test
    @DisplayName("sourceRank 默认开（2026-09-19 转正：P1 干净对照 swap 双跑 6 维一致）")
    void sourceRankDefaultsOn() {
        assertTrue(new EngineConfig("{}", false).sourceRank,
                "sourceRank 默认应为 true —— 依据：2026-09-17 六维盲判双跑一致判其更优");
    }

    @Test
    @DisplayName("assignByCitation 默认开（2026-09-17 flip；2026-09-19 补齐质量盲判）")
    void assignByCitationDefaultsOn() {
        assertTrue(new EngineConfig("{}", false).assignByCitation,
                "assignByCitation 默认应为 true —— 盲判见 result-six-dim-blind-judge-r2r4-flip-20260919.md");
    }

    @Test
        @DisplayName("sourceDistill 仍默认关（依据为 tie=交换项，翻转待裁定）")
    void sourceDistillStillDefaultsOff() {
        assertFalse(new EngineConfig("{}", false).sourceDistill,
                "sourceDistill 仍默认 false —— 依据：开/关各有胜负（交换项），非全面优势");
    }

    @Test
    @DisplayName("extractOnDistilled 默认关（Y 臂直通；2026-09-19 结案，值未变）")
    void extractOnDistilledDefaultsOff() {
        assertFalse(new EngineConfig("{}", false).extractOnDistilled,
                "extractOnDistilled 默认应为 false（Y 臂）");
    }

    @Test
    @DisplayName("显式传值仍可回退（config JSON 覆盖默认）")
    void explicitValuesStillOverride() {
        EngineConfig cfg = new EngineConfig(
                "{\"sourceRank\":false,\"assignByCitation\":false}", false);
        assertFalse(cfg.sourceRank, "显式 false 必须能回退 sourceRank");
        assertFalse(cfg.assignByCitation, "显式 false 必须能回退 assignByCitation");
    }
}
