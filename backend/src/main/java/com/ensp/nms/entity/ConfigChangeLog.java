package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 配置变更日志实体，记录前后配置与变更结论。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15
 */

@Data
@Entity
@Table(name = "config_change_log")
public class ConfigChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_name", length = 100)
    private String deviceName;

    @Column(name = "change_type", nullable = false, length = 50)
    private String changeType;

    @Column(name = "before_version", length = 50)
    private String beforeVersion;

    @Column(name = "after_version", length = 50)
    private String afterVersion;

    @Column(columnDefinition = "TEXT")
    private String commands;

    @Column(length = 50)
    private String operator;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(nullable = false, length = 20)
    private String status = "pending";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
