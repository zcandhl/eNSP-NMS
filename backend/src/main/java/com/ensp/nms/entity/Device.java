package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 设备台账实体，保存纳管设备的连接信息与运行状态。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Data
@Entity
@Table(name = "device")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "device_type", length = 20)
    private String deviceType = "other";

    @Column(name = "monitor_mode", length = 20)
    private String monitorMode = "auto";

    @Column(name = "last_probe_method", length = 20)
    private String lastProbeMethod;

    @Transient
    private Map<String, Boolean> capabilities;

    @Column(length = 100)
    private String model;

    @Column(length = 50)
    private String vendor;

    @Column(name = "snmp_version", length = 10)
    private String snmpVersion = "v2c";

    @Column(name = "snmp_community", length = 50)
    private String snmpCommunity = "public";

    @Column(name = "snmp_port")
    private Integer snmpPort = 161;

    @Column(name = "ssh_username", length = 50)
    private String sshUsername;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(name = "ssh_password", length = 512)
    private String sshPassword;

    @Column(name = "ssh_port")
    private Integer sshPort = 22;

    @Column(name = "management_protocol", length = 10)
    private String managementProtocol = "ssh";

    public boolean isTelnetManagement() {
        return managementProtocol != null && "telnet".equalsIgnoreCase(managementProtocol.trim());
    }

    @Column(length = 20)
    private String status = "offline";

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(length = 200)
    private String location;

    @Column(length = 100)
    private String contact;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_seen")
    private LocalDateTime lastSeen;
}

