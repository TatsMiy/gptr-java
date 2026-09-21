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
import com.gptr.integration.client.ScraperClient;
import com.gptr.integration.client.ScrapedContent;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J3 flat 来源提炼（sourceDistill）：长网页截断（爬虫 4000 + Java 3000）丢后段核心
 * 数据的修复——可配置 LLM 要点提炼覆盖全文；默认关（截断兜底）；失败回退原文。
 * 纯单测（无 DB）。
 */
class ResearchEngineSourceDistillTest {

    /** 超过任何截断上限的长正文：尾部携带核心数据标记（起点必须 > 3000 截断线）。 */
    static final String LONG_CONTENT = "网页开头介绍。".repeat(600)
            + "CORE_DATA_TAIL_42=合格（位于第 4200 字符之后）";

    /** 匿名 LLM：chatJson 分流 planner/distill；chat 记录写作上下文。 */
    static class DistillLlm implements LlmClient {
        final List<String> chatUsers = new CopyOnWriteArrayList<>();
        final List<String> systems = new CopyOnWriteArrayList<>();
        final List<String> users = new CopyOnWriteArrayList<>();
        final AtomicInteger distillCalls = new AtomicInteger();
        String distillJson = "{\"summary\":\"本页综述了后段核心方法及其数据。\","
                + "\"evidence\":[\"证据甲：某数据集 42 万样本\",\"证据乙：后段核心数据 CORE 合格率 98%\"]}";

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            chatUsers.add(userPrompt);
            return "# 报告\n\n正文，无引用。\n";
        }

        @Override
        public String chatJson(String system, String user) {
            systems.add(system);
            users.add(user);
            if (system.contains("研究规划")) {
                return "{\"queries\":[{\"query\":\"sq1\",\"researchGoal\":\"g\"}]}";
            }
            if (system.contains("资料提炼员")) {
                distillCalls.incrementAndGet();
                return distillJson;
            }
            return "{}";
        }

        @Override
        public double lastCallCostUsd() {
            return 0.001;
        }

        @Override
        public String name() {
            return "distill-llm";
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
                return new SearchResponse(List.of(new SearchResult("长网页标题", "https://u1", "snippet")), name());
            }
        };
    }

    /** mock 抓取：返回超长正文；记录收到的 maxChars 参数。 */
    static class LongScraper implements ScraperClient {
        final AtomicInteger seenMaxChars = new AtomicInteger(-1);

        @Override
        public String name() {
            return "long-scraper";
        }

        @Override
        public List<ScrapedContent> scrape(List<String> urls) {
            return List.of(new ScrapedContent(urls.get(0), "t", LONG_CONTENT));
        }

        @Override
        public List<ScrapedContent> scrape(List<String> urls, int maxCharsPerUrl) {
            seenMaxChars.set(maxCharsPerUrl);
            return List.of(new ScrapedContent(urls.get(0), "t", LONG_CONTENT));
        }
    }

    private ResearchEngine engine(DistillLlm llm, LongScraper scraper, String config) {
        ResearchTask task = new ResearchTask();
        task.setId(java.util.UUID.randomUUID());
        task.setQuery("flat topic");
        task.setConfig(config);
        SearchClient search = mockSearch();
        return new ResearchEngineImpl(task, new ResearchEngineImpl.EngineDeps(
                new SubQueryPlanner(llm), new Searcher(search), llm, search, new ReportWriter(llm),
                scraper, new ContextManager(), null));
    }

    private StageResult runThrough(ResearchEngine engine) {
        StageResult scraping = null;
        for (TaskStage stage : engine.stages()) {
            StageResult r = engine.runStage(stage);
            if (stage == TaskStage.SCRAPING) {
                scraping = r;
            }
        }
        return scraping;
    }

    @Test
    void distillEnabledReplacesTruncationWithCoveringPoints() {
        DistillLlm llm = new DistillLlm();
        LongScraper scraper = new LongScraper();
        ResearchEngine engine = engine(llm, scraper, "{\"sourceDistill\":true}");

        StageResult scraping = runThrough(engine);

        assertTrue(scraping.payload().contains("\"distill\":1"),
                "应提炼 1 页: " + scraping.payload() + " llm systems=" + llm.systems);
        assertEquals(20000, scraper.seenMaxChars.get(), "应请求放大正文上限");
        assertEquals(1, llm.distillCalls.get());
        // 提炼 prompt 必须注入研究问题（query 锚定）
        assertTrue(llm.users.stream().anyMatch(u -> u.contains("研究问题: flat topic")),
                "提炼应以研究问题为锚: " + llm.users);
        // 提炼成本计入 SCRAPING（1 次调用 × 0.001）
        assertTrue(scraping.costUsd() > 0, "提炼成本应回写: " + scraping.costUsd());
        // 写作上下文使用提炼产物（总结 + 硬核证据，覆盖全文），而不是开头截断
        String ctx = llm.chatUsers.stream().filter(u -> u.contains("Research context")).findFirst().orElse("");
        assertTrue(ctx.contains("总结："), "应含总结段: " + ctx);
        assertTrue(ctx.contains("硬核证据："), "应含硬核证据块: " + ctx);
        assertTrue(ctx.contains("证据乙：后段核心数据"), "后段核心数据经证据块保留: " + ctx);
        assertTrue(ctx.contains("LLM 提炼"), "应标记为提炼产物");
    }

    @Test
    void distillDisabledKeepsTruncationBaseline() {
        DistillLlm llm = new DistillLlm();
        LongScraper scraper = new LongScraper();
        ResearchEngine engine = engine(llm, scraper, "{}"); // sourceDistill 默认 false

        StageResult scraping = runThrough(engine);

        assertTrue(!scraping.payload().contains("\"distill\""), "默认关：不提炼");
        assertEquals(0, llm.distillCalls.get());
        String ctx = llm.chatUsers.stream().filter(u -> u.contains("Research context")).findFirst().orElse("");
        // 基线 = 开头 3000 截断：有开头无后段核心数据
        assertTrue(ctx.contains("网页开头介绍"), "截断基线应含开头");
        assertFalse(ctx.contains("CORE_DATA_TAIL_42"), "后段核心数据在截断基线中丢失");
    }

    @Test
    void distillFailureFallsBackToTruncatedOriginal() {
        DistillLlm llm = new DistillLlm();
        llm.distillJson = "garbage output"; // 坏输出 → 回退
        LongScraper scraper = new LongScraper();
        ResearchEngine engine = engine(llm, scraper, "{\"sourceDistill\":true}");

        StageResult scraping = runThrough(engine);

        assertTrue(scraping.payload().contains("\"distill\":0")
                && scraping.payload().contains("\"distillFallback\":1"),
                "失败回退: " + scraping.payload());
        String ctx = llm.chatUsers.stream().filter(u -> u.contains("Research context")).findFirst().orElse("");
        assertTrue(ctx.contains("网页开头介绍"), "回退=截断原文");
    }
}
