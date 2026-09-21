package com.gptr.engine.write;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
  * 已写节注入回归。
 *
 * <p>这三条判据全部可机械验证，且**不需要 LLM**：注入块的组装、净化与截断都是纯函数。
 */
class PriorSectionsInjectionTest {

    private static SectionWriter writer(int budget) {
        return new SectionWriter(null, 6000, false, 6, budget);
    }

    @Test
    @DisplayName("判据4：无前文 ⇒ 注入块为空串（等价于第一节的现状 prompt）")
    void noPriorYieldsEmptyBlock() {
        assertEquals("", writer(24000).buildPriorBlock(null), "null ⇒ 空串");
        assertEquals("", writer(24000).buildPriorBlock("   "), "blank ⇒ 空串");
    }

    @Test
    @DisplayName("判据4b：预算 ≤0 ⇒ 关闭注入（A/B 对照档），即使有前文也返回空串")
    void nonPositiveBudgetDisablesInjection() {
        assertEquals("", writer(0).buildPriorBlock("## 甲\n正文 https://a.example/x"));
        assertEquals("", writer(-1).buildPriorBlock("## 甲\n正文"));
    }

    @Test
    @DisplayName("判据5：净化剥 markdown 链接与裸 URL，但保留文字（防撞节级引用闸门）")
    void stripUrlsRemovesAllUrlsButKeepsText() {
        String in = "## 甲\n见 [来源](https://a.example/x) 与裸链 https://b.example/y 以及 ![图](https://c.example/z.png)。";
        String out = SectionWriter.stripUrls(in);
        assertFalse(out.contains("http"), "输出不得含任何 http —— 实际：" + out);
        assertTrue(out.contains("[来源]"), "链接标签须保留方括号 —— 剥成裸文本会让模型把无链接当引用范式");
        assertTrue(out.contains("见"), "正文必须保留");
    }

    @Test
    @DisplayName("判据6：超预算 ⇒ 从最早的节开始整节丢弃，保留最近的节")
    void truncatePriorDropsEarliestSectionsFirst() {
        String prior = "## 第一节\n" + "A".repeat(100)
                + "\n\n## 第二节\n" + "B".repeat(100)
                + "\n\n## 第三节\n" + "C".repeat(100);
        String kept = SectionWriter.truncatePrior(prior, 250);
        assertFalse(kept.contains("第一节"), "最早的节应被丢弃");
        assertTrue(kept.contains("第三节"), "最近的节必须保留");
    }

    @Test
    @DisplayName("判据6b：未超预算 ⇒ 原样返回（不触发截断与 warn）")
    void truncatePriorKeepsAllWhenUnderBudget() {
        String prior = "## 甲\n短正文";
        assertEquals(prior, SectionWriter.truncatePrior(prior, 24000));
    }

    @Test
    @DisplayName("注入块：含 already_written 标记与防重复指令，且不含任何 URL")
    void blockCarriesGuardAndNoUrls() {
        String block = writer(24000).buildPriorBlock("## 甲\n见 [来源](https://a.example/x) 的论述。");
        assertTrue(block.contains("<already_written>"), "须有包裹标记");
        assertTrue(block.contains("do NOT restate"), "须含防重复指令");
        assertFalse(block.contains("a.example"), "注入块不得含 URL");
        assertTrue(block.contains("来源"), "正文保留");
    }
}
