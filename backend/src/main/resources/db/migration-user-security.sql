-- 生产 ddl-auto=validate 时手动执行（开发环境 hibernate update 会自动加列）
-- MySQL：若列已存在会报错，可忽略
ALTER TABLE app_user ADD COLUMN last_login_at DATETIME NULL;
ALTER TABLE app_user ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN locked_until DATETIME NULL;
