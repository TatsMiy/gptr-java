-- ============================================================
-- gptr-java · V1 初始 schema
-- 说明：状态/事件枚举在 DB 层用 varchar + CHECK，而非 Postgres 原生 ENUM。
-- 原因：Hibernate 6 对原生 ENUM 列的读写存在兼容性细节，varchar + CHECK
-- 行为等价且是 Hibernate 最成熟稳定的映射路径（@Enumerated(EnumType.STRING)）。
-- ============================================================

CREATE TABLE tasks (
    id                  uuid PRIMARY KEY,
    client_key          varchar(64) UNIQUE,
    query               text NOT NULL,
    config              jsonb NOT NULL DEFAULT '{}',
    status              varchar(20) NOT NULL DEFAULT 'PENDING',
    priority            smallint NOT NULL DEFAULT 50,
    attempt             int NOT NULL DEFAULT 0,
    max_attempts        int NOT NULL DEFAULT 3,
    step_budget         int NOT NULL DEFAULT 100,
    time_budget_seconds int NOT NULL DEFAULT 1800,
    cost_budget_usd     numeric(10,4) NOT NULL DEFAULT 10.0000,
    cost_spent_usd      numeric(12,6) NOT NULL DEFAULT 0,
    steps_used          int NOT NULL DEFAULT 0,
    started_at          timestamptz,
    finished_at         timestamptz,
    deadline_at         timestamptz NOT NULL,
    owner_token         uuid,
    lease_until         timestamptz,
    result_ref          text,
    partial_result      boolean NOT NULL DEFAULT false,
    error_code          text,
    error_detail        text,
    version             int NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_tasks_status CHECK (status IN
        ('PENDING','RUNNING','SUSPENDED','SUCCEEDED','FAILED','CANCELLED'))
);

-- 出队扫描索引（M2 使用）
CREATE INDEX idx_tasks_dequeue ON tasks (status, priority DESC, created_at)
    WHERE status = 'PENDING';

-- 租约回收扫描索引（M2 使用）
CREATE INDEX idx_tasks_lease ON tasks (status, lease_until)
    WHERE status = 'RUNNING';

CREATE TABLE task_events (
    id         bigserial PRIMARY KEY,
    task_id    uuid NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    seq        int NOT NULL,
    type       varchar(40) NOT NULL,
    stage      varchar(20),
    payload    jsonb,           -- 含 traceId / spanId 预留字段
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (task_id, seq),
    CONSTRAINT chk_events_type CHECK (type IN
        ('CREATED','DISPATCHED','LEASE_EXPIRED','STAGE_STARTED','STAGE_COMPLETED',
         'RETRY','BUDGET_EXCEEDED','SUCCEEDED','FAILED','CANCELLED','WEBHOOK_SENT')),
    CONSTRAINT chk_events_stage CHECK (stage IS NULL OR stage IN
        ('PLANNING','SEARCHING','SCRAPING','SUMMARIZING','WRITING'))
);

CREATE TABLE checkpoints (
    task_id    uuid PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE,
    stage      varchar(20) NOT NULL,
    payload    jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_checkpoints_stage CHECK (stage IN
        ('PLANNING','SEARCHING','SCRAPING','SUMMARIZING','WRITING'))
);

CREATE TABLE webhook_deliveries (
    id              bigserial PRIMARY KEY,
    task_id         uuid NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    url             text NOT NULL,
    attempts        int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_status     int,
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
