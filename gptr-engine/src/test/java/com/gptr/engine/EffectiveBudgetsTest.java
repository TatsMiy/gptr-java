package com.gptr.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EffectiveBudgets} 与原内联表达式的等价性，以及 {@link EngineConfig#snapshot()} 的一致性。
 *
 * <p>这是"生效值快照"的**门禁**而非文档：若有人改了 {@link EffectiveBudgets#of} 却忘了快照
  * （或反之），本测试即红。 */
class EffectiveBudgetsTest {

    @Test
    @DisplayName("默认配置：extraChars=0 / pageRawCap=4000 / groupJoinCap=20000")
    void defaultsMatchLegacyExpressions() {
        EffectiveBudgets b = new EngineConfig("{}").budgets();
        assertEquals(0, b.extraChars());
        assertEquals(4000, b.pageRawCap());
        assertEquals(20000, b.groupJoinCap());
    }

    @Test
    @DisplayName("sourceDistill=true 且上限 30000：三者同步放大")
    void distillModeScalesAllThree() {
        EffectiveBudgets b = new EngineConfig(
                "{\"sourceDistill\":true,\"distillMaxChars\":30000}").budgets();
        assertEquals(30000, b.extraChars());
        assertEquals(30000, b.pageRawCap());
        assertEquals(30000, b.groupJoinCap());
    }

    @Test
    @DisplayName("distillMaxChars 低于下限时 groupJoinCap 被下限顶住（静默失效实例）")
    void groupJoinCapKeepsFloor() {
        EffectiveBudgets b = new EngineConfig(
                "{\"sourceDistill\":true,\"distillMaxChars\":5000}").budgets();
        assertEquals(5000, b.extraChars());
        assertEquals(5000, b.pageRawCap());
        assertEquals(20000, b.groupJoinCap(),
                "Math.max(DEFAULT_GROUP_JOIN_CAP, …) 下限：该键对提炼上下文不再生效");
    }

    @Test
    @DisplayName("快照与 budgets() 同源：三个派生量数值必须一致")
    void snapshotAgreesWithBudgets() {
        EngineConfig cfg = new EngineConfig("{\"sourceDistill\":true,\"distillMaxChars\":30000}");
        EffectiveBudgets b = cfg.budgets();
        String joined = String.join("\n", cfg.snapshot());
        assertTrue(joined.contains("extraChars=" + b.extraChars()), joined);
        assertTrue(joined.contains("pageRawCap=" + b.pageRawCap()), joined);
        assertTrue(joined.contains("groupJoinCap=" + b.groupJoinCap()), joined);
    }

    @Test
    @DisplayName("快照形状稳定：7 行、组序固定、language 用 [] 包裹")
    void snapshotShapeIsStable() {
        List<String> lines = new EngineConfig("{}").snapshot();
        assertEquals(7, lines.size());
        assertTrue(lines.get(0).startsWith("图规模 | "), lines.get(0));
        assertTrue(lines.get(4).startsWith("预算派生 | "), lines.get(4));
        assertTrue(lines.stream().anyMatch(l -> l.contains("language=[中文]")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("distillConcurrency=3(IGNORED)")),
                "任务级 distillConcurrency 已失效，快照必须显式标注，否则读者会以为它能调");
    }
}
