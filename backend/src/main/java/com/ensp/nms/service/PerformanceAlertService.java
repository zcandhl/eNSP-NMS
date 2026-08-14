package com.ensp.nms.service;

import com.ensp.nms.config.PerformanceThresholdProperties;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.PerformanceAlert;
import com.ensp.nms.entity.PerformanceData;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.PerformanceAlertRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PerformanceAlertService {

    @Autowired
    private PerformanceAlertRepository alertRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private PerformanceMonitorService monitorService;

    @Autowired
    private AlarmService alarmService;

    @Autowired
    private PerformanceThresholdProperties thresholdProperties;

    /** 相对采集任务错开 15s，避免与 PerformanceMonitorService 同时扫全设备 */
    @Scheduled(fixedRate = 30000, initialDelay = 15000)
    public void checkAndGenerateAlerts() {
        log.info("开始检查性能告警...");
        List<Device> devices = deviceRepository.findAll();
        if (devices.isEmpty()) {
            return;
        }

        // 预取活跃告警，避免每设备×每指标 N+1
        List<PerformanceAlert> openAlerts = alertRepository.findByStatusInOrderByCreatedAtDesc(
                List.of("active", "acknowledged"));
        Map<String, PerformanceAlert> alertIndex = new HashMap<>();
        for (PerformanceAlert a : openAlerts) {
            if (a.getDeviceId() == null || a.getMetric() == null) {
                continue;
            }
            String key = a.getDeviceId() + ":" + a.getMetric();
            alertIndex.putIfAbsent(key, a);
        }

        for (Device device : devices) {
            try {
                if (!"online".equalsIgnoreCase(device.getStatus())) {
                    continue;
                }
                checkDeviceAlerts(device, alertIndex);
            } catch (Exception e) {
                log.error("检查设备 {} 告警失败: {}", device.getName(), e.getMessage());
            }
        }
    }

    private void checkDeviceAlerts(Device device, Map<String, PerformanceAlert> alertIndex) {
        PerformanceData latestData = monitorService.getLatestPerformance(device.getId());
        if (latestData == null) {
            return;
        }

        // 仿真指标不参与阈值告警，避免假数据刷屏
        if (!latestData.isCpuSimulated()) {
            checkMetric(device, "cpu", latestData.getCpuUsage(), alertIndex);
        }
        if (!latestData.isMemorySimulated()) {
            checkMetric(device, "memory", latestData.getMemoryUsage(), alertIndex);
        }
    }

    private void checkMetric(Device device, String metric, Double value,
                             Map<String, PerformanceAlert> alertIndex) {
        if (value == null) {
            return;
        }

        PerformanceThresholdProperties.MetricThreshold th = thresholdProperties.forMetric(metric);
        double warningThreshold = th.getWarning();
        double dangerThreshold = th.getDanger();
        if (warningThreshold <= 0 || dangerThreshold <= 0 || dangerThreshold < warningThreshold) {
            return;
        }

        String key = device.getId() + ":" + metric;
        PerformanceAlert activeAlert = alertIndex.get(key);

        if (value >= dangerThreshold) {
            if (activeAlert == null || !"danger".equals(activeAlert.getLevel())) {
                PerformanceAlert created = createAlert(device, metric, "danger", value, dangerThreshold);
                alertIndex.put(key, created);
            } else {
                activeAlert.setCurrentValue(value);
                alertRepository.save(activeAlert);
                alarmService.syncPerformanceThresholdAlarm(
                        device, metric, "danger", activeAlert.getMessage(), value, dangerThreshold);
            }
        } else if (value >= warningThreshold) {
            if (activeAlert == null) {
                PerformanceAlert created = createAlert(device, metric, "warning", value, warningThreshold);
                alertIndex.put(key, created);
            } else if ("danger".equals(activeAlert.getLevel())) {
                activeAlert.setLevel("warning");
                activeAlert.setCurrentValue(value);
                activeAlert.setThresholdValue(warningThreshold);
                activeAlert.setMessage(String.format("%s超过阈值: %.1f (阈值: %.1f)",
                        getMetricName(metric), value, warningThreshold));
                alertRepository.save(activeAlert);
                alarmService.syncPerformanceThresholdAlarm(
                        device, metric, "warning", activeAlert.getMessage(), value, warningThreshold);
            } else {
                activeAlert.setCurrentValue(value);
                alertRepository.save(activeAlert);
            }
        } else {
            if (activeAlert != null && ("active".equals(activeAlert.getStatus())
                    || "acknowledged".equals(activeAlert.getStatus()))) {
                activeAlert.setStatus("resolved");
                activeAlert.setResolvedAt(LocalDateTime.now());
                alertRepository.save(activeAlert);
                alertIndex.remove(key);
                alarmService.clearPerformanceThresholdAlarm(device, metric);
                log.info("设备 {} 的 {} 告警已恢复", device.getName(), metric);
            }
        }
    }

    private PerformanceAlert createAlert(Device device, String metric, String level,
                                         Double value, Double threshold) {
        PerformanceAlert alert = new PerformanceAlert();
        alert.setDevice(device);
        alert.setMetric(metric);
        alert.setLevel(level);
        alert.setCurrentValue(value);
        alert.setThresholdValue(threshold);
        alert.setStatus("active");
        alert.setCreatedAt(LocalDateTime.now());

        String metricName = getMetricName(metric);
        alert.setMessage(String.format("%s超过阈值: %.1f (阈值: %.1f)", metricName, value, threshold));

        PerformanceAlert saved = alertRepository.save(alert);
        alarmService.syncPerformanceThresholdAlarm(device, metric, level, saved.getMessage(), value, threshold);
        log.warn("设备 {} 产生告警: {} - {}", device.getName(), level, saved.getMessage());
        return saved;
    }

    private String getMetricName(String metric) {
        return switch (metric) {
            case "cpu" -> "CPU使用率";
            case "memory" -> "内存使用率";
            case "temperature" -> "温度";
            default -> metric;
        };
    }

    public List<PerformanceAlert> getActiveAlerts() {
        return alertRepository.findByStatusOrderByCreatedAtDesc("active");
    }

    public List<PerformanceAlert> getDeviceAlerts(Long deviceId, String status) {
        if (status.contains(",")) {
            List<String> statuses = List.of(status.split(","));
            return alertRepository.findByDeviceIdAndStatusInOrderByCreatedAtDesc(deviceId, statuses);
        }
        return alertRepository.findByDeviceIdAndStatusOrderByCreatedAtDesc(deviceId, status);
    }

    public List<PerformanceAlert> getRecentAlerts(int hours) {
        return alertRepository.findByCreatedAtAfterOrderByCreatedAtDesc(
                LocalDateTime.now().minusHours(hours)
        );
    }

    public PerformanceAlert acknowledgeAlert(Long alertId) {
        PerformanceAlert alert = alertRepository.findById(alertId).orElse(null);
        if (alert != null && "active".equals(alert.getStatus())) {
            alert.setStatus("acknowledged");
            alert.setAcknowledgedAt(LocalDateTime.now());
            PerformanceAlert saved = alertRepository.save(alert);
            Long deviceId = saved.getDeviceId() != null
                    ? saved.getDeviceId()
                    : (saved.getDevice() != null ? saved.getDevice().getId() : null);
            if (deviceId != null && saved.getMetric() != null) {
                alarmService.acknowledgePerformanceThresholdAlarm(deviceId, saved.getMetric(), null);
            }
            return saved;
        }
        return alert;
    }

    public PerformanceAlert resolveAlert(Long alertId) {
        PerformanceAlert alert = alertRepository.findById(alertId).orElse(null);
        if (alert != null && !"resolved".equals(alert.getStatus())) {
            alert.setStatus("resolved");
            alert.setResolvedAt(LocalDateTime.now());
            PerformanceAlert saved = alertRepository.save(alert);
            if (alert.getDeviceId() != null) {
                alarmService.clearPerformanceThresholdAlarm(alert.getDeviceId(), alert.getMetric());
            } else if (alert.getDevice() != null) {
                alarmService.clearPerformanceThresholdAlarm(alert.getDevice(), alert.getMetric());
            }
            return saved;
        }
        return alert;
    }
}
