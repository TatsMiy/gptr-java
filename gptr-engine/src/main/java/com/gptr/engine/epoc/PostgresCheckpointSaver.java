package com.gptr.engine.epoc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LangGraph4j checkpoint 的 Postgres 实现（BaseCheckpointSaver SPI）。
 *
 * <p>存储方式：checkpoint 的 {@code state}（Map）序列化为 JSON（PoC 状态均为
 * JSON 友好类型：String/List/Integer）；恢复时用 {@link Checkpoint.Builder} 重建。
 * 按 thread_id upsert 最新 checkpoint（单行），支撑 worker 崩溃后从图状态续跑。
 *
 * <p>表：{@code graph_checkpoints}（V5 迁移）。
 */
public class PostgresCheckpointSaver implements BaseCheckpointSaver {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public PostgresCheckpointSaver(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        String threadId = config.threadId().orElseThrow(() ->
                new IllegalStateException("threadId is required to list checkpoints"));
        return jdbc.query(
                "SELECT checkpoint_id, state_json, node_id, next_node_id FROM graph_checkpoints WHERE thread_id = ?",
                (rs, i) -> toCheckpoint(rs.getString("checkpoint_id"), rs.getString("state_json"),
                        rs.getString("node_id"), rs.getString("next_node_id")),
                threadId);
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        String threadId = config.threadId().orElseThrow(() ->
                new IllegalStateException("threadId is required to get checkpoint"));
        List<Checkpoint> found = jdbc.query(
                "SELECT checkpoint_id, state_json, node_id, next_node_id FROM graph_checkpoints WHERE thread_id = ?",
                (rs, i) -> toCheckpoint(rs.getString("checkpoint_id"), rs.getString("state_json"),
                        rs.getString("node_id"), rs.getString("next_node_id")),
                threadId);
        return found.stream().findFirst();
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        String threadId = config.threadId().orElseGet(checkpoint::getId);
        String stateJson = mapper.writeValueAsString(checkpoint.getState());
        jdbc.update("""
                INSERT INTO graph_checkpoints (thread_id, checkpoint_id, state_json, node_id, next_node_id, updated_at)
                VALUES (?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (thread_id) DO UPDATE SET
                    checkpoint_id = EXCLUDED.checkpoint_id,
                    state_json = EXCLUDED.state_json,
                    node_id = EXCLUDED.node_id,
                    next_node_id = EXCLUDED.next_node_id,
                    updated_at = EXCLUDED.updated_at
                """,
                threadId, checkpoint.getId(), stateJson, checkpoint.getNodeId(),
                checkpoint.getNextNodeId(), Timestamp.from(OffsetDateTime.now().toInstant()));
        return config;
    }

    @Override
    public Tag release(RunnableConfig config) {
        return null; // PoC：无需释放标记
    }

    /** 删除某 thread 的全部 checkpoint（任务重试/回队重执行前调用，
     *  避免 LangGraph4j 对同 thread 二次 invoke 的"旧状态播种 + 全图重放"导致
     *  learnings 残留重复与成本双计）。 */
    public void delete(String threadId) {
        jdbc.update("DELETE FROM graph_checkpoints WHERE thread_id = ?", threadId);
    }

    private Checkpoint toCheckpoint(String id, String stateJson, String nodeId, String nextNodeId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> state = mapper.readValue(stateJson, Map.class);
            return Checkpoint.builder()
                    .id(id)
                    .state(state)
                    .nodeId(nodeId)
                    .nextNodeId(nextNodeId)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize checkpoint " + id, e);
        }
    }
}
