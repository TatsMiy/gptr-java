-- OBS-1：观测台索引（任务列表排序 + stats 近 24h 过滤/状态分组共用）
CREATE INDEX IF NOT EXISTS idx_tasks_status_created ON tasks (status, created_at DESC);
