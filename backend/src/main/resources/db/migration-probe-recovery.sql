-- 生产 ddl-auto=validate 时手动执行（开发环境 hibernate update 会自动加列）
-- MySQL 8：若列已存在会报错，可忽略
ALTER TABLE alarm ADD COLUMN clear_note TEXT NULL;
ALTER TABLE aiops_policy_settings ADD COLUMN online_after_successes INT NULL DEFAULT 2;
ALTER TABLE aiops_policy_settings ADD COLUMN offline_after_failures INT NULL DEFAULT 2;
