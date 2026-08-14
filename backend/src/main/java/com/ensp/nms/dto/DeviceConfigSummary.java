package com.ensp.nms.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 配置备份列表项（不含 LONGTEXT content） */
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
