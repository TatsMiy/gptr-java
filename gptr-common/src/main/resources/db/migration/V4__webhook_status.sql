-- V4：webhook_deliveries 增加投递状态与负载列
ALTER TABLE webhook_deliveries ADD COLUMN status varchar(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE webhook_deliveries ADD COLUMN payload text;
ALTER TABLE webhook_deliveries ADD COLUMN last_success_at timestamptz;
