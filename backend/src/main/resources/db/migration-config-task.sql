-- 生产 ddl-auto=validate 时手动执行（开发环境 hibernate update 会自动建表）
CREATE TABLE IF NOT EXISTS config_task (
  id VARCHAR(64) NOT NULL PRIMARY KEY,
  type VARCHAR(32) NOT NULL,
  label VARCHAR(255) NULL,
  operator VARCHAR(100) NULL,
  target_count INT NULL,
  status VARCHAR(32) NOT NULL,
  progress INT NULL,
  message VARCHAR(500) NULL,
  error TEXT NULL,
  result_json LONGTEXT NULL,
  request_json LONGTEXT NULL,
  cancel_requested TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  finished_at DATETIME NULL,
  updated_at DATETIME NULL,
  INDEX idx_config_task_created (created_at),
  INDEX idx_config_task_status (status)
);
