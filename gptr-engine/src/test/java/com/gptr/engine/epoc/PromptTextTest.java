package com.gptr.engine.epoc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PromptText} 的预算截断契约。
 *
 * <p>存在的理由：q08 报告丢关键数字的病因曾三次误判（写作层 → 提炼层 → 抓取选择层），
 * 第四次嫌疑是"抓到了却因组内拼接预算被整条丢弃"。该嫌疑能否成立**只取决于这两个函数的行为**，
 * 与网络、LLM、数据库均无关，故用离线断言钉死，避免每次排查都依赖昂贵端到端任务。
 *
  * <p>其中 {@link #dropsContentBlocksWhenSummariesFillBudget} 把一个**未证项**
  * （"被丢的是正文块"
 * 当时由长度反推、非直观测）变成可机械执行的断言。
 */
class PromptTextTest {

    private static final int PAGE = 4000;
    private static final int CAP = 10000;
    private static final String TRUNCATED_MARK = "...[truncated]";

    private static String page(int len) {
        return "x".repeat(len);
    }

    private static String contentBlock(int bodyChars) {
        return "Full content of https://example.test/a:\n" + "y".repeat(bodyChars);
    }

    @Test
    @DisplayName("预算内全量拼接，不留截断标记、无丢弃")
    void joinWithinBudgetKeepsAll() {
        PromptText.Joined r = PromptText.joinItemsLimit(List.of(page(PAGE), page(PAGE)), CAP);
        assertFalse(r.text().endsWith(TRUNCATED_MARK));
        assertEquals(2 * PAGE + 2, r.text().length());
        assertTrue(r.dropped().isEmpty());
    }

    @Test
    @DisplayName("超预算的条目整条丢弃，并记入 dropped（下标/类型/长度）")
    void joinBeyondBudgetDropsWholeItem() {
        PromptText.Joined r = PromptText.joinItemsLimit(
                List.of(page(PAGE), page(PAGE), page(PAGE)), CAP);
        assertTrue(r.text().endsWith(TRUNCATED_MARK));
        assertEquals(2 * PAGE + 2 + 1 + TRUNCATED_MARK.length(), r.text().length());
        assertEquals(1, r.dropped().size(), "第 3 条被丢");
        PromptText.Dropped d = r.dropped().get(0);
        assertEquals(2, d.index(), "下标是入参中的位置");
        assertEquals(PAGE, d.chars());
        assertEquals("summary", d.kind(), "无前缀 → summary");
    }

    @Test
    @DisplayName("摘要 + 正文块混合组：被丢的是正文块（补上探针未证项）")
    void dropsContentBlocksWhenSummariesFillBudget() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(page(260)); // 5 条摘要 ≈1300，与探针实测同构
        }
        items.add(contentBlock(3900));
        items.add(contentBlock(3900));
        items.add(contentBlock(3900)); // 第 3 块必然出局（剩余 < 4000）

        PromptText.Joined r = PromptText.joinItemsLimit(items, CAP);

        assertTrue(r.text().endsWith(TRUNCATED_MARK));
        assertEquals(1, r.dropped().size(), "只有第 3 个正文块被丢");
        PromptText.Dropped d = r.dropped().get(0);
        assertEquals(7, d.index());
        assertEquals("content", d.kind(), "带 'Full content of ' 前缀 → content");
        assertEquals(contentBlock(3900).length(), d.chars());
    }

    @Test
    @DisplayName("蒸馏块类型可识别（DISTILLED 前缀）")
    void distilledBlocksAreTyped() {
        List<String> items = new ArrayList<>(List.of(page(260)));
        items.add(EvidenceText.DISTILLED_MARKER + "https://example.test/a\n- " + page(PAGE));
        items.add(EvidenceText.DISTILLED_MARKER + "https://example.test/b\n- " + page(PAGE));
        items.add(EvidenceText.DISTILLED_MARKER + "https://example.test/c\n- " + page(PAGE));

        PromptText.Joined r = PromptText.joinItemsLimit(items, CAP);
        assertFalse(r.dropped().isEmpty());
        assertEquals("distilled", r.dropped().get(0).kind());
    }

    @Test
    @DisplayName("单条即超预算 ⇒ 退化为仅剩截断标记（近乎空上下文）")
    void singleItemBeyondBudgetYieldsMarkerOnly() {
        PromptText.Joined r = PromptText.joinItemsLimit(List.of(page(20000)), CAP);
        assertEquals("\n" + TRUNCATED_MARK, r.text());
        assertEquals(1, r.dropped().size());
    }

    @Test
    @DisplayName("null / 空白条目跳过：不占预算、不计入 dropped")
    void joinSkipsBlankEntries() {
        PromptText.Joined r = PromptText.joinItemsLimit(
                Arrays.asList(page(10), "   ", null, page(10)), CAP);
        assertEquals(22, r.text().length());
        assertTrue(r.dropped().isEmpty(), "continue 跳过 ≠ 被预算丢弃");
    }
}
