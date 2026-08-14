package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 设备接口/环回等非纳管主 IP，用于 Trap agent 地址回映射到管理 IP。
 */
@Data
@Entity
@Table(name = "device_ip_alias",
        uniqueConstraints = @UniqueConstraint(name = "uk_device_ip_alias_ip", columnNames = "ip_address"),
        indexes = @Index(name = "idx_device_ip_alias_device", columnList = "device_id"))
public class DeviceIpAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
