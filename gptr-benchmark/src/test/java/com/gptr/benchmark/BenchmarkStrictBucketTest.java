package com.gptr.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * all-strict 全样本口径桶（失败任务显式入分母）。
 */
class BenchmarkStrictBucketTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode row(String status, boolean hasGold, String accuracy,
                                  boolean leakExcluded) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("status", status);
        n.put("hasGold", hasGold);
        if (accuracy != null) {
            if ("true".equals(accuracy) || "false".equals(accuracy)) {
                n.put("accuracy", Boolean.parseBoolean(accuracy));
            } else {
                n.put("accuracy", accuracy);
            }
        }
        n.put("leakExcluded", leakExcluded);
        return n;
    }

    @Test
    void failedTaskCountedAsIncorrectInStrictBucket() {
        Map<String, double[]> buckets = new LinkedHashMap<>();
        // 成功且答对
        BenchmarkMain.aggregateStrict(row("SUCCEEDED", true, "true", false), buckets);
        // 任务失败（执行层挂）→ 计 incorrect 入分母
        BenchmarkMain.aggregateStrict(row("FAILED", true, null, false), buckets);
        // 非 gold 题不入
        BenchmarkMain.aggregateStrict(row("FAILED", false, null, false), buckets);
        double[] b = buckets.get("all-strict");
        assertArrayEquals(new double[]{2, 1}, b,
                "all-strict n=2(失败计入分母) correct=1");
    }

    @Test
    void judgeUnparsableRemovedLikeConditionalBucket() {
        Map<String, double[]> buckets = new LinkedHashMap<>();
        BenchmarkMain.aggregateStrict(row("SUCCEEDED", true, "judge-unparsable", false), buckets);
        org.junit.jupiter.api.Assertions.assertNull(buckets.get("all-strict"),
                "SUCCEEDED 但 judge 无布尔判定：剔除（无判定能力不算错也不算对，同条件桶）");
        // 但任务失败（FAILED）即使无判定也必须入桶计错——这是 all-strict 全样本口径的核心语义
        Map<String, double[]> buckets2 = new LinkedHashMap<>();
        BenchmarkMain.aggregateStrict(row("FAILED", true, null, false), buckets2);
        assertArrayEquals(new double[]{1, 0}, buckets2.get("all-strict"),
                "FAILED 无判定也必须计入分母（失败=未作答=incorrect）");
    }

    @Test
    void leakExcludedNotCounted() {
        Map<String, double[]> buckets = new LinkedHashMap<>();
        BenchmarkMain.aggregateStrict(row("SUCCEEDED", true, "true", true), buckets);
        org.junit.jupiter.api.Assertions.assertNull(buckets.get("all-strict"),
                "泄漏剔除题不进任何准确率桶");
    }

    @Test
    void wrongAndItemErrorBothIncorrect() {
        Map<String, double[]> buckets = new LinkedHashMap<>();
        BenchmarkMain.aggregateStrict(row("SUCCEEDED", true, "false", false), buckets);
        BenchmarkMain.aggregateStrict(row("ITEM_ERROR", true, null, false), buckets);
        assertArrayEquals(new double[]{2, 0}, buckets.get("all-strict"));
    }
}
