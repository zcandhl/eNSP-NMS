package com.ensp.nms.service.aiops;

import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.PerformanceData;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.service.AlarmService;
import com.ensp.nms.service.DeviceService;
import com.ensp.nms.service.PerformanceMonitorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 真实可执行处置动作与事件时间线（不含跳转式演示步骤）。
 */
@Slf4j
@Service
public class AiopsPlaybookService {

    private final DeviceRepository deviceRepository;
    private final AlarmRepository alarmRepository;
    private final DeviceService deviceService;
    private final PerformanceMonitorService performanceMonitorService;
    private final AiopsActionService aiopsActionService;
    private final AlarmCorrelationService alarmCorrelationService;
    private final RcaService rcaService;
    private final OpsAssistantService opsAssistantService;
    private final AlarmService alarmService;

    public AiopsPlaybookService(
            DeviceRepository deviceRepository,
            AlarmRepository alarmRepository,
            DeviceService deviceService,
            PerformanceMonitorService performanceMonitorService,
            AiopsActionService aiopsActionService,
            AlarmCorrelationService alarmCorrelationService,
            RcaService rcaService,
            OpsAssistantService opsAssistantService,
            AlarmService alarmService) {
        this.deviceRepository = deviceRepository;
        this.alarmRepository = alarmRepository;
        this.deviceService = deviceService;
        this.performanceMonitorService = performanceMonitorService;
        this.aiopsActionService = aiopsActionService;
        this.alarmCorrelationService = alarmCorrelationService;
        this.rcaService = rcaService;
        this.opsAssistantService = opsAssistantService;
        this.alarmService = alarmService;
    }

    /**
     * 仅返回会改库/改状态的真实动作（无 open_* / 跳转步骤）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildPlaybook(Long deviceId, Long alarmId) {
        Alarm alarm = alarmId != null ? alarmRepository.findById(alarmId).orElse(null) : null;
        final Long resolvedDeviceId = deviceId != null
                ? deviceId
                : (alarm != null ? alarm.getDeviceId() : null);
        Device device = resolvedDeviceId != null ? deviceRepository.findById(resolvedDeviceId).orElse(null) : null;
        String scenario = detectScenario(device, alarm);
        boolean closed = alarm != null && alarm.getStatus() == Alarm.Status.CLEARED;
        List<Map<String, Object>> recommended = buildRecommendedTools(
                scenario, resolvedDeviceId, alarmId, closed, alarm, device);
        List<Map<String, Object>> steps = new ArrayList<>();
        int seq = 1;
        for (Map<String, Object> t : recommended) {
            String name = String.valueOf(t.get("name"));
            String action = switch (name) {
                case "ack_noise" -> "ack_secondary";
                case "restore_latest" -> "restore";
                default -> name;
            };
            @SuppressWarnings("unchecked")
            Map<String, Object> toolParams = t.get("params") instanceof Map<?, ?> pm
                    ? (Map<String, Object>) pm
                    : (t.get("args") instanceof Map<?, ?> am ? (Map<String, Object>) am : params());
            steps.add(step(seq++, name, String.valueOf(t.getOrDefault("label", name)),
                    String.valueOf(t.getOrDefault("description", t.getOrDefault("detail", ""))),
                    action, toolParams, true));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", scenario);
        result.put("scenarioLabel", scenarioLabel(scenario));
        result.put("deviceId", resolvedDeviceId);
        result.put("deviceName", device != null ? device.getName() : null);
        result.put("alarmId", alarmId);
        result.put("title", "真实处置动作 · " + scenarioLabel(scenario));
        result.put("steps", steps);
        result.put("recommendedTools", recommended);
        result.put("disclaimer", "下列动作均会真实写库或刷新状态；备份/回滚需确认，不会无人值守改配。");
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildTimeline(Long alarmId, Long deviceId) {
        Alarm alarm = alarmId != null ? alarmRepository.findById(alarmId).orElse(null) : null;
        final Long resolvedDeviceId = deviceId != null
                ? deviceId
                : (alarm != null ? alarm.getDeviceId() : null);
        List<Map<String, Object>> events = new ArrayList<>();

        if (alarm != null) {
            events.add(timelineEvent(alarm.getOccurredAt(), "alarm", "告警产生",
                    alarm.getTitle() + (alarm.isSecondaryAlarm() ? "（连带）" : ""),
                    alarm.getSeverity() != null ? alarm.getSeverity().name() : null));
            if (alarm.getCorrelationNote() != null && !alarm.getCorrelationNote().isBlank()) {
                events.add(timelineEvent(alarm.getOccurredAt(), "correlation", "智能关联",
                        alarm.getCorrelationNote(), alarm.getCorrelationType()));
            }
            if (alarm.getAcknowledgedAt() != null) {
                events.add(timelineEvent(alarm.getAcknowledgedAt(), "ack", "进入处理中",
                        "操作人 " + (alarm.getAcknowledgedBy() != null ? alarm.getAcknowledgedBy() : "-")
                                + (alarm.getAcknowledgeNote() != null ? " · " + alarm.getAcknowledgeNote() : ""),
                        alarm.getStatus() != null ? alarm.getStatus().name() : null));
            }
            if (alarm.getClearedAt() != null) {
                String clearDetail = alarm.getClearNote() != null && !alarm.getClearNote().isBlank()
                        ? alarm.getClearNote()
                        : "故障条件消失或人工关闭";
                events.add(timelineEvent(alarm.getClearedAt(), "clear", "已关闭",
                        clearDetail,
                        Alarm.Status.CLEARED.name()));
            }
            for (Alarm child : alarmRepository.findByParentAlarmId(alarm.getId())) {
                events.add(timelineEvent(child.getOccurredAt(), "child", "收敛子告警",
                        child.getTitle() + " · " + (child.getStatus() != null ? child.getStatus().name() : ""),
                        child.getCorrelationType()));
            }
        }

        if (resolvedDeviceId != null) {
            Device d = deviceRepository.findById(resolvedDeviceId).orElse(null);
            if (d != null) {
                Map<String, Object> snap = deviceService.getProbeSnapshot(d.getId());
                String probe = d.getLastProbeMethod() != null ? d.getLastProbeMethod() : "-";
                events.add(timelineEvent(LocalDateTime.now(), "device", "设备当前状态",
                        String.format("%s · %s · %s · 探测=%s · 连续可达 %s/%s",
                                d.getName(), d.getStatus(), d.getIpAddress(), probe,
                                snap.getOrDefault("consecutiveSuccess", 0),
                                snap.getOrDefault("onlineAfterSuccesses", 2)),
                        d.getStatus()));
            }
            PerformanceData latest = performanceMonitorService.getLatestPerformance(resolvedDeviceId);
            if (latest != null) {
                events.add(timelineEvent(latest.getTimestamp(), "performance", "最新性能快照",
                        String.format("CPU %s%% (%s) · 内存 %s%% (%s)",
                                latest.getCpuUsage(), latest.getCpuSource(),
                                latest.getMemoryUsage(), latest.getMemorySource()),
                        latest.getMetricSourceSummary()));
            }
            alarmRepository.findByStatusInOrderByOccurredAtDesc(
                            List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED))
                    .stream()
                    .filter(a -> resolvedDeviceId.equals(a.getDeviceId()))
                    .filter(a -> alarm == null || !Objects.equals(a.getId(), alarm.getId()))
                    .limit(5)
                    .forEach(a -> events.add(timelineEvent(a.getOccurredAt(), "related_alarm",
                            "同设备相关告警", a.getTitle() + " · " + (a.getStatus() != null ? a.getStatus().name() : ""),
                            a.getSeverity() != null ? a.getSeverity().name() : null)));
        }

        events.sort(Comparator.comparing(
                (Map<String, Object> e) -> e.get("at") instanceof LocalDateTime t ? t : LocalDateTime.MIN,
                Comparator.reverseOrder()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alarmId", alarmId);
        result.put("deviceId", resolvedDeviceId);
        result.put("events", events);
        result.put("narrative", narrate(events, alarm));
        return result;
    }

    /** 仅执行真实动作；跳转类动作一律拒绝 */
    public Map<String, Object> executeStep(String action, Long deviceId, Long alarmId,
                                           boolean confirmed, String question) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        result.put("autoChange", false);
        try {
            switch (action == null ? "" : action) {
                case "dispose_incident" -> {
                    return aiopsActionService.disposeIncidentWithConfirm(alarmId, deviceId, confirmed);
                }
                case "refresh_device" -> {
                    if (deviceId == null) {
                        return fail(result, "缺少 deviceId");
                    }
                    Device d = deviceService.refreshDeviceStatus(deviceId);
                    result.put("ok", true);
                    result.put("message", "设备状态已刷新：" + d.getName() + " → " + d.getStatus());
                    result.put("status", d.getStatus());
                }
                case "refresh_offline" -> {
                    return aiopsActionService.refreshOfflineDevices(confirmed);
                }
                case "ack_secondary" -> {
                    return aiopsActionService.ackSecondaryWithConfirm(deviceId, alarmId, confirmed, null);
                }
                case "backup" -> {
                    return aiopsActionService.backupWithConfirm(deviceId, confirmed, "AIOps处置备份");
                }
                case "restore" -> {
                    return aiopsActionService.restoreLatestWithConfirm(deviceId, confirmed, "AIOps处置回滚");
                }
                case "inspect" -> {
                    Map<String, Object> corr = alarmCorrelationService.correlateOpenAlarms();
                    Map<String, Object> rca = rcaService.analyze();
                    result.put("ok", true);
                    result.put("message", "已重算关联与根因");
                    result.put("correlation", corr);
                    result.put("rca", rca);
                }
                default -> {
                    return fail(result, "不支持演示/跳转动作: " + action + "（仅允许真实写库动作）");
                }
            }
        } catch (Exception e) {
            log.warn("处置动作失败 action={}: {}", action, e.getMessage());
            return fail(result, e.getMessage() != null ? e.getMessage() : "执行失败");
        }
        return result;
    }

    /**
     * 工作台焦点聚合：场景 + 推荐工具 + 影响范围 + 证据摘要。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildWorkbenchFocus(Long alarmId, Long deviceId) {
        Alarm alarm = alarmId != null ? alarmRepository.findById(alarmId).orElse(null) : null;
        final Long resolvedDeviceId = deviceId != null
                ? deviceId
                : (alarm != null ? alarm.getDeviceId() : null);
        Device device = resolvedDeviceId != null ? deviceRepository.findById(resolvedDeviceId).orElse(null) : null;
        String scenario = detectScenario(device, alarm);

        List<Alarm> children = alarmId != null
                ? alarmRepository.findByParentAlarmId(alarmId)
                : List.of();
        long childActive = children.stream().filter(a -> a.getStatus() == Alarm.Status.ACTIVE).count();
        long secondaryNear = 0;
        if (resolvedDeviceId != null) {
            secondaryNear = alarmRepository.findByStatusInOrderByOccurredAtDesc(List.of(Alarm.Status.ACTIVE))
                    .stream()
                    .filter(Alarm::isSecondaryAlarm)
                    .filter(a -> Objects.equals(a.getDeviceId(), resolvedDeviceId))
                    .count();
        }

        boolean closed = alarm != null && alarm.getStatus() == Alarm.Status.CLEARED;
        boolean inProgress = alarm != null && alarm.getStatus() == Alarm.Status.ACKNOWLEDGED;
        List<Map<String, Object>> recommendedTools = buildRecommendedTools(
                scenario, resolvedDeviceId, alarmId, closed, alarm, device);
        String primaryReason = recommendedTools.isEmpty() ? null
                : String.valueOf(recommendedTools.get(0).getOrDefault("reason", ""));
        String summary = buildPlanSummary(scenario, alarm, device, childActive, secondaryNear, closed, inProgress);

        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("childCount", children.size());
        impact.put("childActiveCount", childActive);
        impact.put("secondaryOnDevice", secondaryNear);
        impact.put("deviceStatus", device != null ? device.getStatus() : null);
        if (device != null) {
            impact.put("lastProbeMethod", device.getLastProbeMethod());
            impact.put("lastSeen", device.getLastSeen());
            Map<String, Object> snap = deviceService.getProbeSnapshot(device.getId());
            impact.putAll(snap);
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        List<Map<String, Object>> childMaps = new ArrayList<>();
        for (Alarm c : children) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("title", c.getTitle());
            m.put("status", c.getStatus() != null ? c.getStatus().name() : null);
            m.put("severity", c.getSeverity() != null ? c.getSeverity().name() : null);
            m.put("secondary", c.isSecondaryAlarm());
            m.put("occurredAt", c.getOccurredAt());
            childMaps.add(m);
        }
        evidence.put("childAlarms", childMaps);

        if (resolvedDeviceId != null) {
            PerformanceData latest = performanceMonitorService.getLatestPerformance(resolvedDeviceId);
            if (latest != null) {
                Map<String, Object> perf = new LinkedHashMap<>();
                perf.put("cpuUsage", latest.getCpuUsage());
                perf.put("memoryUsage", latest.getMemoryUsage());
                perf.put("cpuSource", latest.getCpuSource());
                perf.put("memorySource", latest.getMemorySource());
                perf.put("timestamp", latest.getTimestamp());
                evidence.put("performance", perf);
            }
        }

        try {
            if (alarm != null && alarm.getDeviceId() != null) {
                evidence.put("relatedChanges", opsAssistantService.relatedConfigChanges(
                        alarm.getDeviceId(),
                        alarm.getOccurredAt() != null ? alarm.getOccurredAt() : LocalDateTime.now(),
                        60));
            } else {
                evidence.put("relatedChanges", List.of());
            }
        } catch (Exception ignored) {
            evidence.put("relatedChanges", List.of());
        }

        List<Map<String, Object>> rcaHints = new ArrayList<>();
        try {
            Object cands = rcaService.analyze().get("candidates");
            if (cands instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    if (resolvedDeviceId != null && !Objects.equals(toLong(m.get("deviceId")), resolvedDeviceId)) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("deviceId", m.get("deviceId"));
                    row.put("name", m.get("name"));
                    row.put("reason", m.get("reason"));
                    row.put("score", m.get("score"));
                    Object ev = m.get("evidence");
                    if (ev instanceof List<?> el) {
                        row.put("evidence", el.stream().limit(5).toList());
                    }
                    rcaHints.add(row);
                    if (rcaHints.size() >= 3) break;
                }
            }
        } catch (Exception ignored) {
            /* ignore */
        }
        evidence.put("rcaHints", rcaHints);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("scenario", scenario);
        result.put("scenarioLabel", scenarioLabel(scenario));
        result.put("summary", summary);
        result.put("hint", closed ? null : scenarioHint(scenario));
        result.put("alarmId", alarmId);
        result.put("deviceId", resolvedDeviceId);
        result.put("deviceName", device != null ? device.getName() : (alarm != null ? alarm.getDeviceName() : null));
        result.put("title", alarm != null ? alarm.getTitle() : (device != null ? device.getName() : null));
        result.put("severity", alarm != null && alarm.getSeverity() != null ? alarm.getSeverity().name() : null);
        result.put("status", alarm != null && alarm.getStatus() != null ? alarm.getStatus().name() : null);
        result.put("closed", closed);
        result.put("inProgress", inProgress);
        result.put("phase", closed ? "closed" : (inProgress ? "in_progress" : "pending"));
        // handled 仅=已关闭（兼容旧前端；ACK≠办结）
        result.put("handled", closed);
        result.put("correlationNote", alarm != null ? alarm.getCorrelationNote() : null);
        result.put("correlationType", alarm != null ? alarm.getCorrelationType() : null);
        result.put("trapType", alarm != null ? alarm.getTrapType() : null);
        result.put("recommendedTools", recommendedTools);
        result.put("primaryReason", primaryReason);
        result.put("ackCloses", alarm != null && alarmService.isAckClosesAlarm(alarm));
        result.put("impact", impact);
        result.put("evidence", evidence);
        result.put("timeline", buildTimeline(alarmId, resolvedDeviceId));
        return result;
    }

    /** 供 LLM 启发式兜底：场景推荐工具 */
    public List<Map<String, Object>> buildRecommendedTools(String scenario, Long deviceId, Long alarmId) {
        return buildRecommendedTools(scenario, deviceId, alarmId, false, null, null);
    }

    public List<Map<String, Object>> buildRecommendedTools(String scenario, Long deviceId, Long alarmId,
                                                           boolean closed) {
        return buildRecommendedTools(scenario, deviceId, alarmId, closed, null, null);
    }

    /**
     * 按状态重排主按钮：阅知关闭 / 离线刷新 / 标准处置 / 复核。
     */
    public List<Map<String, Object>> buildRecommendedTools(String scenario, Long deviceId, Long alarmId,
                                                           boolean closed, Alarm alarm, Device device) {
        List<Map<String, Object>> tools = new ArrayList<>();
        String sc = scenario != null ? scenario : "GENERIC";
        boolean ackCloses = alarm != null && alarmService.isAckClosesAlarm(alarm);
        boolean offline = (device != null && "offline".equalsIgnoreCase(String.valueOf(device.getStatus())))
                || "OFFLINE".equals(sc)
                || (alarm != null && "DEVICE_OFFLINE".equalsIgnoreCase(alarm.getTrapType()));

        if (closed) {
            if (deviceId != null) {
                tools.add(toolBtn("refresh_device", "刷新设备复核", "事件已关闭，复核连通性", false,
                        params("deviceId", deviceId), "事件已关闭，建议复核设备状态"));
            }
            tools.add(toolBtn("inspect", "重算关联与根因", "复核分析快照", false, params(),
                    "事件已关闭，可重算关联做复核"));
            return limitTools(tools);
        }

        if (ackCloses && alarm != null && alarm.getStatus() == Alarm.Status.ACTIVE) {
            tools.add(toolBtn("ack_alarm", "阅知关闭", "阅知类事件：确认后直接办结", true,
                    params("alarmId", alarmId), "阅知类提示事件，确认即可关闭"));
            if (deviceId != null) {
                tools.add(toolBtn("refresh_device", "刷新设备", "可选：复核设备状态", true,
                        params("deviceId", deviceId), null));
            }
            return limitTools(tools);
        }

        if (offline && deviceId != null) {
            tools.add(toolBtn("refresh_device", "刷新设备连通性", "离线事件优先重新探测", true,
                    params("deviceId", deviceId), "设备离线或 OFFLINE 场景，先刷新连通性"));
            tools.add(toolBtn("get_topo_neighbors", "拓扑邻居", "查看邻居是否连带离线", false,
                    params("deviceId", deviceId), null));
            tools.add(toolBtn("get_device_summary", "设备摘要", "汇总状态/告警/备份", false,
                    params("deviceId", deviceId), null));
            tools.add(toolBtn("refresh_offline", "批量刷新离线设备", "刷新当前所有离线设备状态", true, params(), null));
            if (alarmId != null) {
                tools.add(toolBtn("dispose_incident", "标准处置本事件",
                        "探测确认后关闭或进入处理中（不改配）", true,
                        params("alarmId", alarmId, "deviceId", deviceId), null));
                tools.add(toolBtn("ack_noise", "处理关联告警", "批量处理关联噪音", true,
                        params("alarmId", alarmId, "deviceId", deviceId), null));
            }
            return limitTools(tools);
        }

        if (alarmId != null) {
            tools.add(toolBtn("dispose_incident", "标准处置本事件",
                    "触发网管探测：连续可达确认后关闭告警；单次可达仅为疑似恢复并进入「处理中」（不改配）", true,
                    params("alarmId", alarmId, "deviceId", deviceId),
                    "常规故障事件，建议标准处置闭环"));
        }

        switch (sc) {
            case "LINK", "STORM" -> {
                if (deviceId != null) {
                    tools.add(toolBtn("get_topo_neighbors", "拓扑邻居", "查看链路相关邻居", false,
                            params("deviceId", deviceId), null));
                    tools.add(toolBtn("run_path_hint", "路径提示", "计算可达路径（可传 targetDeviceId）", false,
                            params("deviceId", deviceId), null));
                    tools.add(toolBtn("refresh_device", "刷新设备状态", "核对链路相关设备当前是否在线", true,
                            params("deviceId", deviceId), null));
                    tools.add(toolBtn("get_config_diff_summary", "配置差异摘要", "对比 running 与最新备份", false,
                            params("deviceId", deviceId), null));
                }
                tools.add(toolBtn("ack_noise", "处理关联告警", "批量处理链路/风暴关联告警", true,
                        params("alarmId", alarmId, "deviceId", deviceId), null));
                if (deviceId != null) {
                    tools.add(toolBtn("pull_live_config", "查看当前配置", "只读拉取，不会自动回滚", false,
                            params("deviceId", deviceId, "configType", "running"), null));
                }
            }
            case "PERFORMANCE" -> {
                if (deviceId != null) {
                    tools.add(toolBtn("get_perf_snapshot", "性能快照", "CPU/内存/端口与基线异常", false,
                            params("deviceId", deviceId), "性能场景优先看快照"));
                    tools.add(toolBtn("list_active_alarms_for_device", "活动告警", "列出该设备开告警", false,
                            params("deviceId", deviceId), null));
                    tools.add(toolBtn("refresh_device", "刷新设备", "复核设备当前状态", true,
                            params("deviceId", deviceId), null));
                    tools.add(toolBtn("backup", "备份当前配置", "变更前先备份，需确认", true,
                            params("deviceId", deviceId), null));
                }
                tools.add(toolBtn("inspect", "重算关联与基线", "立即刷新分析结果", false, params(), null));
            }
            case "CONFIG" -> {
                if (deviceId != null) {
                    tools.add(toolBtn("get_config_diff_summary", "配置差异摘要", "对比 running 与最新备份（只读）", false,
                            params("deviceId", deviceId), "配置场景优先看差异"));
                    tools.add(toolBtn("pull_live_config", "查看当前配置", "只读拉取设备配置", false,
                            params("deviceId", deviceId, "configType", "running"), null));
                    tools.add(toolBtn("backup", "先备份", "回滚前建议先备份", true,
                            params("deviceId", deviceId), null));
                    tools.add(toolBtn("restore_latest", "回滚最新备份", "会真实下发配置，必须确认", true,
                            params("deviceId", deviceId), null));
                }
            }
            default -> {
                if (deviceId != null) {
                    tools.add(toolBtn("get_device_summary", "设备摘要", "状态/告警/邻居/备份一览", false,
                            params("deviceId", deviceId), null));
                    tools.add(toolBtn("list_active_alarms_for_device", "活动告警", "列出该设备开告警", false,
                            params("deviceId", deviceId), null));
                    tools.add(toolBtn("refresh_device", "刷新设备", "重新探测并写回设备状态", true,
                            params("deviceId", deviceId), null));
                }
                tools.add(toolBtn("ack_noise", "处理关联告警", "批量处理本事件关联告警", true,
                        params("alarmId", alarmId, "deviceId", deviceId), null));
                tools.add(toolBtn("inspect", "重算关联与根因", "立即刷新分析结果", false, params(), null));
            }
        }
        return limitTools(tools);
    }

    private List<Map<String, Object>> limitTools(List<Map<String, Object>> tools) {
        Map<String, Map<String, Object>> uniq = new LinkedHashMap<>();
        for (Map<String, Object> t : tools) {
            uniq.putIfAbsent(String.valueOf(t.get("name")), t);
        }
        return new ArrayList<>(uniq.values()).stream().limit(5).toList();
    }

    private Map<String, Object> toolBtn(String name, String label, String description, boolean needConfirm,
                                        Map<String, Object> params) {
        return toolBtn(name, label, description, needConfirm, params, null);
    }

    private Map<String, Object> toolBtn(String name, String label, String description, boolean needConfirm,
                                        Map<String, Object> params, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("id", name);
        m.put("label", label);
        m.put("description", description);
        m.put("detail", description);
        m.put("needConfirm", needConfirm);
        Map<String, Object> p = params != null ? params : Map.of();
        m.put("params", p);
        m.put("args", p);
        m.put("real", true);
        if (reason != null && !reason.isBlank()) {
            m.put("reason", reason);
        }
        return m;
    }

    private String buildPlanSummary(String scenario, Alarm alarm, Device device,
                                    long childActive, long secondaryNear,
                                    boolean closed, boolean inProgress) {
        String label = scenarioLabel(scenario);
        String title = alarm != null ? alarm.getTitle() : (device != null ? device.getName() : "当前对象");
        String what = String.format("发生了什么：%s（%s）。关联告警 %d 条未处理，同设备相关 %d 条。",
                title, label, childActive, secondaryNear);
        if (closed) {
            return what + "建议做什么：事件已关闭，可刷新设备或重算关联做复核。";
        }
        if (inProgress) {
            return what + "建议做什么：当前为「处理中」。网管连续探测确认恢复后会自动关闭；也可再次标准处置加速复核。";
        }
        return what + "建议做什么：先点下方「下一步」主按钮。";
    }

    private String scenarioHint(String scenario) {
        return switch (scenario) {
            case "OFFLINE" -> "建议先确认设备是否还能通，再处理关联告警；先不要改配置。";
            case "LINK", "STORM" -> "建议先确认链路/风暴带来的关联告警；查看配置只为核对，不要盲目回滚。";
            case "PERFORMANCE" -> "建议先看排查依据里的性能数据，变更前先备份；仿真数据一般不当作处置依据。";
            case "CONFIG" -> "建议先拉取当前配置并对照变更记录，确认后再回滚。";
            default -> "建议先执行「下一步」主操作，再决定是否备份或回滚。";
        };
    }

    public String detectScenario(Device device, Alarm alarm) {
        if (alarm != null) {
            String blob = (nullToEmpty(alarm.getTitle()) + " " + nullToEmpty(alarm.getDescription())
                    + " " + nullToEmpty(alarm.getTrapType())).toLowerCase(Locale.ROOT);
            if (blob.contains("performance") || blob.contains("cpu") || blob.contains("内存")
                    || blob.contains("基线") || blob.contains("阈值")
                    || "PERFORMANCE".equalsIgnoreCase(alarm.getTrapType())
                    || "BASELINE_ANOMALY".equalsIgnoreCase(alarm.getTrapType())) {
                return "PERFORMANCE";
            }
            if (blob.contains("stp") || blob.contains("拓扑变更") || blob.contains("link")
                    || blob.contains("链路") || "LINK".equalsIgnoreCase(alarm.getCorrelationType())) {
                return "LINK";
            }
            if ("STORM".equals(alarm.getCorrelationType()) || blob.contains("风暴")) {
                return "STORM";
            }
            if (blob.contains("offline") || blob.contains("离线") || blob.contains("不可达")) {
                return "OFFLINE";
            }
            if ((alarm.getCorrelationNote() != null && alarm.getCorrelationNote().contains("配置"))
                    || blob.contains("配置") || blob.contains("config")) {
                return "CONFIG";
            }
        }
        if (device != null && !"online".equalsIgnoreCase(device.getStatus())) {
            return "OFFLINE";
        }
        return "GENERIC";
    }

    private String scenarioLabel(String scenario) {
        return switch (scenario) {
            case "OFFLINE" -> "设备离线";
            case "STORM" -> "告警风暴";
            case "LINK" -> "链路/拓扑变更";
            case "PERFORMANCE" -> "性能异常";
            case "CONFIG" -> "配置相关";
            default -> "通用处置";
        };
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> step(int seq, String code, String title, String detail,
                                     String action, Map<String, Object> params, boolean executable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("code", code);
        m.put("title", title);
        m.put("detail", detail);
        m.put("action", action);
        m.put("params", params);
        m.put("executable", executable);
        m.put("needConfirm", true);
        m.put("real", true);
        return m;
    }

    private Map<String, Object> timelineEvent(LocalDateTime at, String type, String title,
                                              String text, String tag) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", at != null ? at : LocalDateTime.now());
        m.put("type", type);
        m.put("title", title);
        m.put("text", text);
        m.put("tag", tag);
        return m;
    }

    private String narrate(List<Map<String, Object>> events, Alarm alarm) {
        if (events.isEmpty()) {
            return "暂无时间线事件。";
        }
        long activeRelated = events.stream()
                .filter(e -> "child".equals(e.get("type")) || "related_alarm".equals(e.get("type")))
                .count();
        StringBuilder sb = new StringBuilder();
        if (alarm != null) {
            sb.append("告警「").append(alarm.getTitle()).append("」状态 ")
                    .append(alarm.getStatus() != null ? alarm.getStatus().name() : "-");
            if (activeRelated > 0) {
                sb.append("，关联噪音/子告警 ").append(activeRelated).append(" 条");
            }
            sb.append("。可执行标准处置写入确认。");
        } else {
            sb.append("共 ").append(events.size()).append(" 条时间线记录。");
        }
        return sb.toString();
    }

    private Map<String, Object> fail(Map<String, Object> result, String error) {
        result.put("ok", false);
        result.put("error", error);
        return result;
    }

    private Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (kv == null) {
            return m;
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
