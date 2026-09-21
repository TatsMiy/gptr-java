package com.gptr.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EngineConfig mock.* 隔离单测：生产（allowMock=false）忽略 mock 测试旋钮，
 * 阶段延迟/固定成本为 0；显式 allowMock=true 时才生效。
 */
class EngineConfigTest {

    @Test
    void productionIgnoresMockKnobsAndDefaultsToZeroCostDelay() {
        String config = "{\"maxSubQueries\":2,\"mock\":{\"failAtStage\":\"SEARCHING\","
                + "\"stageDelayMs\":9999,\"costPerStage\":100}}";
        EngineConfig cfg = new EngineConfig(config, false);
        assertNull(cfg.failAtStage, "生产必须忽略 mock.failAtStage（防远程失败注入）");
        assertEquals(0L, cfg.stageDelayMs, "生产无假延迟");
        assertEquals(0.0, cfg.costPerStage, "生产无假成本");
        assertEquals(2, cfg.maxSubQueries, "非 mock 键不受影响");
        assertTrue(!cfg.deepResearch);
    }

    @Test
    void productionIgnoresMockKnobsWhenConfigHasNoMockSection() {
        EngineConfig cfg = new EngineConfig("{\"mode\":\"deep_research\",\"breadth\":2,\"depth\":1}", false);
        assertEquals(0L, cfg.stageDelayMs);
        assertEquals(0.0, cfg.costPerStage);
        assertNull(cfg.failAtStage);
        assertTrue(cfg.deepResearch);
        assertEquals(2, cfg.breadth);
    }

    @Test
    void allowMockEnablesTestKnobs() {
        String config = "{\"mock\":{\"failAtStage\":\"WRITING\",\"stageDelayMs\":25,\"costPerStage\":0.01}}";
        EngineConfig cfg = new EngineConfig(config, true);
        assertEquals("WRITING", cfg.failAtStage);
        assertEquals(25L, cfg.stageDelayMs);
        assertEquals(0.01, cfg.costPerStage, 1e-9);
    }
}
