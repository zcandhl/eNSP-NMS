package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

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

    /** 每周：ISO 1=周一 … 7=周日；仅 weekly 有效 */
    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    /** 每月：1–31；仅 monthly 有效 */
    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    @Column(name = "config_type", length = 20)
    private String configType; // running, startup

    /** 由「设备分组策略」创建时记录来源组，便于成员变更后同步/清理 */
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
