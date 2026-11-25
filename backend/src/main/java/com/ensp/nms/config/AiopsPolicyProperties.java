package com.ensp.nms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AIOps运行时策略参数，支持yml与运行时API调整。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Data
@Component
@ConfigurationProperties(prefix = "nms.aiops")
public class AiopsPolicyProperties {

    private int stormWindowMinutes = 10;
    private int linkWindowMinutes = 15;

    private int anomalyLookbackHours = 24;
    private double anomalyKSigma = 2.2;
    private double anomalyMinAbsDelta = 12.0;
    private int anomalySuppressMinutes = 30;
    private boolean analyzeSimulatedMetrics = false;
    private int anomalyMinSamples = 5;
    private boolean autoAckSecondary = false;

    private boolean webhookEnabled = false;
    private String webhookUrl = "";
    private String webhookMinSeverity = "MAJOR";

    private String llmOpsMode = "manual";
    private int unattendedMaxPerCycle = 3;
    private int unattendedMaxSteps = 3;
    private int unattendedCooldownMinutes = 10;
    private boolean unattendedOnCorrelate = false;
    private boolean unattendedAllowBackup = false;
    private boolean unattendedPaused = false;

    private int llmCircuitFailThreshold = 3;
    private int llmCircuitMinutes = 15;
    private int unattendedRunRetentionDays = 30;

    private boolean escalationEnabled = false;
    private int escalationMinutes = 30;
    private boolean escalationNotify = true;

    public boolean isUnattendedMode() {
        return "unattended".equalsIgnoreCase(llmOpsMode != null ? llmOpsMode.trim() : "");
    }
}
