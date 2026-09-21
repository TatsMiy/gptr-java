-- V9：OBS-3 全局运行配置（app_config：worker 启动时覆盖默认，保存后重启生效，无热加载）
CREATE TABLE app_config (
    key        varchar(64) PRIMARY KEY,
    value      text NOT NULL,
    secret     boolean NOT NULL DEFAULT false,   -- secret 行 API 永不回显明文（仅"已设置"态）
    version    int NOT NULL DEFAULT 0,           -- 每次更新 +1（变更审计）
    updated_at timestamptz NOT NULL DEFAULT now()
);
