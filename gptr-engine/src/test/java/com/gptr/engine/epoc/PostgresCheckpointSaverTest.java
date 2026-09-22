package com.gptr.engine.epoc;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import com.gptr.engine.testsupport.IntegrationDbGuard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收（二）：Postgres checkpoint saver（LangGraph4j Saver SPI → Postgres）。
 *
 * <p>连接 docker compose 的本地 Postgres；测试前自建 graph_checkpoints 表
 * （IF NOT EXISTS，不依赖 Flyway 已运行）。验证：put/get 往返、图续跑恢复。
 */
@Tag("integration")
class PostgresCheckpointSaverTest {

    private static JdbcTemplate jdbc;
    private static PostgresCheckpointSaver saver;

    @BeforeAll
    static void setup() {
        // 连测试库：优先取 -Pintegration 经 surefire 注入的 spring.datasource.url，
        // 缺省也落到 *_test（本测试**不走 Spring context**，必须自行读系统属性；
        // 硬编码 gptr 会被 IntegrationDbGuard 拒绝）
        DriverManagerDataSource ds = new DriverManagerDataSource(
                System.getProperty("spring.datasource.url",
                        "jdbc:postgresql://localhost:5432/gptr_test"), "gptr", "gptr");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS graph_checkpoints (
                    thread_id     varchar(64) PRIMARY KEY,
                    checkpoint_id varchar(64) NOT NULL,
                    state_json    jsonb NOT NULL,
                    node_id       varchar(64),
                    next_node_id  varchar(64),
                    updated_at    timestamptz NOT NULL DEFAULT now()
                )""");
        saver = new PostgresCheckpointSaver(jdbc);
    }

    @BeforeEach
    void clear() {
        // 只清数据不清表（表由 Flyway V5 或本测试自建，勿 DROP 破坏迁移一致性）
        IntegrationDbGuard.truncateCheckpoints(jdbc);
    }

    private static BiFunction<String, String, String> mockLlm() {
        return (system, user) -> system.contains("generating search queries")
                ? "[{\"query\":\"q1\",\"researchGoal\":\"g1\"}]"
                : "{\"learnings\":[{\"insight\":\"L\",\"sourceUrl\":\"https://l\"}],\"followUpQuestions\":[\"f\"]}";
    }

    @Test
    void putGetRoundTrip() throws Exception {
        RunnableConfig config = RunnableConfig.builder().threadId("thread-1").build();
        Checkpoint cp = Checkpoint.builder()
                .id("cp-1")
                .state(Map.of("query", "topic", "learnings", List.of("a", "b"), "currentDepth", 2))
                .nodeId("extract_learnings")
                .nextNodeId("__END__")
                .build();

        saver.put(config, cp);
        Optional<Checkpoint> loaded = saver.get(config);

        assertTrue(loaded.isPresent());
        assertEquals("cp-1", loaded.get().getId());
        assertEquals(List.of("a", "b"), loaded.get().getState().get("learnings"));
        assertEquals(2, loaded.get().getState().get("currentDepth"));
        assertEquals("extract_learnings", loaded.get().getNodeId());
    }

    @Test
    void graphPersistsCheckpointAndResumes() throws Exception {
        RunnableConfig config = RunnableConfig.builder().threadId("thread-resume").build();

        // 第一次完整跑（depth=2）
        CompiledGraph<DeepResearchState> graph1 = DeepResearchGraph.buildCompiled(mockLlm(), saver);
        DeepResearchState done = graph1.invoke(
                Map.of("query", "t", "breadth", 1, "depth", 2), config).orElseThrow();
        assertEquals(2, done.currentDepth());

        // checkpoint 已持久化：新 saver 实例（模拟新进程/worker）能读到
        PostgresCheckpointSaver freshSaver = new PostgresCheckpointSaver(jdbc);
        Optional<Checkpoint> persisted = freshSaver.get(config);
        assertTrue(persisted.isPresent(), "checkpoint must survive across saver instances");
        assertEquals(2, persisted.get().getState().get("currentDepth"),
                "persisted state must reflect completed depth");

        // 同 threadId 再次 invoke：从 checkpoint 恢复（状态继承已累积的 learnings）
        CompiledGraph<DeepResearchState> graph2 = DeepResearchGraph.buildCompiled(mockLlm(), freshSaver);
        DeepResearchState resumed = graph2.invoke(Map.of(), config).orElseThrow();
        assertTrue(resumed.learnings().size() >= 1,
                "resumed graph must carry over prior learnings, got " + resumed.learnings());
    }
}
