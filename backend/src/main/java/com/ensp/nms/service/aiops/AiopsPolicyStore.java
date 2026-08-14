package com.ensp.nms.service.aiops;

import com.ensp.nms.config.AiopsPolicyProperties;
import com.ensp.nms.config.NmsProbeProperties;
import com.ensp.nms.config.PerformanceThresholdProperties;
import com.ensp.nms.entity.AiopsPolicySettings;
import com.ensp.nms.repository.AiopsPolicySettingsRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AIOps 策略单行持久化：启动时加载到内存 Properties，PUT 时写库并同步内存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiopsPolicyStore {

    private final AiopsPolicySettingsRepository repository;
    private final AiopsPolicyProperties policyProperties;
    private final NmsProbeProperties probeProperties;
    private final PerformanceThresholdProperties thresholdProperties;

    @PostConstruct
    public void loadOnStartup() {
        try {
            applyEntityToRuntime(getOrCreate());
            log.info("已加载持久化 AIOps 策略（minSamples={}）", policyProperties.getAnomalyMinSamples());
        } catch (Exception e) {
            log.warn("加载 AIOps 策略失败，使用 yml 默认: {}", e.getMessage());
        }
    }

    @Transactional
    public AiopsPolicySettings getOrCreate() {
        return repository.findAll().stream().findFirst().orElseGet(() -> {
            AiopsPolicySettings s = fromRuntimeDefaults();
            return repository.save(s);
        });
    }

    @Transactional
    public AiopsPolicySettings saveFromRuntime() {
        AiopsPolicySettings s = getOrCreate();
        copyRuntimeToEntity(s);
        return repository.save(s);
    }

    public void applyEntityToRuntime(AiopsPolicySettings s) {
        if (s == null) {
            return;
        }
        if (s.getStormWindowMinutes() != null && s.getStormWindowMinutes() > 0) {
            policyProperties.setStormWindowMinutes(s.getStormWindowMinutes());
        }
        if (s.getLinkWindowMinutes() != null && s.getLinkWindowMinutes() > 0) {
            policyProperties.setLinkWindowMinutes(s.getLinkWindowMinutes());
        }
        if (s.getAnomalyLookbackHours() != null && s.getAnomalyLookbackHours() > 0) {
            policyProperties.setAnomalyLookbackHours(s.getAnomalyLookbackHours());
        }
        if (s.getAnomalyKSigma() != null && s.getAnomalyKSigma() > 0) {
            policyProperties.setAnomalyKSigma(s.getAnomalyKSigma());
        }
        if (s.getAnomalyMinAbsDelta() != null && s.getAnomalyMinAbsDelta() >= 0) {
            policyProperties.setAnomalyMinAbsDelta(s.getAnomalyMinAbsDelta());
        }
        if (s.getAnomalySuppressMinutes() != null && s.getAnomalySuppressMinutes() > 0) {
            policyProperties.setAnomalySuppressMinutes(s.getAnomalySuppressMinutes());
        }
        if (s.getAnalyzeSimulatedMetrics() != null) {
            policyProperties.setAnalyzeSimulatedMetrics(s.getAnalyzeSimulatedMetrics());
        }
        if (s.getAnomalyMinSamples() != null && s.getAnomalyMinSamples() > 0) {
            policyProperties.setAnomalyMinSamples(s.getAnomalyMinSamples());
        }
        if (s.getCpuWarning() != null) {
            thresholdProperties.getCpu().setWarning(s.getCpuWarning());
        }
        if (s.getCpuDanger() != null) {
            thresholdProperties.getCpu().setDanger(s.getCpuDanger());
        }
        if (s.getMemoryWarning() != null) {
            thresholdProperties.getMemory().setWarning(s.getMemoryWarning());
        }
        if (s.getMemoryDanger() != null) {
            thresholdProperties.getMemory().setDanger(s.getMemoryDanger());
        }
        if (s.getAutoAckSecondary() != null) {
            policyProperties.setAutoAckSecondary(s.getAutoAckSecondary());
        }
        if (s.getWebhookEnabled() != null) {
            policyProperties.setWebhookEnabled(s.getWebhookEnabled());
        }
        if (s.getWebhookUrl() != null) {
            policyProperties.setWebhookUrl(s.getWebhookUrl());
        }
        if (s.getWebhookMinSeverity() != null && !s.getWebhookMinSeverity().isBlank()) {
            policyProperties.setWebhookMinSeverity(s.getWebhookMinSeverity());
        }
        if (s.getOnlineAfterSuccesses() != null && s.getOnlineAfterSuccesses() > 0) {
            probeProperties.setOnlineAfterSuccesses(s.getOnlineAfterSuccesses());
        }
        if (s.getOfflineAfterFailures() != null && s.getOfflineAfterFailures() > 0) {
            probeProperties.setOfflineAfterFailures(s.getOfflineAfterFailures());
        }
        if (s.getLlmOpsMode() != null && !s.getLlmOpsMode().isBlank()) {
            policyProperties.setLlmOpsMode(s.getLlmOpsMode().trim().toLowerCase());
        }
        if (s.getUnattendedMaxPerCycle() != null && s.getUnattendedMaxPerCycle() > 0) {
            policyProperties.setUnattendedMaxPerCycle(s.getUnattendedMaxPerCycle());
        }
        if (s.getUnattendedMaxSteps() != null && s.getUnattendedMaxSteps() > 0) {
            policyProperties.setUnattendedMaxSteps(s.getUnattendedMaxSteps());
        }
        if (s.getUnattendedCooldownMinutes() != null && s.getUnattendedCooldownMinutes() > 0) {
            policyProperties.setUnattendedCooldownMinutes(s.getUnattendedCooldownMinutes());
        }
        if (s.getUnattendedOnCorrelate() != null) {
            policyProperties.setUnattendedOnCorrelate(s.getUnattendedOnCorrelate());
        }
        if (s.getUnattendedAllowBackup() != null) {
            policyProperties.setUnattendedAllowBackup(s.getUnattendedAllowBackup());
        }
        if (s.getUnattendedPaused() != null) {
            policyProperties.setUnattendedPaused(s.getUnattendedPaused());
        }
        if (s.getLlmCircuitFailThreshold() != null && s.getLlmCircuitFailThreshold() > 0) {
            policyProperties.setLlmCircuitFailThreshold(s.getLlmCircuitFailThreshold());
        }
        if (s.getLlmCircuitMinutes() != null && s.getLlmCircuitMinutes() > 0) {
            policyProperties.setLlmCircuitMinutes(s.getLlmCircuitMinutes());
        }
        if (s.getUnattendedRunRetentionDays() != null && s.getUnattendedRunRetentionDays() > 0) {
            policyProperties.setUnattendedRunRetentionDays(s.getUnattendedRunRetentionDays());
        }
        if (s.getEscalationEnabled() != null) {
            policyProperties.setEscalationEnabled(s.getEscalationEnabled());
        }
        if (s.getEscalationMinutes() != null && s.getEscalationMinutes() > 0) {
            policyProperties.setEscalationMinutes(s.getEscalationMinutes());
        }
        if (s.getEscalationNotify() != null) {
            policyProperties.setEscalationNotify(s.getEscalationNotify());
        }
        normalizeThresholds();
    }

    private AiopsPolicySettings fromRuntimeDefaults() {
        AiopsPolicySettings s = new AiopsPolicySettings();
        copyRuntimeToEntity(s);
        return s;
    }

    private void copyRuntimeToEntity(AiopsPolicySettings s) {
        s.setStormWindowMinutes(policyProperties.getStormWindowMinutes());
        s.setLinkWindowMinutes(policyProperties.getLinkWindowMinutes());
        s.setAnomalyLookbackHours(policyProperties.getAnomalyLookbackHours());
        s.setAnomalyKSigma(policyProperties.getAnomalyKSigma());
        s.setAnomalyMinAbsDelta(policyProperties.getAnomalyMinAbsDelta());
        s.setAnomalySuppressMinutes(policyProperties.getAnomalySuppressMinutes());
        s.setAnalyzeSimulatedMetrics(policyProperties.isAnalyzeSimulatedMetrics());
        s.setAnomalyMinSamples(Math.max(3, policyProperties.getAnomalyMinSamples()));
        s.setCpuWarning(thresholdProperties.getCpu().getWarning());
        s.setCpuDanger(thresholdProperties.getCpu().getDanger());
        s.setMemoryWarning(thresholdProperties.getMemory().getWarning());
        s.setMemoryDanger(thresholdProperties.getMemory().getDanger());
        s.setAutoAckSecondary(policyProperties.isAutoAckSecondary());
        s.setWebhookEnabled(policyProperties.isWebhookEnabled());
        s.setWebhookUrl(policyProperties.getWebhookUrl() != null ? policyProperties.getWebhookUrl() : "");
        s.setWebhookMinSeverity(policyProperties.getWebhookMinSeverity() != null
                ? policyProperties.getWebhookMinSeverity() : "MAJOR");
        s.setOnlineAfterSuccesses(Math.max(1, probeProperties.getOnlineAfterSuccesses()));
        s.setOfflineAfterFailures(Math.max(1, probeProperties.getOfflineAfterFailures()));
        s.setLlmOpsMode(policyProperties.getLlmOpsMode() != null ? policyProperties.getLlmOpsMode() : "manual");
        s.setUnattendedMaxPerCycle(Math.max(1, policyProperties.getUnattendedMaxPerCycle()));
        s.setUnattendedMaxSteps(Math.max(1, policyProperties.getUnattendedMaxSteps()));
        s.setUnattendedCooldownMinutes(Math.max(1, policyProperties.getUnattendedCooldownMinutes()));
        s.setUnattendedOnCorrelate(policyProperties.isUnattendedOnCorrelate());
        s.setUnattendedAllowBackup(policyProperties.isUnattendedAllowBackup());
        s.setUnattendedPaused(policyProperties.isUnattendedPaused());
        s.setLlmCircuitFailThreshold(Math.max(1, policyProperties.getLlmCircuitFailThreshold()));
        s.setLlmCircuitMinutes(Math.max(1, policyProperties.getLlmCircuitMinutes()));
        s.setUnattendedRunRetentionDays(Math.max(1, policyProperties.getUnattendedRunRetentionDays()));
        s.setEscalationEnabled(policyProperties.isEscalationEnabled());
        s.setEscalationMinutes(Math.max(1, policyProperties.getEscalationMinutes()));
        s.setEscalationNotify(policyProperties.isEscalationNotify());
    }

    private void normalizeThresholds() {
        var cpu = thresholdProperties.getCpu();
        if (cpu.getDanger() < cpu.getWarning()) {
            cpu.setDanger(cpu.getWarning());
        }
        var mem = thresholdProperties.getMemory();
        if (mem.getDanger() < mem.getWarning()) {
            mem.setDanger(mem.getWarning());
        }
    }
}
