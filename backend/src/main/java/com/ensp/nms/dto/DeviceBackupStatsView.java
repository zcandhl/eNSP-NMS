package com.ensp.nms.dto;

import java.time.LocalDateTime;

/** 备份聚合投影（不含配置全文） */
public interface DeviceBackupStatsView {
    Long getDeviceId();

    Long getBackupCount();

    LocalDateTime getLastBackupAt();
}
