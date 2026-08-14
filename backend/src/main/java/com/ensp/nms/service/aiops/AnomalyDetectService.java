package com.ensp.nms.service.aiops;

import com.ensp.nms.config.AiopsPolicyProperties;
import com.ensp.nms.config.PerformanceThresholdProperties;
import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.PerformanceData;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.PerformanceDataRepository;
import com.ensp.nms.service.AlarmService;
import com.ensp.nms.service.DeviceCapabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 滑动基线异常检测：均值 ± k·σ；固定阈值仍由 PerformanceAlertService 负责。
 * 默认忽略仿真指标，避免假数据污染 AIOps。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetectService {

    private final DeviceRepository deviceRepository;
    private final PerformanceDataRepository performanceDataRepository;
    private final DeviceCapabilityService deviceCapabilityService;
    private final AlarmService alarmService;
    private final AlarmRepository alarmRepository;
    private final PerformanceThresholdProperties thresholdProperties;
    private final AiopsPolicyProperties policyProperties;

    @Scheduled(fixedRate = 120000, initialDelay = 45000)
    public void scheduledDetect() {
        try {
            detectAll();
        } catch (Exception e) {
            log.warn("基线异常检测失败: {}", e.getMessage());
        }
    }

    private int minSamples() {
        return Math.max(3, policyProperties.getAnomalyMinSamples());
    }

    @Transactional
    public Map<String, Object> detectAll() {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusHours(Math.max(1, policyProperties.getAnomalyLookbackHours()));
        int checked = 0;
        int anomalies = 0;
        int skippedSimulated = 0;
        int skippedInsufficientSamples = 0;
        int minSamples = minSamples();
        List<Map<String, Object>> details = new ArrayList<>();

        for (Device device : deviceRepository.findAll()) {
            deviceCapabilityService.normalize(device);
            Map<String, Boolean> caps = deviceCapabilityService.resolveCapabilities(device);
            if (!Boolean.TRUE.equals(caps.get("performance"))) {
                continue;
            }
            if (!"online".equalsIgnoreCase(device.getStatus())) {
                continue;
            }
            checked++;
            List<PerformanceData> history = performanceDataRepository.findByDeviceIdAndTimestampBetween(
                    device.getId(), start, end);
            if (history.size() < minSamples) {
                skippedInsufficientSamples++;
                continue;
            }
            for (String metric : List.of("cpu", "memory")) {
                Map<String, Object> hit = evaluateMetric(device, history, metric);
                if (hit != null && Boolean.TRUE.equals(hit.get("skippedSimulated"))) {
                    skippedSimulated++;
                    continue;
                }
                if (hit != null && Boolean.TRUE.equals(hit.get("skippedInsufficientSamples"))) {
                    skippedInsufficientSamples++;
                    continue;
                }
                if (hit != null) {
                    anomalies++;
                    details.add(hit);
                    raiseAnomalyAlarm(device, hit);
                } else {
                    // 指标已回落基线内：关闭残留基线异常（含处理中）
                    try {
                        alarmService.clearBaselineAnomalyAlarms(device.getId(), metric,
                                "基线检测指标已回落正常，自动关闭");
                    } catch (Exception e) {
                        log.debug("清除基线异常失败 device={} metric={}: {}",
                                device.getName(), metric, e.getMessage());
                    }
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkedDevices", checked);
        result.put("anomalies", anomalies);
        result.put("skippedSimulated", skippedSimulated);
        result.put("skippedInsufficientSamples", skippedInsufficientSamples);
        result.put("minSamples", minSamples);
        result.put("details", details);
        return result;
    }

    /**
     * 近期基线异常告警摘要，供智能中心展示。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRecentAnomalies(int limit) {
        int lim = Math.max(1, Math.min(50, limit));
        LocalDateTime since = LocalDateTime.now().minusHours(
                Math.max(1, policyProperties.getAnomalyLookbackHours()));
        List<Alarm> alarms = alarmRepository.findByStatusInOrderByOccurredAtDesc(
                        List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED))
                .stream()
                .filter(a -> "BASELINE_ANOMALY".equalsIgnoreCase(a.getTrapType())
                        || (a.getTitle() != null && a.getTitle().startsWith("基线异常")))
                .filter(a -> a.getOccurredAt() == null || !a.getOccurredAt().isBefore(since))
                .limit(lim)
                .toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Alarm a : alarms) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("deviceId", a.getDeviceId());
            row.put("deviceName", a.getDeviceName());
            row.put("title", a.getTitle());
            row.put("description", a.getDescription());
            row.put("severity", a.getSeverity() != null ? a.getSeverity().name() : null);
            row.put("occurredAt", a.getOccurredAt());
            row.put("metric", a.getRawData());
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> evaluateMetric(Device device, List<PerformanceData> history, String metric) {
        int minSamples = minSamples();
        List<Double> values = new ArrayList<>();
        String latestSource = PerformanceData.SOURCE_UNKNOWN;
        for (PerformanceData p : history) {
            boolean sim = "cpu".equals(metric) ? p.isCpuSimulated() : p.isMemorySimulated();
            if (sim && !policyProperties.isAnalyzeSimulatedMetrics()) {
                continue;
            }
            Double v = "cpu".equals(metric) ? p.getCpuUsage() : p.getMemoryUsage();
            if (v != null) {
                values.add(v);
                latestSource = "cpu".equals(metric)
                        ? (p.getCpuSource() != null ? p.getCpuSource() : PerformanceData.SOURCE_UNKNOWN)
                        : (p.getMemorySource() != null ? p.getMemorySource() : PerformanceData.SOURCE_UNKNOWN);
            }
        }
        if (values.size() < minSamples) {
            // 若全是仿真被过滤，标记跳过便于统计
            if (!policyProperties.isAnalyzeSimulatedMetrics()
                    && history.stream().anyMatch(p -> "cpu".equals(metric) ? p.isCpuSimulated() : p.isMemorySimulated())) {
                Map<String, Object> skip = new LinkedHashMap<>();
                skip.put("skippedSimulated", true);
                skip.put("deviceId", device.getId());
                skip.put("metric", metric);
                return skip;
            }
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put("skippedInsufficientSamples", true);
            skip.put("deviceId", device.getId());
            skip.put("metric", metric);
            skip.put("sampleCount", values.size());
            skip.put("minSamples", minSamples);
            return skip;
        }
        double latest = values.get(values.size() - 1);
        List<Double> baseline = values.subList(0, values.size() - 1);
        if (baseline.size() < minSamples - 1) {
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put("skippedInsufficientSamples", true);
            skip.put("deviceId", device.getId());
            skip.put("metric", metric);
            return skip;
        }
        double mean = baseline.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = 0;
        for (double v : baseline) {
            variance += (v - mean) * (v - mean);
        }
        double std = Math.sqrt(variance / baseline.size());
        if (std < 1.5) {
            std = 1.5;
        }
        double kSigma = policyProperties.getAnomalyKSigma();
        double minAbs = policyProperties.getAnomalyMinAbsDelta();
        double upper = mean + kSigma * std;
        if (latest <= upper || (latest - mean) < minAbs) {
            return null;
        }
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("deviceId", device.getId());
        hit.put("deviceName", device.getName());
        hit.put("metric", metric);
        hit.put("latest", round1(latest));
        hit.put("mean", round1(mean));
        hit.put("std", round1(std));
        hit.put("upper", round1(upper));
        hit.put("source", latestSource);
        return hit;
    }

    private void raiseAnomalyAlarm(Device device, Map<String, Object> hit) {
        if (device.getIpAddress() == null || device.getIpAddress().isBlank()) {
            return;
        }
        String metric = String.valueOf(hit.get("metric"));
        String title = "基线异常-" + ("cpu".equals(metric) ? "CPU" : "内存");
        LocalDateTime since = LocalDateTime.now()
                .minusMinutes(Math.max(1, policyProperties.getAnomalySuppressMinutes()));
        List<Alarm> existing = alarmRepository.findSimilarRecent(
                AlarmService.normalizeDeviceIp(device.getIpAddress()),
                "BASELINE_ANOMALY",
                title,
                List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED),
                since,
                PageRequest.of(0, 1));
        if (!existing.isEmpty()) {
            return;
        }
        String source = hit.get("source") != null ? String.valueOf(hit.get("source")) : "unknown";
        String desc = String.format("%s 当前 %.1f%%，超过基线上限 %.1f%%（均值 %.1f ± %.1f，来源 %s）",
                title, ((Number) hit.get("latest")).doubleValue(),
                ((Number) hit.get("upper")).doubleValue(),
                ((Number) hit.get("mean")).doubleValue(),
                ((Number) hit.get("std")).doubleValue(),
                source);
        alarmService.createAlarm(
                device.getIpAddress(),
                title,
                desc,
                Alarm.Severity.WARNING,
                metric,
                "BASELINE_ANOMALY"
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> capacityTrends() {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(3);
        double dangerCpu = thresholdProperties.forMetric("cpu").getDanger();
        List<Map<String, Object>> trends = new ArrayList<>();
        for (Device device : deviceRepository.findAll()) {
            if (!"online".equalsIgnoreCase(device.getStatus())) {
                continue;
            }
            List<PerformanceData> history = performanceDataRepository.findByDeviceIdAndTimestampBetween(
                    device.getId(), start, end);
            List<Double> cpu = new ArrayList<>();
            List<PerformanceData> snmpPoints = new ArrayList<>();
            for (PerformanceData p : history) {
                if (p.getCpuUsage() == null) {
                    continue;
                }
                if (p.isCpuSimulated() && !policyProperties.isAnalyzeSimulatedMetrics()) {
                    continue;
                }
                cpu.add(p.getCpuUsage());
                snmpPoints.add(p);
            }
            if (cpu.size() < 12) {
                continue;
            }
            double slope = linearSlope(cpu);
            if (slope <= 0.05) {
                continue;
            }
            double latest = cpu.get(cpu.size() - 1);
            Double etaHours = estimateEtaHours(snmpPoints, cpu, dangerCpu);
            String source = snmpPoints.get(snmpPoints.size() - 1).getCpuSource();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deviceId", device.getId());
            row.put("deviceName", device.getName());
            row.put("metric", "cpu");
            row.put("slope", round1(slope));
            row.put("latest", round1(latest));
            row.put("threshold", dangerCpu);
            row.put("source", source != null ? source : PerformanceData.SOURCE_UNKNOWN);
            if (etaHours != null) {
                row.put("etaHours", round1(etaHours));
                row.put("message", String.format(
                        "CPU 近 3 日上升（当前 %.1f%%，%s），按趋势约 %.1f 小时后触及 %.0f%% 阈值",
                        latest, sourceLabel(source), etaHours, dangerCpu));
                row.put("level", etaHours < 24 ? "warning" : (slope > 0.2 ? "warning" : "info"));
            } else if (latest >= dangerCpu) {
                row.put("etaHours", 0.0);
                row.put("message", String.format("CPU 当前 %.1f%% 已达/超过阈值 %.0f%%", latest, dangerCpu));
                row.put("level", "warning");
            } else {
                row.put("message", "CPU 近 3 日呈上升趋势，请关注容量");
                row.put("level", slope > 0.2 ? "warning" : "info");
            }
            trends.add(row);
        }
        trends.sort(Comparator
                .comparing((Map<String, Object> m) -> m.get("etaHours") instanceof Number n
                        ? n.doubleValue() : Double.MAX_VALUE)
                .thenComparing(m -> -((Number) m.getOrDefault("slope", 0)).doubleValue()));
        if (trends.size() > 20) {
            return new ArrayList<>(trends.subList(0, 20));
        }
        return trends;
    }

    private String sourceLabel(String source) {
        if (PerformanceData.SOURCE_SNMP.equalsIgnoreCase(source)) {
            return "SNMP真采";
        }
        if (PerformanceData.SOURCE_SIMULATED.equalsIgnoreCase(source)) {
            return "仿真";
        }
        return "来源未知";
    }

    private Double estimateEtaHours(List<PerformanceData> history, List<Double> cpu, double threshold) {
        double latest = cpu.get(cpu.size() - 1);
        if (latest >= threshold) {
            return 0.0;
        }
        double slopePerStep = linearSlope(cpu);
        if (slopePerStep <= 1e-6) {
            return null;
        }
        LocalDateTime t0 = null;
        LocalDateTime t1 = null;
        for (PerformanceData p : history) {
            if (p.getCpuUsage() == null || p.getTimestamp() == null) {
                continue;
            }
            if (t0 == null) {
                t0 = p.getTimestamp();
            }
            t1 = p.getTimestamp();
        }
        if (t0 == null || t1 == null || !t1.isAfter(t0) || cpu.size() < 2) {
            return null;
        }
        double hoursSpan = java.time.Duration.between(t0, t1).toMinutes() / 60.0;
        if (hoursSpan < 0.1) {
            return null;
        }
        double hoursPerStep = hoursSpan / (cpu.size() - 1.0);
        double stepsNeeded = (threshold - latest) / slopePerStep;
        if (stepsNeeded < 0) {
            return 0.0;
        }
        double eta = stepsNeeded * hoursPerStep;
        if (eta > 24 * 30) {
            return null;
        }
        return eta;
    }

    private double linearSlope(List<Double> y) {
        int n = y.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += y.get(i);
            sumXY += i * y.get(i);
            sumXX += i * (double) i;
        }
        double den = n * sumXX - sumX * sumX;
        if (Math.abs(den) < 1e-9) {
            return 0;
        }
        return (n * sumXY - sumX * sumY) / den;
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
