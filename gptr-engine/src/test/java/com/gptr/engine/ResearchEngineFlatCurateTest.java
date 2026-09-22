package com.gptr.engine;

import com.gptr.common.engine.ResearchEngine;
import com.gptr.common.engine.StageResult;
import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskStage;
import com.gptr.engine.context.ContextManager;
import com.gptr.engine.plan.SubQueryPlanner;
import com.gptr.engine.search.Searcher;
import com.gptr.engine.write.ReportWriter;
import com.gptr.integration.client.LlmClient;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * flat 模式来源质量闸：SUMMARIZING 前对整批条目一次 LLM 排序精选（对标 py
 * researcher.py + SourceCurator）；坏输出回退原文；**默认关**（原验收未覆盖
 * "组内条目数 &gt; `curatorMaxSources`"区间——越过该闸时组尾已抓正文块被整批丢弃）。
 * 本类用例一律**显式**指定该键，不依赖默认值。
 * 纯单测（无 DB，checkpointSaver=null）。
 */
class ResearchEngineFlatCurateTest {

    /** 匿名 LLM：chatJson 分流 planner/curate；chat 记录写作上下文（供断言顺序）。 */
    static class CurateLlm implements LlmClient {
        final List<String> chatUsers = new CopyOnWriteArrayList<>();
        String curateJson = "{\"kept\":[2,1]}";

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            chatUsers.add(userPrompt);
            return "# 报告\n\n正文内容，无引用。\n";
        }

        @Override
        public String chatJson(String system, String user) {
            if (system.contains("研究规划")) {
                return "{\"queries\":[{\"query\":\"sq1\",\"researchGoal\":\"g\"}]}";
            }
            if (system.contains("source curator")) {
                return curateJson;
            }
            return "{}";
        }

        @Override
        public double lastCallCostUsd() {
            return 0.001;
        }

        @Override
        public String name() {
            return "curate-llm";
        }
    }

    static SearchClient mockSearch() {
        return new SearchClient() {
            @Override
            public String name() {
                return "mock";
            }

            @Override
            public SearchResponse search(String query) {
                return search(query, SearchOptions.DEFAULT);
            }

            @Override
            public SearchResponse search(String query, SearchOptions opts) {
                return new SearchResponse(List.of(new SearchResult("r1", "https://u1", "snippet one"),
                        new SearchResult("r2", "https://u2", "snippet two")), name());
            }
        };
    }

    private ResearchEngine engine(CurateLlm llm, String config) {
        ResearchTask task = new ResearchTask();
        task.setId(java.util.UUID.randomUUID());
        task.setQuery("flat topic");
        task.setConfig(config);
        SearchClient search = mockSearch();
        return new ResearchEngineImpl(task, new ResearchEngineImpl.EngineDeps(
                new SubQueryPlanner(llm), new Searcher(search), llm, search, new ReportWriter(llm),
                null, new ContextManager(), null));
    }

    private StageResult runThrough(ResearchEngine engine) {
        StageResult summarizing = null;
        for (TaskStage stage : engine.stages()) {
            StageResult r = engine.runStage(stage);
            if (stage == TaskStage.SUMMARIZING) {
                summarizing = r;
            }
        }
        return summarizing;
    }

    @Test
    void flatCurateReordersEntriesByKeptPriority() {
        CurateLlm llm = new CurateLlm();
        // curateSources 默认关 ⇒ 本用例显式打开（不再依赖默认值）
        ResearchEngine engine = engine(llm, "{\"curateSources\":true}");

        StageResult summarizing = runThrough(engine);

        assertTrue(summarizing.payload().contains("\"curated\":true"),
                "显式开：curate 应用应标记: " + summarizing.payload());
        // curate 一次 LLM 调用成本计入 SUMMARIZING
        assertTrue(summarizing.costUsd() > 0, "curate 调用成本应回写: " + summarizing.costUsd());
        // 写作上下文按 kept 优先级（u2 在前）
        String ctx = llm.chatUsers.stream().filter(u -> u.contains("Research context")).findFirst().orElse("");
        int p1 = ctx.indexOf("https://u2");
        int p2 = ctx.indexOf("https://u1");
        assertTrue(p1 >= 0 && p2 >= 0 && p1 < p2, "kept=[2,1] → u2 应在 u1 前: " + ctx);
    }

    @Test
    void flatCurateExplicitlyDisabledKeepsOriginalOrder() {
        CurateLlm llm = new CurateLlm();
        ResearchEngine engine = engine(llm, "{\"curateSources\":false}"); // 显式关仍可关

        StageResult summarizing = runThrough(engine);

        assertTrue(!summarizing.payload().contains("\"curated\""),
                "显式关：不 curate — " + summarizing.payload());
        String ctx = llm.chatUsers.stream().filter(u -> u.contains("Research context")).findFirst().orElse("");
        int p1 = ctx.indexOf("https://u1");
        int p2 = ctx.indexOf("https://u2");
        assertTrue(p1 >= 0 && p2 >= 0 && p1 < p2, "关闭时保持原顺序: " + ctx);
    }

    @Test
    void flatCurateBadOutputFallsBackToOriginal() {
        CurateLlm llm = new CurateLlm();
        llm.curateJson = "garbage";
        ResearchEngine engine = engine(llm, "{\"curateSources\":true}");

        StageResult summarizing = runThrough(engine);

        assertTrue(!summarizing.payload().contains("\"curated\""),
                "坏输出回退 → 不标记 curated（成本也不计 LLM）: " + summarizing.payload());
        assertEquals(0.0, summarizing.costUsd(), 0.0001);
        String ctx = llm.chatUsers.stream().filter(u -> u.contains("Research context")).findFirst().orElse("");
        assertTrue(ctx.contains("https://u1") && ctx.contains("https://u2"), "回退保留全部原文");
        int p1 = ctx.indexOf("https://u1");
        int p2 = ctx.indexOf("https://u2");
        assertTrue(p1 < p2, "回退保持原顺序");
    }

    @Test
    void flatCurateKeepsAllWhenKeptCoversEverything() {
        CurateLlm llm = new CurateLlm();
        llm.curateJson = "{\"kept\":[1,2]}"; // 全保留但保序
        ResearchEngine engine = engine(llm, "{\"curateSources\":true}");

        StageResult summarizing = runThrough(engine);

        assertTrue(summarizing.payload().contains("\"curated\":true"), "走过质量闸即标记");
        String ctx = llm.chatUsers.stream().filter(u -> u.contains("Research context")).findFirst().orElse("");
        assertTrue(ctx.contains("https://u1") && ctx.contains("https://u2"), "两条都保留");
    }
}
