package com.ensp.nms.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 告警实体，记录设备告警内容、级别与处理状态。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Entity
@Table(name = "alarm")
public class Alarm {

    public enum Severity {
        CRITICAL,
        MAJOR,
        MINOR,
        WARNING,
        INFO,
        CLEARED
    }

    public enum Status {
        ACTIVE,
        ACKNOWLEDGED,
        CLEARED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    @JsonIgnore
    private Device device;

    @Column(name = "device_id", insertable = false, updatable = false)
    private Long deviceId;

    @Column(name = "device_ip", length = 64)
    private String deviceIp;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "trap_oid", length = 255)
    private String trapOid;

    @Column(name = "trap_type", length = 100)
    private String trapType;

    @Column(columnDefinition = "TEXT")
    private String rawData;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledged_by", length = 100)
    private String acknowledgedBy;

    @Column(name = "acknowledge_note", columnDefinition = "TEXT")
    private String acknowledgeNote;

    @Column(name = "cleared_at")
    private LocalDateTime clearedAt;

    @Column(name = "clear_note", columnDefinition = "TEXT", nullable = true)
    private String clearNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "repeat_count")
    private Integer repeatCount = 1;

    @Column(name = "last_occurred_at")
    private LocalDateTime lastOccurredAt;

    @Column(name = "parent_alarm_id")
    private Long parentAlarmId;

    @Column(name = "correlation_type", length = 32)
    private String correlationType;

    @Column(name = "secondary_alarm")
    private Boolean secondaryAlarm = false;

    @Column(name = "correlation_note", length = 500)
    private String correlationNote;

    @Transient
    private Boolean ackCloses;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
        if (lastOccurredAt == null) {
            lastOccurredAt = occurredAt;
        }
        if (repeatCount == null) {
            repeatCount = 1;
        }
        if (status == null) {
            status = Status.ACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Alarm() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Device getDevice() {
        return device;
    }

    public void setDevice(Device device) {
        this.device = device;
        if (device != null && device.getId() != null) {
            this.deviceId = device.getId();
        }
    }

    // 优先用列映射，避免会话外访问懒加载device
    public Long getDeviceId() {
        if (deviceId != null) {
            return deviceId;
        }
        return device != null ? device.getId() : null;
    }

    public void setDeviceId(Long deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceName() {
        return device != null ? device.getName() : null;
    }

    public String getDeviceIp() {
        return deviceIp;
    }

    public void setDeviceIp(String deviceIp) {
        this.deviceIp = deviceIp;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getTrapOid() {
        return trapOid;
    }

    public void setTrapOid(String trapOid) {
        this.trapOid = trapOid;
    }

    public String getTrapType() {
        return trapType;
    }

    public void setTrapType(String trapType) {
        this.trapType = trapType;
    }

    public String getRawData() {
        return rawData;
    }

    public void setRawData(String rawData) {
        this.rawData = rawData;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public LocalDateTime getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(LocalDateTime acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public void setAcknowledgedBy(String acknowledgedBy) {
        this.acknowledgedBy = acknowledgedBy;
    }

    public String getAcknowledgeNote() {
        return acknowledgeNote;
    }

    public void setAcknowledgeNote(String acknowledgeNote) {
        this.acknowledgeNote = acknowledgeNote;
    }

    public LocalDateTime getClearedAt() {
        return clearedAt;
    }

    public void setClearedAt(LocalDateTime clearedAt) {
        this.clearedAt = clearedAt;
    }

    public String getClearNote() {
        return clearNote;
    }

    public void setClearNote(String clearNote) {
        this.clearNote = clearNote;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getRepeatCount() {
        return repeatCount;
    }

    public void setRepeatCount(Integer repeatCount) {
        this.repeatCount = repeatCount;
    }

    public LocalDateTime getLastOccurredAt() {
        return lastOccurredAt;
    }

    public void setLastOccurredAt(LocalDateTime lastOccurredAt) {
        this.lastOccurredAt = lastOccurredAt;
    }

    public Long getParentAlarmId() {
        return parentAlarmId;
    }

    public void setParentAlarmId(Long parentAlarmId) {
        this.parentAlarmId = parentAlarmId;
    }

    public String getCorrelationType() {
        return correlationType;
    }

    public void setCorrelationType(String correlationType) {
        this.correlationType = correlationType;
    }

    public Boolean getSecondaryAlarm() {
        return secondaryAlarm;
    }

    public void setSecondaryAlarm(Boolean secondaryAlarm) {
        this.secondaryAlarm = secondaryAlarm;
    }

    public boolean isSecondaryAlarm() {
        return Boolean.TRUE.equals(secondaryAlarm);
    }

    public String getCorrelationNote() {
        return correlationNote;
    }

    public void setCorrelationNote(String correlationNote) {
        this.correlationNote = correlationNote;
    }

    public Boolean getAckCloses() {
        return ackCloses;
    }

    public void setAckCloses(Boolean ackCloses) {
        this.ackCloses = ackCloses;
    }

    public boolean isAckCloses() {
        return Boolean.TRUE.equals(ackCloses);
    }
}
