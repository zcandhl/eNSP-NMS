package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 备份计划实体，保存周期策略与目标设备。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Data
@Entity
@Table(name = "backup_schedule")
public class BackupSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_name")
    private String deviceName;

    @Column(name = "schedule_type", length = 20)
    private String scheduleType; // daily, weekly, monthly

    @Column(name = "schedule_time", length = 10)
    private String scheduleTime; // e.g., "02:00"

    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    @Column(name = "config_type", length = 20)
    private String configType; // running, startup

    @Column(name = "source_group_id")
    private Long sourceGroupId;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "last_run")
    private LocalDateTime lastRun;

    @Column(name = "last_status")
    private String lastStatus;

    @Column(columnDefinition = "TEXT")
    private String lastResult;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
