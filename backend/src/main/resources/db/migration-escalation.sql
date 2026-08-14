-- 生产 ddl-auto=validate 时手动执行（开发环境 hibernate update 会自动加列）
-- MySQL：若列已存在会报错，可忽略
ALTER TABLE aiops_policy_settings ADD COLUMN escalation_enabled TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE aiops_policy_settings ADD COLUMN escalation_minutes INT NULL DEFAULT 30;
ALTER TABLE aiops_policy_settings ADD COLUMN escalation_notify TINYINT(1) NOT NULL DEFAULT 1;
