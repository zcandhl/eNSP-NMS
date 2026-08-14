package com.ensp.nms.dto;

import lombok.Data;

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
    /** snmp | arp_endpoint */
    private String discoverSource;
    private String macAddress;
    private String learnedFromDevice;
    private Long learnedFromDeviceId;
}
