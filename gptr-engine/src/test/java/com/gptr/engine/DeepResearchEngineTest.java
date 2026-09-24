package com.gptr.engine;

import com.gptr.common.engine.ResearchEngine;
import com.gptr.common.engine.StageResult;
import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskStage;
import com.gptr.engine.context.ContextManager;
import com.gptr.engine.epoc.PostgresCheckpointSaver;
import com.gptr.engine.plan.SubQueryPlanner;
import com.gptr.engine.search.Searcher;
import com.gptr.integration.client.LlmClient;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
import com.gptr.integration.client.mock.MockLlmClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import com.gptr.engine.testsupport.IntegrationDbGuard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎级测试：deep research 模式（LangGraph4j 真实图 + Postgres saver + 成本回写）。
 *
 * <p>直接构造 ResearchEngineImpl（不依赖 Spring context），连本地 Postgres 验证
 * 图 checkpoint saver；mock LLM（JSON 模式）+ mock 搜索驱动。
 */
@Tag("integration")
class DeepResearchEngineTest {

    private static PostgresCheckpointSaver saver;

    @BeforeAll
    static void setup() {
        // 连测试库：优先取 -Pintegration 经 surefire 注入的 spring.datasource.url，
        // 缺省也落到 *_test（本测试**不走 Spring context**，故 profile 的
        // systemPropertyVariables 必须自行读取；硬编码 gptr 会被 IntegrationDbGuard 拒绝，
        // 且曾真实清空过开发库的 graph_checkpoints）
        DriverManagerDataSource ds = new DriverManagerDataSource(
                System.getProperty("spring.datasource.url",
                        "jdbc:postgresql://localhost:5432/gptr_test"), "gptr", "gptr");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS graph_checkpoints (
                    thread_id     varchar(64) PRIMARY KEY,
                    checkpoint_id varchar(64) NOT NULL,
                    state_json    jsonb NOT NULL,
                    node_id       varchar(64),
                    next_node_id  varchar(64),
                    updated_at    timestamptz NOT NULL DEFAULT now()
                )""");
        IntegrationDbGuard.truncateCheckpoints(jdbc);
        saver = new PostgresCheckpointSaver(jdbc);
    }

    private static SearchClient mockSearch() {
        return new SearchClient() {
            @Override
            public String name() {
                return "mock";
            }

            @Override
            public SearchResponse search(String query) {
                return new SearchResponse(List.of(new SearchResult("r1", "https://mock.com/" + query.hashCode(), "snippet")), name());
            }
        };
    }

    @Test
    void deepResearchStagesAndCost() {
        ResearchTask task = new ResearchTask();
        task.setId(java.util.UUID.randomUUID());
        task.setQuery("deep topic");
        task.setConfig("{\"mode\":\"deep_research\",\"breadth\":2,\"depth\":2}");

        LlmClient llm = new MockLlmClient("llm", MockLlmClient.Mode.JSON);
        SearchClient search = mockSearch();
        ResearchEngine engine = new ResearchEngineImpl(
                task, new ResearchEngineImpl.EngineDeps(new SubQueryPlanner(llm), new Searcher(search),
                        llm, search, new com.gptr.engine.write.ReportWriter(llm, "中文"), null,
                        new ContextManager(), saver));

        // 阶段 = deep research 模式（PLANNING → RESEARCH → WRITING）
        assertEquals(List.of(TaskStage.PLANNING, TaskStage.RESEARCH, TaskStage.WRITING), engine.stages());

        StageResult research = null;
        for (TaskStage stage : engine.stages()) {
            StageResult r = engine.runStage(stage);
            if (stage == TaskStage.RESEARCH) {
                research = r;
            }
        }

        assertTrue(research != null, "RESEARCH stage must run");
        assertTrue(research.payload().contains("\"depthReached\":2"), "2 layers expected: " + research.payload());
        // per-query 提炼：breadth=2 × 每 query 2 learnings × 2 层 = 8
        assertTrue(research.payload().contains("\"learnings\":8"), "8 learnings (2 per query x 2 queries x 2 layers): " + research.payload());
        // JSON 模式 LLM 每次调用成本 0.001，图内多次调用累加
        assertTrue(research.costUsd() > 0, "graph LLM costs must be aggregated into stage cost");
    }

    @Test
    void deepResearchPersistsGraphCheckpoint() throws Exception {
        ResearchTask task = new ResearchTask();
        task.setId(java.util.UUID.randomUUID());
        task.setQuery("checkpoint topic");
        task.setConfig("{\"mode\":\"deep_research\",\"breadth\":1,\"depth\":1}");

        LlmClient llm = new MockLlmClient("llm", MockLlmClient.Mode.JSON);
        SearchClient search = mockSearch();
        ResearchEngine engine = new ResearchEngineImpl(
                task, new ResearchEngineImpl.EngineDeps(new SubQueryPlanner(llm), new Searcher(search),
                        llm, search, new com.gptr.engine.write.ReportWriter(llm, "中文"), null,
                        new ContextManager(), saver));

        for (TaskStage stage : engine.stages()) {
            engine.runStage(stage);
        }

        // 图 checkpoint 已持久化（threadId = taskId）
        var config = org.bsc.langgraph4j.RunnableConfig.builder()
                .threadId(task.getId().toString()).build();
        assertTrue(saver.get(config).isPresent(), "graph checkpoint must be persisted");
        assertEquals(1, saver.get(config).get().getState().get("currentDepth"));
    }

    @Test
    void flatModeKeepsFiveStages() {
        ResearchTask task = new ResearchTask();
        task.setQuery("flat");
        task.setConfig("{}");
        LlmClient llm = new MockLlmClient("llm", MockLlmClient.Mode.OK);
        SearchClient search = mockSearch();
        ResearchEngine engine = new ResearchEngineImpl(
                task, new ResearchEngineImpl.EngineDeps(new SubQueryPlanner(llm), new Searcher(search),
                        llm, search, new com.gptr.engine.write.ReportWriter(llm, "中文"), null,
                        new ContextManager(), null));
        assertEquals(5, engine.stages().size(), "non-deep mode keeps five stages");
    }
}
