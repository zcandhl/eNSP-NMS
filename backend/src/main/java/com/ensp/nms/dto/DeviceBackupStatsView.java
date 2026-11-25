package com.ensp.nms.dto;

import java.time.LocalDateTime;

/**
 * 备份聚合投影
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

public interface DeviceBackupStatsView {
    Long getDeviceId();

    Long getBackupCount();

    LocalDateTime getLastBackupAt();
}
