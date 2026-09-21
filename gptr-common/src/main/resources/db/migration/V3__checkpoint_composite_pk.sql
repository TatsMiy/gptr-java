-- V3：checkpoints 主键 task_id → (task_id, stage)
-- 原因：设计意图是"每完成一个阶段写一条 checkpoint"（五阶段最多 5 条，断点续跑用），
-- V1 误将 task_id 设为主键导致每任务仅一条记录。复合主键 (task_id, stage) 修正。
ALTER TABLE checkpoints DROP CONSTRAINT checkpoints_pkey;
ALTER TABLE checkpoints ADD PRIMARY KEY (task_id, stage);
