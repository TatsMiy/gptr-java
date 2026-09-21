-- V5：LangGraph4j 图内 checkpoint 持久化（图内状态层）
-- 按 thread_id 存最新 checkpoint（upsert）；state 为 JSON（PoC 阶段状态为 JSON 友好类型）
-- IF NOT EXISTS：兼容 PostgresCheckpointSaverTest 的自建表（幂等，避免测试/应用冲突）
CREATE TABLE IF NOT EXISTS graph_checkpoints (
    thread_id     varchar(64) PRIMARY KEY,
    checkpoint_id varchar(64) NOT NULL,
    state_json    jsonb NOT NULL,
    node_id       varchar(64),
    next_node_id  varchar(64),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
