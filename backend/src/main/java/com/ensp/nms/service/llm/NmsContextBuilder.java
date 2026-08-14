package com.ensp.nms.service.llm;

import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.DeviceConfig;
import com.ensp.nms.entity.PerformanceData;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceConfigRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.service.DeviceService;
import com.ensp.nms.service.PerformanceMonitorService;
import com.ensp.nms.service.TopologyService;
import com.ensp.nms.service.aiops.AiopsPlaybookService;
import com.ensp.nms.service.aiops.AlarmCorrelationService;
import com.ensp.nms.service.aiops.AnomalyDetectService;
import com.ensp.nms.service.aiops.HealthScoreService;
import com.ensp.nms.service.aiops.RcaService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 只读聚合网管上下文，供 LLM system prompt 注入。
 */
@Service
public class NmsContextBuilder {

    private final HealthScoreService healthScoreService;
    private final AlarmCorrelationService alarmCorrelationService;
    private final RcaService rcaService;
    private final AlarmRepository alarmRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceConfigRepository deviceConfigRepository;
    private final AiopsPlaybookService playbookService;
    private final DeviceService deviceService;
    private final TopologyService topologyService;
    private final PerformanceMonitorService performanceMonitorService;
    private final AnomalyDetectService anomalyDetectService;

    public NmsContextBuilder(
            HealthScoreService healthScoreService,
            AlarmCorrelationService alarmCorrelationService,
            RcaService rcaService,
            AlarmRepository alarmRepository,
            DeviceRepository deviceRepository,
            DeviceConfigRepository deviceConfigRepository,
            @Lazy AiopsPlaybookService playbookService,
            @Lazy DeviceService deviceService,
            @Lazy TopologyService topologyService,
            @Lazy PerformanceMonitorService performanceMonitorService,
            @Lazy AnomalyDetectService anomalyDetectService) {
        this.healthScoreService = healthScoreService;
        this.alarmCorrelationService = alarmCorrelationService;
        this.rcaService = rcaService;
        this.alarmRepository = alarmRepository;
        this.deviceRepository = deviceRepository;
        this.deviceConfigRepository = deviceConfigRepository;
        this.playbookService = playbookService;
        this.deviceService = deviceService;
        this.topologyService = topologyService;
        this.performanceMonitorService = performanceMonitorService;
        this.anomalyDetectService = anomalyDetectService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> build(Long deviceId, Long alarmId, String pagePath) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("pagePath", pagePath != null ? pagePath : "");
        ctx.put("generatedAt", LocalDateTime.now().toString());
        ctx.put("instruction", "回答必须绑定 focusAlarm/focusDevice；无焦点时只做全局态势，禁止臆造设备状态。");

        try {
            Map<String, Object> health = healthScoreService.compute();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("score", health.get("networkScore"));
            summary.put("level", health.get("level"));
            summary.put("deviceTotal", health.get("deviceTotal"));
            summary.put("onlineCount", health.get("onlineCount"));
            summary.put("offlineCount", health.get("offlineCount"));
            summary.put("openAlarmCount", health.get("activeAlarms"));
            ctx.put("health", summary);
        } catch (Exception e) {
            ctx.put("health", Map.of("error", "unavailable"));
        }

        final List<Alarm> openAlarms = loadOpenAlarms();

        try {
            List<Map<String, Object>> incidents = alarmCorrelationService.listIncidents();
            List<Map<String, Object>> top = new ArrayList<>();
            for (Map<String, Object> inc : incidents.stream().limit(5).toList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", inc.get("id"));
                row.put("title", inc.get("title"));
                row.put("severity", inc.get("severity"));
                row.put("deviceId", inc.get("deviceId"));
                row.put("deviceName", inc.get("deviceName"));
                row.put("childCount", inc.get("childCount"));
                row.put("secondary", inc.get("secondary"));
                top.add(row);
            }
            ctx.put("incidentTop", top);
            ctx.put("incidentCount", incidents.size());
        } catch (Exception e) {
            ctx.put("incidentTop", List.of());
        }

        try {
            Map<String, Object> rca = rcaService.analyze();
            Object candidates = rca.get("candidates");
            if (candidates instanceof List<?> list) {
                List<Map<String, Object>> top = new ArrayList<>();
                int i = 0;
                for (Object o : list) {
                    if (i++ >= 5) break;
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("deviceId", m.get("deviceId"));
                        row.put("deviceName", m.get("name"));
                        row.put("reason", m.get("reason"));
                        row.put("score", m.get("score"));
                        row.put("category", m.get("category"));
                        top.add(row);
                    }
                }
                ctx.put("rcaTop", top);
            }
        } catch (Exception e) {
            ctx.put("rcaTop", List.of());
        }

        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        long todayAlarms = openAlarms.stream()
                .filter(a -> a.getOccurredAt() != null && !a.getOccurredAt().isBefore(startOfDay))
                .count();
        if (todayAlarms == 0) {
            todayAlarms = alarmRepository.findAll().stream()
                    .filter(a -> a.getOccurredAt() != null && !a.getOccurredAt().isBefore(startOfDay))
                    .count();
        }
        ctx.put("todayAlarmCount", todayAlarms);

        long activeSecondary = openAlarms.stream()
                .filter(a -> a.getStatus() == Alarm.Status.ACTIVE)
                .filter(Alarm::isSecondaryAlarm)
                .count();
        long activeChildren = openAlarms.stream()
                .filter(a -> a.getStatus() == Alarm.Status.ACTIVE)
                .filter(a -> a.getParentAlarmId() != null)
                .count();
        ctx.put("ackableNoiseCount", activeSecondary + activeChildren);
        ctx.put("activeSecondaryCount", activeSecondary);
        ctx.put("activeChildCount", activeChildren);

        Alarm focusAlarm = null;
        if (alarmId != null) {
            Alarm resolvedAlarm = openAlarms.stream().filter(a -> Objects.equals(a.getId(), alarmId)).findFirst()
                    .orElseGet(() -> alarmRepository.findById(alarmId).orElse(null));
            focusAlarm = resolvedAlarm;
            if (resolvedAlarm != null) {
                Map<String, Object> brief = alarmBrief(resolvedAlarm);
                long childNoise = openAlarms.stream()
                        .filter(a -> a.getStatus() == Alarm.Status.ACTIVE)
                        .filter(a -> Objects.equals(a.getParentAlarmId(), alarmId))
                        .count();
                brief.put("childActiveCount", childNoise);
                // 同类近期（resolvedAlarm 为 effectively final，可供 lambda 引用）
                final Long focusAlarmDeviceId = resolvedAlarm.getDeviceId();
                String titlePrefix = resolvedAlarm.getTitle() != null && resolvedAlarm.getTitle().length() > 8
                        ? resolvedAlarm.getTitle().substring(0, 8) : resolvedAlarm.getTitle();
                long similar = openAlarms.stream()
                        .filter(a -> Objects.equals(a.getDeviceId(), focusAlarmDeviceId))
                        .filter(a -> titlePrefix != null && a.getTitle() != null && a.getTitle().startsWith(titlePrefix))
                        .count();
                brief.put("similarOpenCount", similar);
                ctx.put("focusAlarm", brief);
                ctx.put("focusAlarmChildActiveCount", childNoise);
            }
        }

        final Long focusDeviceId = deviceId != null
                ? deviceId
                : (focusAlarm != null ? focusAlarm.getDeviceId() : null);

        if (focusDeviceId != null) {
            deviceRepository.findById(focusDeviceId).ifPresent(d -> {
                Map<String, Object> brief = deviceBrief(d);
                List<DeviceConfig> backups = deviceConfigRepository
                        .findByDeviceIdOrderByCreatedAtDesc(focusDeviceId);
                boolean hasBackup = !backups.isEmpty();
                brief.put("hasBackup", hasBackup);
                if (hasBackup) {
                    DeviceConfig latest = backups.get(0);
                    brief.put("latestBackupAt", latest.getCreatedAt() != null
                            ? latest.getCreatedAt().toString() : null);
                    brief.put("latestBackupVersion", latest.getConfigVersion());
                    brief.put("backupCount", backups.size());
                }
                long deviceActiveAlarms = openAlarms.stream()
                        .filter(a -> a.getStatus() == Alarm.Status.ACTIVE
                                || a.getStatus() == Alarm.Status.ACKNOWLEDGED)
                        .filter(a -> Objects.equals(a.getDeviceId(), focusDeviceId))
                        .count();
                brief.put("activeAlarmCount", deviceActiveAlarms);
                long deviceNoise = openAlarms.stream()
                        .filter(a -> a.getStatus() == Alarm.Status.ACTIVE)
                        .filter(a -> Objects.equals(a.getDeviceId(), focusDeviceId))
                        .filter(a -> a.isSecondaryAlarm() || a.getParentAlarmId() != null)
                        .count();
                brief.put("activeNoiseCount", deviceNoise);
                ctx.put("focusDevice", brief);
            });

            // 拓扑邻居（最多 8）
            try {
                List<Map<String, Object>> neighbors = topologyService.getDeviceNeighbors(focusDeviceId);
                ctx.put("focusTopoNeighbors", neighbors.stream().limit(8).toList());
                ctx.put("focusTopoNeighborCount", neighbors.size());
            } catch (Exception e) {
                ctx.put("focusTopoNeighbors", List.of());
            }

            // 性能摘要
            try {
                PerformanceData latest = performanceMonitorService.getLatestPerformance(focusDeviceId);
                if (latest != null) {
                    Map<String, Object> perf = new LinkedHashMap<>();
                    perf.put("cpuUsage", latest.getCpuUsage());
                    perf.put("memoryUsage", latest.getMemoryUsage());
                    perf.put("temperature", latest.getTemperature());
                    perf.put("timestamp", latest.getTimestamp() != null ? latest.getTimestamp().toString() : null);
                    ctx.put("focusPerf", perf);
                }
            } catch (Exception ignored) {
                // optional
            }

            // 配置摘要（不下发全文）
            try {
                List<DeviceConfig> backups = deviceConfigRepository
                        .findByDeviceIdOrderByCreatedAtDesc(focusDeviceId);
                Map<String, Object> cfg = new LinkedHashMap<>();
                cfg.put("backupCount", backups.size());
                if (!backups.isEmpty()) {
                    DeviceConfig latest = backups.get(0);
                    cfg.put("latestBackupAt", latest.getCreatedAt() != null
                            ? latest.getCreatedAt().toString() : null);
                    cfg.put("latestBackupType", latest.getConfigType());
                    cfg.put("latestContentLength", latest.getContent() != null
                            ? latest.getContent().length() : 0);
                }
                ctx.put("focusConfigSummary", cfg);
            } catch (Exception ignored) {
                // optional
            }

            // 该设备近期基线异常
            try {
                List<Map<String, Object>> anomalies = anomalyDetectService.listRecentAnomalies(20).stream()
                        .filter(m -> Objects.equals(toLong(m.get("deviceId")), focusDeviceId))
                        .limit(3)
                        .toList();
                if (!anomalies.isEmpty()) {
                    ctx.put("focusAnomalies", anomalies);
                }
            } catch (Exception ignored) {
                // optional
            }
        }

        Map<String, Object> executable = new LinkedHashMap<>();
        executable.put("canDisposeFocus", alarmId != null);
        executable.put("canBackupFocus", focusDeviceId != null);
        executable.put("canRefreshFocus", focusDeviceId != null);
        executable.put("canAckNoise", activeSecondary + activeChildren > 0);
        executable.put("canTopoNeighbors", focusDeviceId != null);
        executable.put("canPerfSnapshot", focusDeviceId != null);
        executable.put("canConfigDiff", focusDeviceId != null);
        ctx.put("executableHints", executable);

        try {
            Device focusDevice = focusDeviceId != null
                    ? deviceRepository.findById(focusDeviceId).orElse(null) : null;
            String scenario = playbookService.detectScenario(focusDevice, focusAlarm);
            ctx.put("scenario", scenario);
            boolean closed = focusAlarm != null && focusAlarm.getStatus() == Alarm.Status.CLEARED;
            boolean inProgress = focusAlarm != null && focusAlarm.getStatus() == Alarm.Status.ACKNOWLEDGED;
            if (ctx.get("focusAlarm") instanceof Map<?, ?> fa) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mutable = (Map<String, Object>) fa;
                mutable.put("scenario", scenario);
                mutable.put("closed", closed);
                mutable.put("inProgress", inProgress);
                mutable.put("phase", closed ? "closed" : (inProgress ? "in_progress" : "pending"));
                mutable.put("handled", closed);
            }
            List<Map<String, Object>> recommended = playbookService.buildRecommendedTools(
                    scenario, focusDeviceId, alarmId, closed, focusAlarm, focusDevice);
            ctx.put("recommendedTools", recommended.stream().limit(3).toList());
            ctx.put("focusHandled", closed);
            ctx.put("focusClosed", closed);
            ctx.put("focusInProgress", inProgress);
            if (focusDevice != null) {
                Map<String, Object> probe = new LinkedHashMap<>();
                probe.put("status", focusDevice.getStatus());
                probe.put("lastProbeMethod", focusDevice.getLastProbeMethod());
                probe.put("lastSeen", focusDevice.getLastSeen());
                probe.putAll(deviceService.getProbeSnapshot(focusDevice.getId()));
                ctx.put("focusProbe", probe);
            }
        } catch (Exception e) {
            ctx.put("recommendedTools", List.of());
        }
        return ctx;
    }

    private List<Alarm> loadOpenAlarms() {
        try {
            return alarmRepository.findByStatusInOrderByOccurredAtDesc(
                    List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> deviceBrief(Device d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("ipAddress", d.getIpAddress());
        m.put("status", d.getStatus());
        m.put("deviceType", d.getDeviceType());
        m.put("lastProbeMethod", d.getLastProbeMethod());
        m.put("lastSeen", d.getLastSeen() != null ? d.getLastSeen().toString() : null);
        return m;
    }

    private Map<String, Object> alarmBrief(Alarm a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("title", a.getTitle());
        m.put("severity", a.getSeverity() != null ? a.getSeverity().name() : null);
        m.put("status", a.getStatus() != null ? a.getStatus().name() : null);
        m.put("deviceId", a.getDeviceId());
        m.put("deviceName", a.getDeviceName());
        m.put("secondary", a.isSecondaryAlarm());
        m.put("parentAlarmId", a.getParentAlarmId());
        m.put("correlationType", a.getCorrelationType());
        m.put("correlationNote", a.getCorrelationNote());
        m.put("occurredAt", a.getOccurredAt() != null ? a.getOccurredAt().toString() : null);
        return m;
    }

    private static Long toLong(Object v) {
        if (v == null || "".equals(v)) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
