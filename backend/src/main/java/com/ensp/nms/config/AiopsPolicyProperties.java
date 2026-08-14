package com.ensp.nms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AIOps 策略参数（关联窗口、基线 k 等），支持 yml 与运行时 API 调整。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nms.aiops")
public class AiopsPolicyProperties {

    /** 告警风暴收敛时间桶（分钟） */
    private int stormWindowMinutes = 10;
    /** 链路两端关联时间窗（分钟） */
    private int linkWindowMinutes = 15;
    /** 基线 lookback 小时 */
    private int anomalyLookbackHours = 24;
    /** 基线 σ 倍数 */
    private double anomalyKSigma = 2.2;
    /** 相对均值最小绝对偏差 */
    private double anomalyMinAbsDelta = 12.0;
    /** 同类基线异常抑制窗口（分钟） */
    private int anomalySuppressMinutes = 30;
    /** 是否对仿真指标做基线/趋势分析（默认否） */
    private boolean analyzeSimulatedMetrics = false;
    /** 基线检测最少样本数（实验室可略降） */
    private int anomalyMinSamples = 5;
    /** 关联后自动确认连带告警（默认关） */
    private boolean autoAckSecondary = false;
    /** Webhook 推送开关 */
    private boolean webhookEnabled = false;
    /** Webhook URL */
    private String webhookUrl = "";
    /** 触发推送的最低严重级别：CRITICAL / MAJOR / WARNING / INFO */
    private String webhookMinSeverity = "MAJOR";

    /** 运维模式：manual=人工确认后执行；unattended=安全工具可自动执行 */
    private String llmOpsMode = "manual";
    /** 无人值守每轮最多处理代表事件数 */
    private int unattendedMaxPerCycle = 3;
    /** 无人值守单事件最多工具步数 */
    private int unattendedMaxSteps = 3;
    /** 同一告警自动处置冷却（分钟） */
    private int unattendedCooldownMinutes = 10;
    /** 定时关联后是否触发无人值守（默认关，仅巡检触发） */
    private boolean unattendedOnCorrelate = false;
    /** 无人值守是否允许自动备份（回滚永远不允许自动） */
    private boolean unattendedAllowBackup = false;
    /** 运营暂停：为 true 时不启动新的自动处置 */
    private boolean unattendedPaused = false;
    /** LLM 连续规划失败达到阈值后进入熔断（改走规则） */
    private int llmCircuitFailThreshold = 3;
    /** 熔断窗口（分钟） */
    private int llmCircuitMinutes = 15;
    /** 无人值守运行记录保留天数 */
    private int unattendedRunRetentionDays = 30;

    /** 超时告警自动升级开关（实验室） */
    private boolean escalationEnabled = false;
    /** 待处理告警持续超过该分钟数则升级一级 */
    private int escalationMinutes = 30;
    /** 升级后是否额外推送 Webhook（需 webhook 已启用） */
    private boolean escalationNotify = true;

    public boolean isUnattendedMode() {
        return "unattended".equalsIgnoreCase(llmOpsMode != null ? llmOpsMode.trim() : "");
    }

    public void applyFrom(AiopsPolicyProperties other) {
        if (other == null) {
            return;
        }
        if (other.stormWindowMinutes > 0) {
            this.stormWindowMinutes = other.stormWindowMinutes;
        }
        if (other.linkWindowMinutes > 0) {
            this.linkWindowMinutes = other.linkWindowMinutes;
        }
        if (other.anomalyLookbackHours > 0) {
            this.anomalyLookbackHours = other.anomalyLookbackHours;
        }
        if (other.anomalyKSigma > 0) {
            this.anomalyKSigma = other.anomalyKSigma;
        }
        if (other.anomalyMinAbsDelta >= 0) {
            this.anomalyMinAbsDelta = other.anomalyMinAbsDelta;
        }
        if (other.anomalySuppressMinutes > 0) {
            this.anomalySuppressMinutes = other.anomalySuppressMinutes;
        }
        this.analyzeSimulatedMetrics = other.analyzeSimulatedMetrics;
        if (other.anomalyMinSamples > 0) {
            this.anomalyMinSamples = other.anomalyMinSamples;
        }
        this.autoAckSecondary = other.autoAckSecondary;
        this.webhookEnabled = other.webhookEnabled;
        if (other.webhookUrl != null) {
            this.webhookUrl = other.webhookUrl;
        }
        if (other.webhookMinSeverity != null && !other.webhookMinSeverity.isBlank()) {
            this.webhookMinSeverity = other.webhookMinSeverity;
        }
        if (other.llmOpsMode != null && !other.llmOpsMode.isBlank()) {
            this.llmOpsMode = other.llmOpsMode.trim().toLowerCase();
        }
        if (other.unattendedMaxPerCycle > 0) {
            this.unattendedMaxPerCycle = other.unattendedMaxPerCycle;
        }
        if (other.unattendedMaxSteps > 0) {
            this.unattendedMaxSteps = other.unattendedMaxSteps;
        }
        if (other.unattendedCooldownMinutes > 0) {
            this.unattendedCooldownMinutes = other.unattendedCooldownMinutes;
        }
        this.unattendedOnCorrelate = other.unattendedOnCorrelate;
        this.unattendedAllowBackup = other.unattendedAllowBackup;
        this.unattendedPaused = other.unattendedPaused;
        if (other.llmCircuitFailThreshold > 0) {
            this.llmCircuitFailThreshold = other.llmCircuitFailThreshold;
        }
        if (other.llmCircuitMinutes > 0) {
            this.llmCircuitMinutes = other.llmCircuitMinutes;
        }
        if (other.unattendedRunRetentionDays > 0) {
            this.unattendedRunRetentionDays = other.unattendedRunRetentionDays;
        }
        this.escalationEnabled = other.escalationEnabled;
        if (other.escalationMinutes > 0) {
            this.escalationMinutes = other.escalationMinutes;
        }
        this.escalationNotify = other.escalationNotify;
    }
}
