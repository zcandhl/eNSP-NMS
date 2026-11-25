package com.ensp.nms.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置备份列表项
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Data
public class DeviceConfigSummary {
    private Long id;
    private Long deviceId;
    private String configType;
    private String configVersion;
    private String description;
    private LocalDateTime createdAt;
    private String createdBy;
}
