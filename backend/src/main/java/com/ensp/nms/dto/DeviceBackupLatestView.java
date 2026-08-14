package com.ensp.nms.dto;

import java.time.LocalDateTime;

/** 每设备最新一份备份的元数据（不含全文） */
public interface DeviceBackupLatestView {
    Long getDeviceId();

    String getConfigType();

    String getConfigVersion();

    LocalDateTime getCreatedAt();
}
