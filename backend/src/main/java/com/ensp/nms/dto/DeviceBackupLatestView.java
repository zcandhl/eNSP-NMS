package com.ensp.nms.dto;

import java.time.LocalDateTime;

/**
 * 每设备最新一份备份的元数据
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

public interface DeviceBackupLatestView {
    Long getDeviceId();

    String getConfigType();

    String getConfigVersion();

    LocalDateTime getCreatedAt();
}
