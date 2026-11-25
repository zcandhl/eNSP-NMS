package com.ensp.nms.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备配置备份健康态
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Data
public class DeviceBackupHealth {
    private Long deviceId;
    private String deviceName;
    private String ipAddress;
    private String status; // online/offline/...
    private long backupCount;
    private LocalDateTime lastBackupAt;
    private String lastBackupType;
    private String lastBackupVersion;
    private String scheduleStatus; // success/failed/null
    private LocalDateTime scheduleLastRun;
    private String health;
    private String healthLabel;
}
