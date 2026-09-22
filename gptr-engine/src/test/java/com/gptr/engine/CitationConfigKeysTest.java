package com.gptr.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
  * EngineConfig 直引归节开关与证据目录预算键。
 *
 * <p>放在本包：EngineConfig 为包私有。
 */
class CitationConfigKeysTest {

    @Test
    void defaultsUseCitationModeAfterFlip() {
        EngineConfig def = new EngineConfig("{}");
        assertTrue(def.assignByCitation,
                "默认 true（2026-09-17 转正：三题验收 + 默认路径复发 3 次错位型占位）——直引归节");
        assertEquals(40000, def.evidenceIndexMaxChars,
                "证据目录上限默认 40000（12000 时 195 条证据被截断 31%）");
    }

    @Test
    void citationModeAndBudgetAccepted() {
        EngineConfig on = new EngineConfig(
                "{\"assignByCitation\":true,\"evidenceIndexMaxChars\":60000}");
        assertTrue(on.assignByCitation);
        assertEquals(60000, on.evidenceIndexMaxChars);
        // 显式设过旧默认值的配置行为不变（向后兼容）
        assertEquals(12000, new EngineConfig("{\"evidenceIndexMaxChars\":12000}")
                .evidenceIndexMaxChars, "显式 12000 仍生效（旧配置不被改写）");
    }

    @Test
    void badValuesFallBackToDefaults() {
        EngineConfig bad = new EngineConfig("{\"evidenceIndexMaxChars\":10}");
        assertEquals(40000, bad.evidenceIndexMaxChars, "低于下界 4000 → 默认（防目录被压成无意义）");
        EngineConfig tooBig = new EngineConfig("{\"evidenceIndexMaxChars\":999999}");
        assertEquals(40000, tooBig.evidenceIndexMaxChars, "超上界 80000 → 默认（防 prompt 爆窗）");
        EngineConfig nonBool = new EngineConfig("{\"assignByCitation\":\"yes\"}");
        assertTrue(nonBool.assignByCitation, "非布尔 → 默认（转正后默认为 true）");
    }
}
