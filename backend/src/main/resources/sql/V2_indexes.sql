-- 性能与告警热点索引（在已有库上手动执行；若索引已存在会报错，可忽略）
-- 与 JPA 实体 PerformanceData / Alarm 字段对齐

USE ensp_nms;

-- 告警
CREATE INDEX idx_alarm_occurred_at ON alarm (occurred_at);
CREATE INDEX idx_alarm_status_occurred ON alarm (status, occurred_at);
CREATE INDEX idx_alarm_device_ip ON alarm (device_ip);
CREATE INDEX idx_alarm_severity ON alarm (severity);
CREATE INDEX idx_alarm_trap_type ON alarm (trap_type);
CREATE INDEX idx_alarm_last_occurred ON alarm (last_occurred_at);

-- 性能数据（实体宽表：cpu/memory/port_* 等列）
CREATE INDEX idx_perf_device_ts ON performance_data (device_id, timestamp);
CREATE INDEX idx_perf_device_port_ts ON performance_data (device_id, port_index, timestamp);
CREATE INDEX idx_perf_timestamp ON performance_data (timestamp);

-- 性能告警
CREATE INDEX idx_perf_alert_device_status ON performance_alerts (device_id, status);
CREATE INDEX idx_perf_alert_created ON performance_alerts (created_at);
