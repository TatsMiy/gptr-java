package com.gptr.benchmark.dims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HallucinationJudge 校验逻辑单测（verdict 注水/不匹配丢弃/限量；
 * 三态 supported/contradictory/inconclusive 白名单）。
 * 直接测纯函数 {@code matchVerdicts}——judge 输出不可信是已证实缺陷。
 */
class HallucinationJudgeTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode verdicts(String json) throws Exception {
        return M.readTree(json).path("verdicts");
    }

    @Test
    void threeStateVerdictsCountedSeparately() throws Exception {
        List<ReportText.Sentence> inputs = List.of(
                new ReportText.Sentence("句子甲。", List.of("https://a.com")),
                new ReportText.Sentence("句子乙。", List.of("https://b.com")),
                new ReportText.Sentence("句子丙。", List.of("https://c.com")));
        JsonNode v = verdicts("{\"verdicts\":["
                + "{\"sentence\":\"句子甲。\",\"verdict\":\"supported\",\"evidence\":\"e1\"},"
                + "{\"sentence\":\"句子乙。\",\"verdict\":\"contradictory\",\"evidence\":\"e2\"},"
                + "{\"sentence\":\"句子丙。\",\"verdict\":\"inconclusive\",\"evidence\":\"e3\"}]}");
        HallucinationJudge.BatchStats s = HallucinationJudge.matchVerdicts(inputs, v);
        assertEquals(3, s.judged());
        assertEquals(1, s.contradictory());
        assertEquals(1, s.inconclusive());
        assertEquals(0, s.dropped());
    }

    @Test
    void verdictOutsideWhitelistDropped() throws Exception {
        // 白名单外值（如旧版 supported:true 形态 / "unsupported"）必须丢弃，防注水/口径漂移
        List<ReportText.Sentence> inputs = List.of(
                new ReportText.Sentence("句子甲。", List.of("https://a.com")),
                new ReportText.Sentence("句子乙。", List.of("https://b.com")));
        JsonNode v = verdicts("{\"verdicts\":["
                + "{\"sentence\":\"句子甲。\",\"verdict\":\"unsupported\",\"evidence\":\"e\"},"
                + "{\"sentence\":\"句子乙。\",\"supported\":true}]}");
        HallucinationJudge.BatchStats s = HallucinationJudge.matchVerdicts(inputs, v);
        assertEquals(0, s.judged());
        assertEquals(2, s.dropped(), "白名单外判定一律丢弃");
    }

    @Test
    void fabricatedVerdictsDropped() throws Exception {
        // judge 注水：返回输入之外的句子 → 必须丢弃（曾实测分母 37 > 上限 20）
        List<ReportText.Sentence> inputs = List.of(
                new ReportText.Sentence("句子甲。", List.of("https://a.com")));
        JsonNode v = verdicts("{\"verdicts\":["
                + "{\"sentence\":\"句子甲。\",\"verdict\":\"supported\",\"evidence\":\"\"},"
                + "{\"sentence\":\"模型编造的句子乙。\",\"verdict\":\"contradictory\",\"evidence\":\"\"}]}");
        HallucinationJudge.BatchStats s = HallucinationJudge.matchVerdicts(inputs, v);
        assertEquals(1, s.judged(), "注水条目必须被丢弃");
        assertEquals(0, s.contradictory());
        assertEquals(1, s.dropped());
    }

    @Test
    void verdictsExceedingBatchSizeTruncated() throws Exception {
        // judge 输出多于输入句数：只取前 N（防分母膨胀）
        List<ReportText.Sentence> inputs = List.of(
                new ReportText.Sentence("句子甲。", List.of("https://a.com")));
        JsonNode v = verdicts("{\"verdicts\":["
                + "{\"sentence\":\"句子甲。\",\"verdict\":\"contradictory\",\"evidence\":\"\"},"
                + "{\"sentence\":\"句子甲。\",\"verdict\":\"supported\",\"evidence\":\"\"}]}");
        HallucinationJudge.BatchStats s = HallucinationJudge.matchVerdicts(inputs, v);
        assertEquals(1, s.judged());
        assertEquals(1, s.contradictory());
    }

    @Test
    void normalizationMatchesPunctuationVariants() throws Exception {
        // judge 回显句子带标点差异也应匹配
        List<ReportText.Sentence> inputs = List.of(
                new ReportText.Sentence("长江全长约6300公里。", List.of("https://a.com")));
        JsonNode v = verdicts("{\"verdicts\":["
                + "{\"sentence\":\"长江全长约6300公里\",\"verdict\":\"supported\",\"evidence\":\"\"}]}");
        HallucinationJudge.BatchStats s = HallucinationJudge.matchVerdicts(inputs, v);
        assertEquals(1, s.judged(), "归一化后应匹配（句号差异）");
        assertEquals(0, s.contradictory(), "supported 不进矛盾计数");
        assertEquals(0, s.inconclusive());
    }
}
