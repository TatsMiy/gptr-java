package com.gptr.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
  * EngineConfig 新键——覆盖档位（coverMode）与抓取配额
 * （scrapeQuotaMode / perQuery / min / hardCap）。
 *
 * <p>放在本包：EngineConfig 为包私有。
 */
class CoverageConfigKeysTest {

    @Test
    void defaultsPreserveCurrentBehaviour() {
        EngineConfig def = new EngineConfig("{}");
        assertEquals("dimensions", def.coverMode,
                "默认新形态（严格优于 legacy：省一次调用 + 判定机械）");
        assertEquals("linked", def.scrapeQuotaMode,
                "2026-09-10 4 格对照后翻转默认值：dims 默认开启时 flat 会让 60% 维度零产出");
        assertEquals(2, def.scrapePerQueryQuota);
        assertEquals(8, def.scrapeMinQuota, "下界=现状 maxScrapeUrls → 配额只增不减");
        assertEquals(16, def.scrapeHardCap, "上界=成本护栏");
    }

    @Test
    void explicitValuesAccepted() {
        EngineConfig on = new EngineConfig("{\"coverMode\":\"off\",\"scrapeQuotaMode\":\"linked\","
                + "\"scrapePerQueryQuota\":3,\"scrapeMinQuota\":8,\"scrapeHardCap\":24}");
        assertEquals("off", on.coverMode);
        assertEquals("linked", on.scrapeQuotaMode);
        assertEquals(3, on.scrapePerQueryQuota);
        assertEquals(8, on.scrapeMinQuota);
        assertEquals(24, on.scrapeHardCap);

        assertEquals("legacy", new EngineConfig("{\"coverMode\":\"LEGACY\"}").coverMode,
                "大小写不敏感");
    }

    @Test
    void badValuesFallBackToDefaults() {
        EngineConfig bad = new EngineConfig("{\"coverMode\":\"nonsense\","
                + "\"scrapeQuotaMode\":\"weird\",\"scrapePerQueryQuota\":0,\"scrapeMinQuota\":-1}");
        assertEquals("dimensions", bad.coverMode, "非法档位 → 默认");
        assertEquals("linked", bad.scrapeQuotaMode, "非法档位 → 默认");
        assertEquals(2, bad.scrapePerQueryQuota, "越界 → 默认");
        assertEquals(8, bad.scrapeMinQuota);

        EngineConfig big = new EngineConfig("{\"scrapeHardCap\":999,\"scrapeMinQuota\":999,"
                + "\"scrapePerQueryQuota\":99}");
        assertEquals(8, big.scrapeMinQuota, "越界 → 默认");
        assertEquals(16, big.scrapeHardCap, "越界 → 默认");
        assertEquals(2, big.scrapePerQueryQuota, "越界 → 默认");
    }

    @Test
    void hardCapBelowMinIsClampedToMin() {
        EngineConfig c = new EngineConfig("{\"scrapeMinQuota\":12,\"scrapeHardCap\":4}");
        assertEquals(12, c.scrapeMinQuota);
        assertEquals(12, c.scrapeHardCap, "cap < min → 夹到 min（防配额被压成 0/负）");
    }
}
