package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "device_ip_alias",
        uniqueConstraints = @UniqueConstraint(name = "uk_device_ip_alias_ip", columnNames = "ip_address"),
        indexes = @Index(name = "idx_device_ip_alias_device", columnList = "device_id"))

/**
 * 设备 IP 别名实体，映射管理地址与业务接口地址。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

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
