package com.gptr.benchmark.dataset;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 官方 SimpleQA 子集可加载性验证（datasets/simpleqa/simpleqa-subset.jsonl，
 * 自官方 simple_qa_test_set.csv 分层裁剪，meta 见 simpleqa-subset.meta.json）。
 */
class DatasetLoaderTest {

    @Test
    void loadsStratifiedSimpleQaSubset() {
        List<BenchmarkItem> items = DatasetLoader.load("classpath:datasets/simpleqa/simpleqa-subset.jsonl");

        assertTrue(items.size() >= 20 && items.size() <= 30,
                "子集应为 20-30 条（设计 24）: " + items.size());
        Set<String> ids = new HashSet<>();
        for (BenchmarkItem it : items) {
            assertTrue(it.id().startsWith("simpleqa-"), "官方行号 id: " + it.id());
            assertTrue(ids.add(it.id()), "id 不重复: " + it.id());
            assertEquals("flat", it.mode(), "SimpleQA 客观题走 flat（可比官方数字）");
            assertEquals("simpleqa", it.cat(), "独立桶，不与 general/recency 混淆");
            assertTrue(it.hasGold(), "SimpleQA 每题有官方 gold: " + it.id());
            assertFalse(it.query().isBlank());
            assertFalse(it.gold().isBlank());
        }
    }

    @Test
    void mainBenchmarkStillLoads() {
        List<BenchmarkItem> items = DatasetLoader.load("classpath:datasets/benchmark.jsonl");
        assertEquals(47, items.size(), "主题集保持 47 条（20 flat + 20 deep + 6 recency + 1）");
    }
}
