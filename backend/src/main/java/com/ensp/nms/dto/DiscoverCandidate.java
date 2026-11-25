package com.ensp.nms.dto;

import lombok.Data;

/**
 * 扫描发现的候选设备条目。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Data
public class DiscoverCandidate {
    private String ipAddress;
    private String name;
    private String description;
    private String vendor;
    private String model;
    private String deviceType;
    private String monitorMode;
    private String snmpVersion = "v2c";
    private String snmpCommunity;
    private Integer snmpPort;
    private boolean alreadyExists;
    private Long existingDeviceId;
    private String discoverSource;
    private String macAddress;
    private String learnedFromDevice;
    private Long learnedFromDeviceId;
}
