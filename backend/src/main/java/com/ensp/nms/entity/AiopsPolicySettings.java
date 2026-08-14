package com.ensp.nms.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "aiops_policy_settings")
public class AiopsPolicySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storm_window_minutes", nullable = false)
    private Integer stormWindowMinutes = 10;

    @Column(name = "link_window_minutes", nullable = false)
    private Integer linkWindowMinutes = 15;

    @Column(name = "anomaly_lookback_hours", nullable = false)
    private Integer anomalyLookbackHours = 24;

    @Column(name = "anomaly_k_sigma", nullable = false)
    private Double anomalyKSigma = 2.2;

    @Column(name = "anomaly_min_abs_delta", nullable = false)
    private Double anomalyMinAbsDelta = 12.0;

    @Column(name = "anomaly_suppress_minutes", nullable = false)
    private Integer anomalySuppressMinutes = 30;

    @Column(name = "analyze_simulated_metrics", nullable = false)
    private Boolean analyzeSimulatedMetrics = false;

    @Column(name = "anomaly_min_samples", nullable = false)
    private Integer anomalyMinSamples = 5;

    @Column(name = "cpu_warning", nullable = false)
    private Double cpuWarning = 70.0;

    @Column(name = "cpu_danger", nullable = false)
    private Double cpuDanger = 85.0;

    @Column(name = "memory_warning", nullable = false)
    private Double memoryWarning = 75.0;

    @Column(name = "memory_danger", nullable = false)
    private Double memoryDanger = 90.0;

    @Column(name = "auto_ack_secondary", nullable = false)
    private Boolean autoAckSecondary = false;

    @Column(name = "webhook_enabled", nullable = false)
    private Boolean webhookEnabled = false;

    @Column(name = "webhook_url", length = 500)
    private String webhookUrl = "";

    @Column(name = "webhook_min_severity", length = 20)
    private String webhookMinSeverity = "MAJOR";

    /** 连续探测成功次数达到后判恢复 */
    @Column(name = "online_after_successes")
    private Integer onlineAfterSuccesses = 2;

    /** 连续探测失败次数达到后判离线 */
    @Column(name = "offline_after_failures")
    private Integer offlineAfterFailures = 2;

    /** manual | unattended */
    @Column(name = "llm_ops_mode", length = 20)
    private String llmOpsMode = "manual";

    @Column(name = "unattended_max_per_cycle")
    private Integer unattendedMaxPerCycle = 3;

    @Column(name = "unattended_max_steps")
    private Integer unattendedMaxSteps = 3;

    @Column(name = "unattended_cooldown_minutes")
    private Integer unattendedCooldownMinutes = 10;

    @Column(name = "unattended_on_correlate")
    private Boolean unattendedOnCorrelate = false;

    @Column(name = "unattended_allow_backup")
    private Boolean unattendedAllowBackup = false;

    @Column(name = "unattended_paused")
    private Boolean unattendedPaused = false;

    @Column(name = "llm_circuit_fail_threshold")
    private Integer llmCircuitFailThreshold = 3;

    @Column(name = "llm_circuit_minutes")
    private Integer llmCircuitMinutes = 15;

    @Column(name = "unattended_run_retention_days")
    private Integer unattendedRunRetentionDays = 30;

    @Column(name = "escalation_enabled", nullable = false)
    private Boolean escalationEnabled = false;

    @Column(name = "escalation_minutes")
    private Integer escalationMinutes = 30;

    @Column(name = "escalation_notify", nullable = false)
    private Boolean escalationNotify = true;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getStormWindowMinutes() { return stormWindowMinutes; }
    public void setStormWindowMinutes(Integer stormWindowMinutes) { this.stormWindowMinutes = stormWindowMinutes; }
    public Integer getLinkWindowMinutes() { return linkWindowMinutes; }
    public void setLinkWindowMinutes(Integer linkWindowMinutes) { this.linkWindowMinutes = linkWindowMinutes; }
    public Integer getAnomalyLookbackHours() { return anomalyLookbackHours; }
    public void setAnomalyLookbackHours(Integer anomalyLookbackHours) { this.anomalyLookbackHours = anomalyLookbackHours; }
    public Double getAnomalyKSigma() { return anomalyKSigma; }
    public void setAnomalyKSigma(Double anomalyKSigma) { this.anomalyKSigma = anomalyKSigma; }
    public Double getAnomalyMinAbsDelta() { return anomalyMinAbsDelta; }
    public void setAnomalyMinAbsDelta(Double anomalyMinAbsDelta) { this.anomalyMinAbsDelta = anomalyMinAbsDelta; }
    public Integer getAnomalySuppressMinutes() { return anomalySuppressMinutes; }
    public void setAnomalySuppressMinutes(Integer anomalySuppressMinutes) { this.anomalySuppressMinutes = anomalySuppressMinutes; }
    public Boolean getAnalyzeSimulatedMetrics() { return analyzeSimulatedMetrics; }
    public void setAnalyzeSimulatedMetrics(Boolean analyzeSimulatedMetrics) { this.analyzeSimulatedMetrics = analyzeSimulatedMetrics; }
    public Integer getAnomalyMinSamples() { return anomalyMinSamples; }
    public void setAnomalyMinSamples(Integer anomalyMinSamples) { this.anomalyMinSamples = anomalyMinSamples; }
    public Double getCpuWarning() { return cpuWarning; }
    public void setCpuWarning(Double cpuWarning) { this.cpuWarning = cpuWarning; }
    public Double getCpuDanger() { return cpuDanger; }
    public void setCpuDanger(Double cpuDanger) { this.cpuDanger = cpuDanger; }
    public Double getMemoryWarning() { return memoryWarning; }
    public void setMemoryWarning(Double memoryWarning) { this.memoryWarning = memoryWarning; }
    public Double getMemoryDanger() { return memoryDanger; }
    public void setMemoryDanger(Double memoryDanger) { this.memoryDanger = memoryDanger; }
    public Boolean getAutoAckSecondary() { return autoAckSecondary; }
    public void setAutoAckSecondary(Boolean autoAckSecondary) { this.autoAckSecondary = autoAckSecondary; }
    public Boolean getWebhookEnabled() { return webhookEnabled; }
    public void setWebhookEnabled(Boolean webhookEnabled) { this.webhookEnabled = webhookEnabled; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    public String getWebhookMinSeverity() { return webhookMinSeverity; }
    public void setWebhookMinSeverity(String webhookMinSeverity) { this.webhookMinSeverity = webhookMinSeverity; }
    public Integer getOnlineAfterSuccesses() { return onlineAfterSuccesses; }
    public void setOnlineAfterSuccesses(Integer onlineAfterSuccesses) { this.onlineAfterSuccesses = onlineAfterSuccesses; }
    public Integer getOfflineAfterFailures() { return offlineAfterFailures; }
    public void setOfflineAfterFailures(Integer offlineAfterFailures) { this.offlineAfterFailures = offlineAfterFailures; }
    public String getLlmOpsMode() { return llmOpsMode; }
    public void setLlmOpsMode(String llmOpsMode) { this.llmOpsMode = llmOpsMode; }
    public Integer getUnattendedMaxPerCycle() { return unattendedMaxPerCycle; }
    public void setUnattendedMaxPerCycle(Integer unattendedMaxPerCycle) { this.unattendedMaxPerCycle = unattendedMaxPerCycle; }
    public Integer getUnattendedMaxSteps() { return unattendedMaxSteps; }
    public void setUnattendedMaxSteps(Integer unattendedMaxSteps) { this.unattendedMaxSteps = unattendedMaxSteps; }
    public Integer getUnattendedCooldownMinutes() { return unattendedCooldownMinutes; }
    public void setUnattendedCooldownMinutes(Integer unattendedCooldownMinutes) { this.unattendedCooldownMinutes = unattendedCooldownMinutes; }
    public Boolean getUnattendedOnCorrelate() { return unattendedOnCorrelate; }
    public void setUnattendedOnCorrelate(Boolean unattendedOnCorrelate) { this.unattendedOnCorrelate = unattendedOnCorrelate; }
    public Boolean getUnattendedAllowBackup() { return unattendedAllowBackup; }
    public void setUnattendedAllowBackup(Boolean unattendedAllowBackup) { this.unattendedAllowBackup = unattendedAllowBackup; }
    public Boolean getUnattendedPaused() { return unattendedPaused; }
    public void setUnattendedPaused(Boolean unattendedPaused) { this.unattendedPaused = unattendedPaused; }
    public Integer getLlmCircuitFailThreshold() { return llmCircuitFailThreshold; }
    public void setLlmCircuitFailThreshold(Integer llmCircuitFailThreshold) { this.llmCircuitFailThreshold = llmCircuitFailThreshold; }
    public Integer getLlmCircuitMinutes() { return llmCircuitMinutes; }
    public void setLlmCircuitMinutes(Integer llmCircuitMinutes) { this.llmCircuitMinutes = llmCircuitMinutes; }
    public Integer getUnattendedRunRetentionDays() { return unattendedRunRetentionDays; }
    public void setUnattendedRunRetentionDays(Integer unattendedRunRetentionDays) { this.unattendedRunRetentionDays = unattendedRunRetentionDays; }
    public Boolean getEscalationEnabled() { return escalationEnabled; }
    public void setEscalationEnabled(Boolean escalationEnabled) { this.escalationEnabled = escalationEnabled; }
    public Integer getEscalationMinutes() { return escalationMinutes; }
    public void setEscalationMinutes(Integer escalationMinutes) { this.escalationMinutes = escalationMinutes; }
    public Boolean getEscalationNotify() { return escalationNotify; }
    public void setEscalationNotify(Boolean escalationNotify) { this.escalationNotify = escalationNotify; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
