-- V6：TaskStage 增加 RESEARCH（deep research 超阶段，E2）
-- 更新 checkpoints 与 task_events 的 stage CHECK 约束
ALTER TABLE checkpoints DROP CONSTRAINT chk_checkpoints_stage;
ALTER TABLE checkpoints ADD CONSTRAINT chk_checkpoints_stage CHECK (stage IN
    ('PLANNING','SEARCHING','SCRAPING','SUMMARIZING','WRITING','RESEARCH'));

ALTER TABLE task_events DROP CONSTRAINT chk_events_stage;
ALTER TABLE task_events ADD CONSTRAINT chk_events_stage CHECK (stage IS NULL OR stage IN
    ('PLANNING','SEARCHING','SCRAPING','SUMMARIZING','WRITING','RESEARCH'));
