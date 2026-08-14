package com.ensp.nms.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "aiops_unattended_run", indexes = {
        @Index(name = "idx_unattended_run_started", columnList = "started_at"),
        @Index(name = "idx_unattended_run_alarm", columnList = "alarm_id")
})
public class AiopsUnattendedRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** inspect / correlate / api / focus / retry */
    @Column(length = 40)
    private String triggerSource;

    @Column(name = "alarm_id")
    private Long alarmId;

    @Column(name = "device_id")
    private Long deviceId;

    /** llm / rules / breaker */
    @Column(length = 20, nullable = false)
    private String planSource = "rules";

    /** running / success / failed / skipped */
    @Column(length = 20, nullable = false)
    private String status = "running";

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "steps_ran")
    private Integer stepsRan = 0;

    @Column(name = "round_no")
    private Integer roundNo = 1;

    @Column(length = 80)
    private String operator;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    void onCreate() {
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
    public Long getAlarmId() { return alarmId; }
    public void setAlarmId(Long alarmId) { this.alarmId = alarmId; }
    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long deviceId) { this.deviceId = deviceId; }
    public String getPlanSource() { return planSource; }
    public void setPlanSource(String planSource) { this.planSource = planSource; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getStepsRan() { return stepsRan; }
    public void setStepsRan(Integer stepsRan) { this.stepsRan = stepsRan; }
    public Integer getRoundNo() { return roundNo; }
    public void setRoundNo(Integer roundNo) { this.roundNo = roundNo; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
