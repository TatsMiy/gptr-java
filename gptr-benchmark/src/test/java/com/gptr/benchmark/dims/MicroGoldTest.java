package com.gptr.benchmark.dims;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ② micro-gold 夹具自洽性单测（期望标签由构造保证，此处防未来编辑误伤）：
 * 数量与比例对齐 DeepFact 1:4 supported:unsupported；每句期望合法、语料非空。
 */
class MicroGoldTest {

    @Test
    void fixtureCountAndRatioAlignDeepFact() {
        List<MicroGold.ProbeCase> cases = MicroGold.fixtures();
        assertEquals(30, cases.size(), "6 组 × 5 派生句");
        long supported = cases.stream().filter(c -> c.expected().equals("supported")).count();
        long contradictory = cases.stream().filter(c -> c.expected().equals("contradictory")).count();
        long inconclusive = cases.stream().filter(c -> c.expected().equals("inconclusive")).count();
        assertEquals(6, supported);
        assertEquals(17, contradictory);
        assertEquals(7, inconclusive);
        // DeepFact 1:4 supported:unsupported（24 非支持 / 6 支持 = 4:1）
        assertEquals(24, contradictory + inconclusive);
        assertEquals(4.0, (double) (contradictory + inconclusive) / supported, 0.001);
    }

    @Test
    void fixtureCasesWellFormed() {
        Set<String> legal = Set.of("supported", "contradictory", "inconclusive");
        for (MicroGold.ProbeCase c : MicroGold.fixtures()) {
            assertFalse(c.sentence().isBlank(), "句子非空");
            assertFalse(c.corpus().isBlank(), "语料非空");
            assertTrue(c.corpus().contains("[来源 "), "语料含引用形态前缀");
            assertTrue(legal.contains(c.expected()), "期望判定合法: " + c.expected());
        }
    }

    @Test
    void supportedCasesAnchorInCorpus() {
        // 期望 supported 的句子：每个关键元素都应在语料中（防未来编辑把 base 改成语料外内容）
        for (MicroGold.ProbeCase c : MicroGold.fixtures()) {
            if (c.expected().equals("supported")) {
                String body = c.corpus();
                String sentence = c.sentence();
                // 粗粒度锚检查：句子中至少一个 3+ 字符片段出现在语料（去标点后）
                String compact = sentence.replaceAll("[\\s，。、；：！？「」『』（）【】]", "");
                boolean anchored = false;
                for (int i = 0; i + 3 <= compact.length() && !anchored; i += 3) {
                    if (body.contains(compact.substring(i, i + 3))) {
                        anchored = true;
                    }
                }
                assertTrue(anchored, "supported 句应在语料中有锚: " + sentence);
            }
        }
    }
}
