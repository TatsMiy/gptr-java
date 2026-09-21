-- V2：priority 列类型 smallint → integer
-- 原因：ResearchTask.priority 为 Java int（Hibernate 映射 Types.INTEGER），
-- 与 V1 的 smallint 不匹配，Hibernate validate 模式启动失败。
ALTER TABLE tasks ALTER COLUMN priority TYPE integer;
