package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.Map;

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

    /** router|switch|firewall|ac|ap|pc|server|other */
    @Column(name = "device_type", length = 20)
    private String deviceType = "other";

    /** snmp|icmp|auto */
    @Column(name = "monitor_mode", length = 20)
    private String monitorMode = "auto";

    /** 最近一次状态探测方式：snmp / icmp */
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

    /**
     * 远程管理协议：ssh | telnet
     * <p>AC6005 在 eNSP 中 SSH 服务异常（KEXINIT 后无响应），AC 类设备统一走 telnet。</p>
     */
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

    /** 所属工程（网络工程档案），全软件共享的工程上下文 */
    @Column(name = "project_id")
    private Long projectId;

    /** 机房/机柜/位置等资产信息 */
    @Column(length = 200)
    private String location;

    /** 运维联系人 */
    @Column(length = 100)
    private String contact;

    /** 序列号 */
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

