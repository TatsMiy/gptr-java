package com.gptr.engine.epoc;

import com.gptr.integration.client.ScrapeOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抓取体检的聚合：分桶、跨轮累加、失败样本封顶。
 *
 * <p>回归重点有两条：① 每个 outcome **恰好进一个桶**（否则面板对不上账）；
 * ② **非数值字段不得被丢掉** —— 状态的通用累加器只搬 {@code Number}，若把本载体
 * 交给它，失败样本会被静默清空。
 */
class ScrapeHealthTest {

    private static ScrapeOutcome ok() {
        return new ScrapeOutcome("https://a", "ok", "article", false, false);
    }

    private static ScrapeOutcome failure(String reason) {
        return new ScrapeOutcome("https://f-" + reason, reason, "unknown", false, false);
    }

    @Test
    void everyOutcomeLandsInExactlyOneBucket() {
        List<ScrapeOutcome> outcomes = List.of(
                ok(),
                new ScrapeOutcome("https://b", "ok", "article", true, false),   // 截断 ⇒ 降级成功
                new ScrapeOutcome("https://c", "ok", "article", false, true),   // 兜底重试 ⇒ 降级成功
                failure("block_challenge"),
                failure("unknown"),
                failure("timeout"),
                failure("too_short"),
                failure("http_4xx"));

        Map<String, Object> health = ScrapeHealth.tally(outcomes);

        assertEquals(1, ScrapeHealth.count(health, ScrapeHealth.OK_CLEAN));
        assertEquals(2, ScrapeHealth.count(health, ScrapeHealth.OK_DEGRADED));
        assertEquals(1, ScrapeHealth.count(health, ScrapeHealth.BLOCKED_OR_LOGIN));
        assertEquals(1, ScrapeHealth.count(health, ScrapeHealth.SILENT_DROPPED));
        assertEquals(3, ScrapeHealth.count(health, ScrapeHealth.FAILED));

        int buckets = 0;
        for (String bucket : List.of(ScrapeHealth.OK_CLEAN, ScrapeHealth.OK_DEGRADED,
                ScrapeHealth.BLOCKED_OR_LOGIN, ScrapeHealth.SILENT_DROPPED, ScrapeHealth.FAILED)) {
            buckets += ScrapeHealth.count(health, bucket);
        }
        assertEquals(outcomes.size(), buckets, "五桶之和必须等于尝试数（可对账）");
    }

    @Test
    void truncatedIsAViewNotABucket() {
        Map<String, Object> health = ScrapeHealth.tally(
                List.of(new ScrapeOutcome("https://a", "ok", "article", true, false)));

        assertEquals(1, ScrapeHealth.count(health, ScrapeHealth.TRUNCATED));
        assertEquals(1, ScrapeHealth.count(health, ScrapeHealth.OK_DEGRADED));
        assertEquals(2, ScrapeHealth.count(health, ScrapeHealth.TRUNCATED)
                + ScrapeHealth.count(health, ScrapeHealth.OK_DEGRADED),
                "truncated 与 okDegraded 可同时成立（故它是子集视图，不参与桶求和）");
    }

    @Test
    void failureSamplesAreCappedAndTheOmittedCountIsHonest() {
        List<ScrapeOutcome> many = new ArrayList<>();
        for (int i = 0; i < ScrapeHealth.SAMPLE_FAILURES_CAP + 5; i++) {
            many.add(failure("timeout"));
        }
        Map<String, Object> health = ScrapeHealth.tally(many);

        assertEquals(ScrapeHealth.SAMPLE_FAILURES_CAP, ScrapeHealth.samples(health).size());
        assertEquals(5, ScrapeHealth.failuresOmitted(health),
                "被截掉的条数必须如实记下，否则'样本 20 条'会被读成'总共 20 条失败'");
        assertEquals(ScrapeHealth.SAMPLE_FAILURES_CAP + 5, ScrapeHealth.count(health, ScrapeHealth.FAILED));
    }

    @Test
    void mergingRoundsAddsCountsAndKeepsNonNumericParts() {
        Map<String, Object> round1 = ScrapeHealth.tally(List.of(ok(), failure("timeout")));
        Map<String, Object> round2 = ScrapeHealth.tally(List.of(ok(), failure("block_challenge")));

        Map<String, Object> merged = ScrapeHealth.merge(round1, round2);

        assertEquals(2, ScrapeHealth.count(merged, ScrapeHealth.OK_CLEAN));
        assertEquals(1, ScrapeHealth.count(merged, ScrapeHealth.FAILED));
        assertEquals(1, ScrapeHealth.count(merged, ScrapeHealth.BLOCKED_OR_LOGIN));
        assertEquals(2, ScrapeHealth.samples(merged).size(),
                "失败样本必须跨轮保留 —— 通用状态累加器只搬 Number，走它会把这些静默丢掉");
        assertTrue(ScrapeHealth.samples(merged).get(0).containsKey("url"));
        assertTrue(ScrapeHealth.samples(merged).get(0).containsKey("reason"));
    }

    @Test
    void mergingCapsAcrossRoundsAndAccumulatesOmitted() {
        List<ScrapeOutcome> batch = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            batch.add(failure("timeout"));
        }
        Map<String, Object> round = ScrapeHealth.tally(batch);

        Map<String, Object> merged = ScrapeHealth.merge(
                ScrapeHealth.merge(ScrapeHealth.empty(), round), round);

        assertEquals(30, ScrapeHealth.count(merged, ScrapeHealth.FAILED));
        assertEquals(ScrapeHealth.SAMPLE_FAILURES_CAP, ScrapeHealth.samples(merged).size());
        assertEquals(10, ScrapeHealth.failuresOmitted(merged));
    }

    @Test
    void checkpointRoundTripValuesSurviveAsNumbersOrLongs() {
        // checkpoint 恢复出来的计数可能是 Long；合并不得因此抛 ClassCastException。
        Map<String, Object> restored = Map.of(
                ScrapeHealth.COUNTS, Map.of(ScrapeHealth.OK_CLEAN, 3L),
                ScrapeHealth.FAILURES, List.of(),
                ScrapeHealth.FAILURES_OMITTED, 7L);

        Map<String, Object> merged = ScrapeHealth.merge(
                restored, ScrapeHealth.tally(List.of(ok())));

        assertEquals(4, ScrapeHealth.count(merged, ScrapeHealth.OK_CLEAN));
        assertEquals(7, ScrapeHealth.failuresOmitted(merged));
    }
}
