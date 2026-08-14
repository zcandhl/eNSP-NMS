package com.ensp.nms.service.llm;

import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.DeviceConfig;
import com.ensp.nms.entity.PerformanceData;
import com.ensp.nms.security.SecurityUtils;
import com.ensp.nms.service.AlarmService;
import com.ensp.nms.service.AuditLogService;
import com.ensp.nms.service.DeviceConfigService;
import com.ensp.nms.service.DeviceService;
import com.ensp.nms.service.PerformanceMonitorService;
import com.ensp.nms.service.TopologyService;
import com.ensp.nms.service.aiops.AiopsActionService;
import com.ensp.nms.service.aiops.AlarmCorrelationService;
import com.ensp.nms.service.aiops.AnomalyDetectService;
import com.ensp.nms.service.aiops.RcaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 页内半闭环工具执行：白名单映射到现有 AIOps/配置/告警服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmToolExecutor {

    public static final Set<String> ALLOWED = Set.of(
            "inspect",
            "refresh_device",
            "refresh_offline",
            "ack_noise",
            "dispose_incident",
            "backup",
            "restore_latest",
            "pull_live_config",
            "ack_alarm",
            "get_device_summary",
            "get_topo_neighbors",
            "get_perf_snapshot",
            "get_config_diff_summary",
            "list_active_alarms_for_device",
            "run_path_hint",
            "navigate_hint",
            "search_devices",
            "get_network_overview",
            "explain_cli_output",
            "suggest_config_commands",
            "get_alarm_detail",
            "list_config_backups",
            "get_backup_schedule_status",
            "ping_check",
            "probe_device",
            "traceroute_hint",
            "highlight_topology_nodes",
            "open_workbench_event",
            "get_interface_brief",
            "run_show_command",
            "get_config_compliance_score"
    );

    public static final Set<String> READ_ONLY = Set.of(
            "inspect",
            "pull_live_config",
            "get_device_summary",
            "get_topo_neighbors",
            "get_perf_snapshot",
            "get_config_diff_summary",
            "list_active_alarms_for_device",
            "run_path_hint",
            "navigate_hint",
            "search_devices",
            "get_network_overview",
            "explain_cli_output",
            "suggest_config_commands",
            "get_alarm_detail",
            "list_config_backups",
            "get_backup_schedule_status",
            "ping_check",
            "probe_device",
            "traceroute_hint",
            "highlight_topology_nodes",
            "open_workbench_event",
            "get_interface_brief",
            "run_show_command",
            "get_config_compliance_score"
    );

    private final AiopsActionService aiopsActionService;
    private final AlarmCorrelationService alarmCorrelationService;
    private final RcaService rcaService;
    private final DeviceService deviceService;
    private final DeviceConfigService deviceConfigService;
    private final AlarmService alarmService;
    private final TopologyService topologyService;
    private final PerformanceMonitorService performanceMonitorService;
    private final AnomalyDetectService anomalyDetectService;
    private final AuditLogService auditLogService;
    private final com.ensp.nms.service.aiops.HealthScoreService healthScoreService;
    private final com.ensp.nms.service.BackupScheduleService backupScheduleService;

    /**
     * @param confirmed 写操作必须为 true；只读工具可放宽
     */
    public Map<String, Object> execute(String name, Map<String, Object> args, boolean confirmed) {
        Map<String, Object> result = new LinkedHashMap<>();
        String tool = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        result.put("tool", tool);
        result.put("autoChange", false);

        if (!ALLOWED.contains(tool)) {
            result.put("ok", false);
            result.put("error", "不允许的工具: " + name);
            auditTool(tool, args, result);
            return result;
        }

        Map<String, Object> a = args != null ? args : Map.of();
        Long deviceId = toLong(a.get("deviceId"));
        Long alarmId = toLong(a.get("alarmId"));

        boolean readOnly = READ_ONLY.contains(tool);
        if (!readOnly && !confirmed) {
            result.put("ok", false);
            result.put("error", "需要确认（confirmed=true）后才会执行");
            result.put("needConfirm", true);
            auditTool(tool, a, result);
            return result;
        }

        // 回滚永不作为无人值守自动动作（即使 confirmed，也禁止 autoUnattended 标记）
        if ("restore_latest".equals(tool) && Boolean.TRUE.equals(a.get("autoUnattended"))) {
            result.put("ok", false);
            result.put("error", "无人值守禁止自动回滚配置，请人工确认执行");
            auditTool(tool, a, result);
            return result;
        }

        try {
            Map<String, Object> executed = switch (tool) {
                case "inspect" -> {
                    Map<String, Object> corr = alarmCorrelationService.correlateOpenAlarms();
                    Map<String, Object> rca = rcaService.analyze();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ok", true);
                    m.put("tool", tool);
                    m.put("autoChange", false);
                    m.put("message", "已重算告警关联与根因");
                    m.put("detail", Map.of(
                            "correlation", corr,
                            "rcaCandidates", rca.get("candidates") instanceof java.util.List<?> list
                                    ? Math.min(list.size(), 5) : 0
                    ));
                    yield m;
                }
                case "refresh_device" -> {
                    if (deviceId == null) {
                        yield fail(tool, "缺少 deviceId");
                    }
                    var d = deviceService.refreshDeviceStatus(deviceId);
                    Map<String, Object> snap = deviceService.getProbeSnapshot(deviceId);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ok", true);
                    m.put("tool", tool);
                    m.put("message", String.format("设备「%s」状态 → %s（探测=%s，连续可达 %s/%s）",
                            d.getName(), d.getStatus(),
                            d.getLastProbeMethod() != null ? d.getLastProbeMethod() : "-",
                            snap.getOrDefault("consecutiveSuccess", 0),
                            snap.getOrDefault("onlineAfterSuccesses", 2)));
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("deviceId", d.getId());
                    detail.put("status", d.getStatus());
                    detail.put("name", d.getName());
                    detail.put("probeMethod", d.getLastProbeMethod());
                    detail.putAll(snap);
                    m.put("detail", detail);
                    yield m;
                }
                case "refresh_offline" -> merge(tool, aiopsActionService.refreshOfflineDevices(true));
                case "ack_noise" -> merge(tool, aiopsActionService.ackSecondaryWithConfirm(deviceId, alarmId, true, null));
                case "dispose_incident" -> {
                    if (alarmId == null) {
                        yield fail(tool, "缺少 alarmId");
                    }
                    yield merge(tool, aiopsActionService.disposeIncidentWithConfirm(alarmId, deviceId, true));
                }
                case "backup" -> {
                    if (deviceId == null) {
                        yield fail(tool, "缺少 deviceId");
                    }
                    yield merge(tool, aiopsActionService.backupWithConfirm(deviceId, true, "LLM助手确认备份"));
                }
                case "restore_latest" -> {
                    if (deviceId == null) {
                        yield fail(tool, "缺少 deviceId");
                    }
                    yield merge(tool, aiopsActionService.restoreLatestWithConfirm(deviceId, true, "LLM助手确认回滚"));
                }
                case "pull_live_config" -> {
                    if (deviceId == null) {
                        yield fail(tool, "缺少 deviceId");
                    }
                    String type = a.get("configType") != null ? String.valueOf(a.get("configType")) : "running";
                    String content = deviceConfigService.pullLiveConfig(deviceId, type);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ok", true);
                    m.put("tool", tool);
                    m.put("message", "已拉取设备 #" + deviceId + " 的 " + type + " 配置（只读）");
                    m.put("configText", content != null ? content : "");
                    m.put("detail", Map.of(
                            "deviceId", deviceId,
                            "configType", type,
                            "length", content != null ? content.length() : 0
                    ));
                    yield m;
                }
                case "ack_alarm" -> {
                    if (alarmId == null) {
                        yield fail(tool, "缺少 alarmId");
                    }
                    String note = a.get("note") != null ? String.valueOf(a.get("note")) : "LLM助手确认告警";
                    String by;
                    try {
                        by = SecurityUtils.currentOperator();
                    } catch (Exception e) {
                        by = "llm-assistant";
                    }
                    var opt = alarmService.acknowledgeAlarm(alarmId, by, note);
                    if (opt.isEmpty()) {
                        yield fail(tool, "告警不存在");
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ok", true);
                    m.put("tool", tool);
                    String st = opt.get().getStatus() != null ? opt.get().getStatus().name() : "";
                    m.put("message", "CLEARED".equals(st)
                            ? "告警 #" + alarmId + " 已阅知关闭"
                            : "告警 #" + alarmId + " 已进入处理中");
                    m.put("detail", Map.of("alarmId", alarmId, "status", st));
                    yield m;
                }
                case "get_device_summary" -> getDeviceSummary(tool, deviceId);
                case "get_topo_neighbors" -> getTopoNeighbors(tool, deviceId);
                case "get_perf_snapshot" -> getPerfSnapshot(tool, deviceId);
                case "get_config_diff_summary" -> getConfigDiffSummary(tool, deviceId, a);
                case "list_active_alarms_for_device" -> listActiveAlarms(tool, deviceId);
                case "run_path_hint" -> runPathHint(tool, deviceId, toLong(a.get("targetDeviceId")));
                case "navigate_hint" -> navigateHint(tool, a);
                case "search_devices" -> searchDevices(tool, a);
                case "get_network_overview" -> getNetworkOverview(tool);
                case "explain_cli_output" -> explainCliOutput(tool, deviceId, a);
                case "suggest_config_commands" -> suggestConfigCommands(tool, deviceId, a);
                case "get_alarm_detail" -> getAlarmDetail(tool, alarmId);
                case "list_config_backups" -> listConfigBackups(tool, deviceId);
                case "get_backup_schedule_status" -> getBackupScheduleStatus(tool, deviceId);
                case "ping_check", "probe_device" -> pingCheck(tool, deviceId);
                case "traceroute_hint" -> runPathHint(tool, deviceId, toLong(a.get("targetDeviceId")));
                case "highlight_topology_nodes" -> highlightTopologyNodes(tool, a, deviceId);
                case "open_workbench_event" -> openWorkbenchEvent(tool, alarmId, deviceId);
                case "get_interface_brief" -> getInterfaceBrief(tool, deviceId);
                case "run_show_command" -> runShowCommand(tool, deviceId, a);
                case "get_config_compliance_score" -> getConfigComplianceScore(tool, deviceId, a);
                default -> fail(tool, "未实现: " + tool);
            };
            auditTool(tool, a, executed);
            return executed;
        } catch (Exception e) {
            log.warn("LLM tool 执行失败 tool={}: {}", tool, e.getMessage());
            Map<String, Object> failed = fail(tool, e.getMessage() != null ? e.getMessage() : "执行失败");
            auditTool(tool, a, failed);
            return failed;
        }
    }

    private Map<String, Object> getDeviceSummary(String tool, Long deviceId) {
        if (deviceId == null) {
            return fail(tool, "缺少 deviceId");
        }
        Device d = deviceService.getDeviceById(deviceId).orElse(null);
        if (d == null) {
            return fail(tool, "设备不存在");
        }
        Map<String, Object> snap = deviceService.getProbeSnapshot(deviceId);
        List<Alarm> alarms = alarmService.getAlarmsByDeviceId(deviceId).stream()
                .filter(a -> a.getStatus() == Alarm.Status.ACTIVE || a.getStatus() == Alarm.Status.ACKNOWLEDGED)
                .limit(10)
                .toList();
        List<Map<String, Object>> neighbors;
        try {
            neighbors = topologyService.getDeviceNeighbors(deviceId);
        } catch (Exception e) {
            neighbors = List.of();
        }
        boolean hasBackup = !deviceConfigService.getConfigSummariesByDeviceId(deviceId).isEmpty();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deviceId", d.getId());
        detail.put("name", d.getName());
        detail.put("ipAddress", d.getIpAddress());
        detail.put("status", d.getStatus());
        detail.put("deviceType", d.getDeviceType());
        detail.put("lastProbeMethod", d.getLastProbeMethod());
        detail.put("lastSeen", d.getLastSeen() != null ? d.getLastSeen().toString() : null);
        detail.put("probe", snap);
        detail.put("openAlarmCount", alarms.size());
        detail.put("neighborCount", neighbors.size());
        detail.put("hasBackup", hasBackup);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", String.format("设备「%s」%s，开告警 %d，邻居 %d，备份=%s",
                d.getName(), d.getStatus(), alarms.size(), neighbors.size(), hasBackup ? "有" : "无"));
        m.put("detail", detail);
        return m;
    }

    private Map<String, Object> getTopoNeighbors(String tool, Long deviceId) {
        if (deviceId == null) {
            return fail(tool, "缺少 deviceId");
        }
        List<Map<String, Object>> neighbors = topologyService.getDeviceNeighbors(deviceId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", "设备 #" + deviceId + " 共 " + neighbors.size() + " 个拓扑邻居");
        m.put("detail", Map.of("deviceId", deviceId, "neighbors", neighbors.stream().limit(20).toList()));
        return m;
    }

    private Map<String, Object> getPerfSnapshot(String tool, Long deviceId) {
        if (deviceId == null) {
            return fail(tool, "缺少 deviceId");
        }
        PerformanceData latest = performanceMonitorService.getLatestPerformance(deviceId);
        List<PerformanceData> ports = performanceMonitorService.getLatestPortMetrics(deviceId);
        List<Map<String, Object>> anomalies = anomalyDetectService.listRecentAnomalies(20).stream()
                .filter(row -> Objects.equals(toLong(row.get("deviceId")), deviceId))
                .limit(5)
                .toList();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deviceId", deviceId);
        if (latest != null) {
            detail.put("cpuUsage", latest.getCpuUsage());
            detail.put("memoryUsage", latest.getMemoryUsage());
            detail.put("temperature", latest.getTemperature());
            detail.put("timestamp", latest.getTimestamp() != null ? latest.getTimestamp().toString() : null);
        } else {
            detail.put("cpuUsage", null);
            detail.put("message", "暂无性能采样");
        }
        List<Map<String, Object>> portBrief = new ArrayList<>();
        for (PerformanceData p : ports.stream().limit(8).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("portName", p.getPortName());
            row.put("portIndex", p.getPortIndex());
            row.put("ifInRate", p.getIfInRate());
            row.put("ifOutRate", p.getIfOutRate());
            portBrief.add(row);
        }
        detail.put("ports", portBrief);
        detail.put("anomalies", anomalies);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        if (latest != null) {
            m.put("message", String.format("性能快照 CPU=%s%% MEM=%s%% TEMP=%s，端口样本 %d，基线异常 %d",
                    fmt(latest.getCpuUsage()), fmt(latest.getMemoryUsage()), fmt(latest.getTemperature()),
                    portBrief.size(), anomalies.size()));
        } else {
            m.put("message", "暂无性能采样数据");
        }
        m.put("detail", detail);
        return m;
    }

    private Map<String, Object> getConfigDiffSummary(String tool, Long deviceId, Map<String, Object> a) {
        if (deviceId == null) {
            return fail(tool, "缺少 deviceId");
        }
        var summaries = deviceConfigService.getConfigSummariesByDeviceId(deviceId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deviceId", deviceId);
        detail.put("backupCount", summaries.size());

        if (summaries.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("tool", tool);
            m.put("autoChange", false);
            m.put("message", "设备 #" + deviceId + " 尚无备份，无法对比");
            m.put("detail", detail);
            return m;
        }

        Long latestId = summaries.get(0).getId();
        DeviceConfig backup = deviceConfigService.getConfigById(latestId).orElse(null);
        String backupText = backup != null && backup.getContent() != null ? backup.getContent() : "";
        detail.put("latestBackupId", latestId);
        detail.put("latestBackupAt", backup != null && backup.getCreatedAt() != null
                ? backup.getCreatedAt().toString() : null);
        detail.put("backupLines", countLines(backupText));

        String liveText = null;
        String liveError = null;
        boolean pullLive = !"false".equalsIgnoreCase(String.valueOf(a.getOrDefault("pullLive", "true")));
        if (pullLive) {
            try {
                liveText = deviceConfigService.pullLiveConfig(deviceId, "running");
            } catch (Exception e) {
                liveError = e.getMessage();
            }
        }

        if (liveText != null) {
            int[] diff = summarizeLineDiff(backupText, liveText);
            detail.put("liveLines", countLines(liveText));
            detail.put("addedLines", diff[0]);
            detail.put("removedLines", diff[1]);
            detail.put("unchangedLines", diff[2]);
            detail.put("hasDiff", diff[0] + diff[1] > 0);
        } else {
            detail.put("livePullSkippedOrFailed", true);
            detail.put("liveError", liveError);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        if (liveText != null) {
            boolean hasDiff = Boolean.TRUE.equals(detail.get("hasDiff"));
            m.put("message", hasDiff
                    ? String.format("running 与最新备份有差异：+%s / -%s 行", detail.get("addedLines"), detail.get("removedLines"))
                    : "running 与最新备份无明显行差异（摘要级）");
        } else {
            m.put("message", "已汇总最新备份元数据；拉取 running 失败或跳过"
                    + (liveError != null ? "：" + liveError : ""));
        }
        m.put("detail", detail);
        return m;
    }

    private Map<String, Object> listActiveAlarms(String tool, Long deviceId) {
        if (deviceId == null) {
            return fail(tool, "缺少 deviceId");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Alarm a : alarmService.getAlarmsByDeviceId(deviceId)) {
            if (a.getStatus() != Alarm.Status.ACTIVE && a.getStatus() != Alarm.Status.ACKNOWLEDGED) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("title", a.getTitle());
            row.put("severity", a.getSeverity() != null ? a.getSeverity().name() : null);
            row.put("status", a.getStatus() != null ? a.getStatus().name() : null);
            row.put("secondary", a.isSecondaryAlarm());
            row.put("parentAlarmId", a.getParentAlarmId());
            row.put("occurredAt", a.getOccurredAt() != null ? a.getOccurredAt().toString() : null);
            rows.add(row);
            if (rows.size() >= 20) break;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", "设备 #" + deviceId + " 活动/处理中告警 " + rows.size() + " 条");
        m.put("detail", Map.of("deviceId", deviceId, "alarms", rows));
        return m;
    }

    private Map<String, Object> navigateHint(String tool, Map<String, Object> a) {
        String raw = a.get("target") != null ? String.valueOf(a.get("target"))
                : (a.get("keyword") != null ? String.valueOf(a.get("keyword")) : "");
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        String path = "/";
        String label = "概览";
        Map<String, Object> query = new LinkedHashMap<>();
        if (key.contains("告警") || key.contains("alarm")) {
            path = "/alarms";
            label = "告警管理";
        } else if (key.contains("拓扑") || key.contains("topo")) {
            path = "/topology";
            label = "拓扑视图";
        } else if (key.contains("性能") || key.contains("perf")) {
            path = "/performance";
            label = "性能监控";
        } else if (key.contains("配置") || key.contains("config") || key.contains("备份")) {
            path = "/configs";
            label = "配置管理";
        } else if (key.contains("设备") || key.contains("device")) {
            path = "/devices";
            label = "设备管理";
        } else if (key.contains("智能") || key.contains("aiops") || key.contains("工作台")) {
            path = "/aiops/workbench";
            label = "智能运维工作台";
        } else if (key.contains("审计") || key.contains("audit")) {
            path = "/audit";
            label = "审计中心";
        } else if (key.contains("策略") || key.contains("policy")) {
            path = "/aiops/policy";
            label = "AIOps 策略";
        }
        Long deviceId = toLong(a.get("deviceId"));
        Long alarmId = toLong(a.get("alarmId"));
        if (deviceId != null) query.put("deviceId", String.valueOf(deviceId));
        if (alarmId != null) query.put("alarmId", String.valueOf(alarmId));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("path", path);
        detail.put("label", label);
        detail.put("query", query);
        detail.put("navigate", true);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", "建议前往「" + label + "」(" + path + ")");
        m.put("detail", detail);
        return m;
    }

    private Map<String, Object> searchDevices(String tool, Map<String, Object> a) {
        String keyword = a.get("keyword") != null ? String.valueOf(a.get("keyword")).trim()
                : (a.get("q") != null ? String.valueOf(a.get("q")).trim() : "");
        if (keyword.isBlank()) {
            return fail(tool, "缺少 keyword");
        }
        var page = deviceService.queryDevices(keyword, null, null,
                org.springframework.data.domain.PageRequest.of(0, 10));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Device d : page.getContent()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("name", d.getName());
            row.put("ipAddress", d.getIpAddress());
            row.put("status", d.getStatus());
            row.put("deviceType", d.getDeviceType());
            rows.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", "关键字「" + keyword + "」匹配设备 " + rows.size() + " 台（最多 10）");
        m.put("detail", Map.of("keyword", keyword, "total", page.getTotalElements(), "devices", rows));
        return m;
    }

    private Map<String, Object> getNetworkOverview(String tool) {
        Map<String, Object> health = healthScoreService.compute();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("networkScore", health.get("networkScore"));
        detail.put("level", health.get("level"));
        detail.put("deviceTotal", health.get("deviceTotal"));
        detail.put("onlineCount", health.get("onlineCount"));
        detail.put("offlineCount", health.get("offlineCount"));
        detail.put("activeAlarms", health.get("activeAlarms"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", String.format("网络健康分 %s（%s），在线 %s/%s，开告警 %s",
                health.get("networkScore"), health.get("level"),
                health.get("onlineCount"), health.get("deviceTotal"), health.get("activeAlarms")));
        m.put("detail", detail);
        return m;
    }

    private Map<String, Object> explainCliOutput(String tool, Long deviceId, Map<String, Object> a) {
        String output = a.get("output") != null ? String.valueOf(a.get("output")) : "";
        if (output.isBlank()) {
            return fail(tool, "缺少 output（终端输出）");
        }
        String clipped = output.length() > 8000 ? output.substring(output.length() - 8000) : output;
        List<String> hints = new ArrayList<>();
        String low = clipped.toLowerCase(Locale.ROOT);
        if (low.contains("error") || clipped.contains("Error") || clipped.contains("失败") || clipped.contains("Wrong")) {
            hints.add("输出含错误关键字，优先核对命令语法、视图层级（system-view）与权限。");
        }
        if (clipped.contains("Incomplete") || clipped.contains("Ambiguous") || clipped.contains("Unrecognized")) {
            hints.add("华为 CLI 提示命令不完整/歧义/无法识别，可用 ? 查看补全。");
        }
        if (low.contains("down") || clipped.contains("Administratively DOWN") || clipped.contains("offline")) {
            hints.add("存在接口/链路 down 迹象，建议 display interface brief 与邻居连通性核对。");
        }
        if (clipped.contains("OSPF") || clipped.contains("ospf") || clipped.contains("BGP")) {
            hints.add("涉及动态路由，建议核对 area/process 与 display ospf peer / display ip routing-table。");
        }
        if (hints.isEmpty()) {
            hints.add("未匹配到典型错误模板；请结合设备型号与当前视图逐行解读。");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deviceId", deviceId);
        detail.put("outputLength", output.length());
        detail.put("outputPreview", clipped.length() > 2000 ? clipped.substring(clipped.length() - 2000) : clipped);
        detail.put("hints", hints);
        detail.put("fillable", false);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", "已解析终端输出（" + clipped.length() + " 字符），提示 " + hints.size() + " 条");
        m.put("detail", detail);
        return m;
    }

    private Map<String, Object> suggestConfigCommands(String tool, Long deviceId, Map<String, Object> a) {
        String intent = a.get("intent") != null ? String.valueOf(a.get("intent")).trim().toLowerCase(Locale.ROOT) : "general";
        String keyword = a.get("keyword") != null ? String.valueOf(a.get("keyword")).toLowerCase(Locale.ROOT) : intent;
        List<Map<String, Object>> steps = new ArrayList<>();

        if (keyword.contains("vlan") || keyword.contains("二层")) {
            steps.add(cmdStep("system-view", "进入系统视图"));
            steps.add(cmdStep("vlan 10", "创建 VLAN（按需改号）"));
            steps.add(cmdStep("interface GigabitEthernet0/0/1", "进入接入口（按实际口名）"));
            steps.add(cmdStep("port link-type access", "设为 access"));
            steps.add(cmdStep("port default vlan 10", "划入 VLAN 10"));
            steps.add(cmdStep("quit", "退出接口"));
        } else if (keyword.contains("ospf") || keyword.contains("路由")) {
            steps.add(cmdStep("system-view", "进入系统视图"));
            steps.add(cmdStep("ospf 1 router-id 1.1.1.1", "启用 OSPF（改 router-id）"));
            steps.add(cmdStep("area 0.0.0.0", "进入 area 0"));
            steps.add(cmdStep("network 192.168.1.0 0.0.0.255", "宣告网段（按规划修改）"));
            steps.add(cmdStep("quit", "退出 area"));
            steps.add(cmdStep("display ospf peer", "核查邻居（只读）"));
        } else if (keyword.contains("ip") || keyword.contains("接口地址") || keyword.contains("interface")) {
            steps.add(cmdStep("system-view", "进入系统视图"));
            steps.add(cmdStep("interface GigabitEthernet0/0/0", "进入三层口（按实际）"));
            steps.add(cmdStep("ip address 192.168.1.1 255.255.255.0", "配置 IP（按规划）"));
            steps.add(cmdStep("undo shutdown", "开启接口"));
            steps.add(cmdStep("display ip interface brief", "核查地址（只读）"));
        } else if (keyword.contains("acl") || keyword.contains("安全")) {
            steps.add(cmdStep("system-view", "进入系统视图"));
            steps.add(cmdStep("acl number 2000", "基本 ACL"));
            steps.add(cmdStep("rule 5 deny source 10.0.0.0 0.0.0.255", "示例拒绝规则（按需）"));
            steps.add(cmdStep("quit", "退出 ACL"));
        } else if (keyword.contains("save") || keyword.contains("保存") || keyword.contains("diagnose") || keyword.contains("排查")) {
            steps.add(cmdStep("display current-configuration", "查看 running（只读）"));
            steps.add(cmdStep("display interface brief", "接口摘要（只读）"));
            steps.add(cmdStep("display ip routing-table", "路由表（只读）"));
            steps.add(cmdStep("ping 192.168.1.1", "连通性探测（改目标）"));
        } else {
            steps.add(cmdStep("system-view", "进入系统视图"));
            steps.add(cmdStep("display current-configuration", "查看当前配置（只读）"));
            steps.add(cmdStep("display interface brief", "接口状态（只读）"));
            steps.add(cmdStep("save", "保存配置（写操作，需人工确认）"));
        }

        String fillCommand = steps.isEmpty() ? "" : String.valueOf(steps.get(0).get("command"));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("deviceId", deviceId);
        detail.put("intent", intent);
        detail.put("steps", steps);
        detail.put("commands", steps.stream().map(s -> String.valueOf(s.get("command"))).toList());
        detail.put("fillCommand", fillCommand);
        detail.put("fillable", true);
        detail.put("note", "仅建议文案，需人工在设备上执行；不会自动下发。");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", deviceId != null
                ? ("已生成 " + steps.size() + " 步配置建议（绑定设备 #" + deviceId + "）")
                : ("已生成 " + steps.size() + " 步通用配置建议（未绑定设备，按 eNSP/华为 CLI 模板）"));
        m.put("detail", detail);
        return m;
    }

    private static Map<String, Object> cmdStep(String command, String purpose) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("command", command);
        s.put("purpose", purpose);
        return s;
    }

    private Map<String, Object> getAlarmDetail(String tool, Long alarmId) {
        if (alarmId == null) {
            return fail(tool, "缺少 alarmId");
        }
        Alarm a = alarmService.getAlarmById(alarmId).orElse(null);
        if (a == null) {
            return fail(tool, "告警不存在");
        }
        List<Map<String, Object>> children = new ArrayList<>();
        Long deviceId = a.getDeviceId();
        List<Alarm> pool = deviceId != null
                ? alarmService.getAlarmsByDeviceId(deviceId)
                : alarmService.getAllAlarms();
        for (Alarm c : pool) {
            if (!Objects.equals(c.getParentAlarmId(), alarmId)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("title", c.getTitle());
            row.put("severity", c.getSeverity() != null ? c.getSeverity().name() : null);
            row.put("status", c.getStatus() != null ? c.getStatus().name() : null);
            row.put("secondary", c.isSecondaryAlarm());
            children.add(row);
            if (children.size() >= 20) break;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", a.getId());
        detail.put("title", a.getTitle());
        detail.put("description", a.getDescription());
        detail.put("severity", a.getSeverity() != null ? a.getSeverity().name() : null);
        detail.put("status", a.getStatus() != null ? a.getStatus().name() : null);
        detail.put("deviceId", a.getDeviceId());
        detail.put("deviceName", a.getDeviceName());
        detail.put("deviceIp", a.getDeviceIp());
        detail.put("parentAlarmId", a.getParentAlarmId());
        detail.put("secondary", a.isSecondaryAlarm());
        detail.put("occurredAt", a.getOccurredAt() != null ? a.getOccurredAt().toString() : null);
        detail.put("acknowledgedAt", a.getAcknowledgedAt() != null ? a.getAcknowledgedAt().toString() : null);
        detail.put("clearedAt", a.getClearedAt() != null ? a.getClearedAt().toString() : null);
        detail.put("childAlarms", children);
        detail.put("childCount", children.size());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", "告警 #" + alarmId + " 详情（关联子告警 " + children.size() + "）");
        m.put("detail", detail);
        return m;
    }

    private Map<String, Object> listConfigBackups(String tool, Long deviceId) {
        if (deviceId == null) {
            return fail(tool, "缺少 deviceId");
        }
        var summaries = deviceConfigService.getConfigSummariesByDeviceId(deviceId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var s : summaries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("configType", s.getConfigType());
            row.put("configVersion", s.getConfigVersion());
            row.put("description", s.getDescription());
            row.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
            row.put("createdBy", s.getCreatedBy());
            rows.add(row);
            if (rows.size() >= 30) break;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", "设备 #" + deviceId + " 备份 " + rows.size() + " 条（最多 30）");
        m.put("detail", Map.of("deviceId", deviceId, "total", summaries.size(), "backups", rows));
        return m;
    }

    private Map<String, Object> getBackupScheduleStatus(String tool, Long deviceId) {
        if (deviceId == null) {
            return fail(tool, "缺少 deviceId");
        }
        var schedules = backupScheduleService.getSchedulesByDeviceId(deviceId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var s : schedules) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("scheduleType", s.getScheduleType());
            row.put("scheduleTime", s.getScheduleTime());
            row.put("configType", s.getConfigType());
            row.put("active", s.getIsActive());
            row.put("lastRun", s.getLastRun() != null ? s.getLastRun().toString() : null);
            row.put("lastStatus", s.getLastStatus());
            row.put("lastResult", s.getLastResult());
            rows.add(row);
        }
        long active = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("active"))).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", "设备 #" + deviceId + " 计划备份 " + rows.size() + " 条（启用 " + active + "）");
        m.put("detail", Map.of("deviceId", deviceId, "schedules", rows));
        return m;
    }

    private Map<String, Object> pingCheck(String tool, Long deviceId) {
        if (deviceId == null) {
            return fail(tool, "缺少 deviceId");
        }
        Map<String, Object> test = deviceService.testConnectivity(deviceId);
        Map<String, Object> snap = deviceService.getProbeSnapshot(deviceId);
        Map<String, Object> detail = new LinkedHashMap<>(test);
        detail.put("probeSnapshot", snap);
        boolean icmpOk = test.get("icmp") instanceof Map<?, ?> icmp && Boolean.TRUE.equals(icmp.get("ok"));
        boolean snmpOk = test.get("snmp") instanceof Map<?, ?> snmp && Boolean.TRUE.equals(snmp.get("ok"));
        String summary = icmpOk || snmpOk
                ? ("可达（ICMP=" + icmpOk + ", SNMP=" + snmpOk + "）")
                : ("不可达或未测通（ICMP=" + icmpOk + ", SNMP=" + snmpOk + "）");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", "设备 #" + deviceId + " 连通性探测：" + summary);
        m.put("detail", detail);
        return m;
    }

    private Map<String, Object> highlightTopologyNodes(String tool, Map<String, Object> a, Long deviceId) {
        List<Long> ids = parseIdList(a != null ? a.get("deviceIds") : null);
        if (ids.isEmpty() && deviceId != null) {
            ids.add(deviceId);
        }
        if (ids.isEmpty() && a != null && a.get("deviceId") != null) {
            Long one = toLong(a.get("deviceId"));
            if (one != null) ids.add(one);
        }
        if (ids.isEmpty()) {
            return fail(tool, "缺少 deviceIds / deviceId");
        }
        String highlight = ids.stream().map(String::valueOf).reduce((x, y) -> x + "," + y).orElse("");
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("rca", "1");
        query.put("highlight", highlight);
        query.put("deviceId", String.valueOf(ids.get(0)));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("path", "/topology");
        detail.put("label", "拓扑视图");
        detail.put("query", query);
        detail.put("navigate", true);
        detail.put("highlight", true);
        detail.put("deviceIds", ids);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", "建议在拓扑高亮 " + ids.size() + " 台设备");
        m.put("detail", detail);
        return m;
    }

    private Map<String, Object> openWorkbenchEvent(String tool, Long alarmId, Long deviceId) {
        if (alarmId == null && deviceId == null) {
            return fail(tool, "缺少 alarmId 或 deviceId");
        }
        Map<String, Object> query = new LinkedHashMap<>();
        if (alarmId != null) query.put("alarmId", String.valueOf(alarmId));
        if (deviceId != null) query.put("deviceId", String.valueOf(deviceId));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("path", "/aiops/workbench");
        detail.put("label", "智能运维工作台");
        detail.put("query", query);
        detail.put("navigate", true);
        detail.put("openWorkbench", true);
        detail.put("alarmId", alarmId);
        detail.put("deviceId", deviceId);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", alarmId != null
                ? ("建议打开工作台事件 #" + alarmId)
                : ("建议打开工作台（设备 #" + deviceId + "）"));
        m.put("detail", detail);
        return m;
    }

    private Map<String, Object> getInterfaceBrief(String tool, Long deviceId) {
        if (deviceId == null) {
            return fail(tool, "缺少 deviceId");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (var p : deviceService.getDevicePorts(deviceId)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", p.getPortName());
                row.put("ifIndex", p.getIfIndex());
                row.put("operStatus", p.getOperStatus());
                row.put("speedHint", p.getSpeed());
                rows.add(row);
                if (rows.size() >= 48) break;
            }
        } catch (Exception e) {
            return fail(tool, "读取接口失败: " + e.getMessage());
        }
        long up = rows.stream().filter(r -> "up".equalsIgnoreCase(String.valueOf(r.get("operStatus")))).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", "设备 #" + deviceId + " 接口摘要 " + rows.size() + " 条（up=" + up + "）");
        m.put("detail", Map.of("deviceId", deviceId, "ports", rows, "upCount", up));
        return m;
    }

    private Map<String, Object> runShowCommand(String tool, Long deviceId, Map<String, Object> a) {
        if (deviceId == null) {
            return fail(tool, "缺少 deviceId");
        }
        String command = a.get("command") != null ? String.valueOf(a.get("command"))
                : (a.get("cmd") != null ? String.valueOf(a.get("cmd")) : "display interface brief");
        try {
            String output = deviceConfigService.runReadOnlyShow(deviceId, command);
            String clipped = output == null ? "" : output;
            if (clipped.length() > 12000) {
                clipped = clipped.substring(0, 12000) + "\n…(已截断)";
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("deviceId", deviceId);
            detail.put("command", DeviceConfigService.normalizeShowCommand(command));
            detail.put("output", clipped);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("tool", tool);
            m.put("autoChange", false);
            m.put("message", "已执行只读 show（设备 #" + deviceId + "）");
            m.put("detail", detail);
            m.put("configText", clipped);
            return m;
        } catch (Exception e) {
            return fail(tool, e.getMessage() != null ? e.getMessage() : "执行失败");
        }
    }

    private Map<String, Object> getConfigComplianceScore(String tool, Long deviceId, Map<String, Object> a) {
        if (deviceId == null) {
            return fail(tool, "缺少 deviceId");
        }
        Map<String, Object> diff = getConfigDiffSummary("get_config_diff_summary", deviceId, a);
        Map<String, Object> detail = new LinkedHashMap<>();
        if (diff.get("detail") instanceof Map<?, ?> d) {
            d.forEach((k, v) -> detail.put(String.valueOf(k), v));
        }
        int score = 100;
        String level = "compliant";
        String reason;
        if (Boolean.TRUE.equals(detail.get("livePullSkippedOrFailed"))) {
            score = 40;
            level = "unknown";
            reason = "无法拉取 running，合规分降级";
        } else if (detail.get("backupCount") instanceof Number n && n.intValue() == 0) {
            score = 20;
            level = "no_baseline";
            reason = "无备份基线";
        } else if (Boolean.TRUE.equals(detail.get("hasDiff"))) {
            int added = detail.get("addedLines") instanceof Number n ? n.intValue() : 0;
            int removed = detail.get("removedLines") instanceof Number n ? n.intValue() : 0;
            int delta = added + removed;
            score = Math.max(0, 100 - Math.min(80, delta * 2));
            level = score >= 80 ? "drift_minor" : (score >= 50 ? "drift" : "drift_major");
            reason = "相对最新备份差异 +" + added + " / -" + removed + " 行";
        } else {
            reason = "running 与最新备份无明显行差异";
        }
        detail.put("complianceScore", score);
        detail.put("complianceLevel", level);
        detail.put("reason", reason);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", "配置合规分 " + score + "（" + level + "）：" + reason);
        m.put("detail", detail);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> parseIdList(Object raw) {
        List<Long> ids = new ArrayList<>();
        if (raw == null) return ids;
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                Long id = toLong(o);
                if (id != null && !ids.contains(id)) ids.add(id);
            }
            return ids;
        }
        String s = String.valueOf(raw).trim();
        if (s.isBlank() || "null".equalsIgnoreCase(s)) return ids;
        for (String part : s.split("[,\\s]+")) {
            Long id = toLong(part);
            if (id != null && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    private Map<String, Object> runPathHint(String tool, Long fromId, Long toId) {
        if (fromId == null) {
            return fail(tool, "缺少 deviceId（起点）");
        }
        if (toId == null) {
            // 无终点时返回邻居摘要作为路径提示降级
            List<Map<String, Object>> neighbors = topologyService.getDeviceNeighbors(fromId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("tool", tool);
            m.put("autoChange", false);
            m.put("message", "未指定终点，返回起点邻居 " + neighbors.size() + " 个");
            m.put("detail", Map.of("fromDeviceId", fromId, "neighbors", neighbors.stream().limit(12).toList()));
            return m;
        }
        Map<String, Object> path = topologyService.findShortestPath(fromId, toId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", !Boolean.FALSE.equals(path.get("ok")) && path.get("error") == null);
        m.put("tool", tool);
        m.put("autoChange", false);
        m.put("message", path.get("message") != null ? String.valueOf(path.get("message"))
                : (path.get("error") != null ? String.valueOf(path.get("error")) : "路径计算完成"));
        m.put("detail", path);
        if (path.get("error") != null) {
            m.put("ok", false);
            m.put("error", path.get("error"));
        }
        return m;
    }

    private void auditTool(String tool, Map<String, Object> args, Map<String, Object> result) {
        try {
            boolean ok = result != null && !Boolean.FALSE.equals(result.get("ok"));
            String source = args != null && args.get("source") != null
                    ? String.valueOf(args.get("source")) : "assistant";
            if (Boolean.TRUE.equals(args != null ? args.get("autoUnattended") : null)) {
                source = "unattended";
            }
            String targetId = null;
            if (args != null) {
                if (args.get("deviceId") != null) targetId = String.valueOf(args.get("deviceId"));
                else if (args.get("alarmId") != null) targetId = String.valueOf(args.get("alarmId"));
            }
            String summary = result != null && result.get("message") != null
                    ? String.valueOf(result.get("message"))
                    : (result != null && result.get("error") != null
                    ? String.valueOf(result.get("error")) : tool);
            auditLogService.record(AuditLogService.AuditRecord.builder()
                    .module("aiops")
                    .action("llm_tool_" + tool)
                    .targetType("llm_tool")
                    .targetId(targetId)
                    .status(ok ? "success" : "failed")
                    .summary(truncate(summary, 500))
                    .detail("source=" + source + ", tool=" + tool
                            + ", args=" + truncate(String.valueOf(args), 800))
                    .refType("llm")
                    .refId(tool)
                    .build());
        } catch (Exception e) {
            log.debug("LLM tool 审计写入失败: {}", e.getMessage());
        }
    }

    private static int countLines(String text) {
        if (text == null || text.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') n++;
        }
        return n;
    }

    /** @return [added, removed, unchanged] 基于行集合的粗粒度摘要 */
    private static int[] summarizeLineDiff(String left, String right) {
        Set<String> a = new java.util.HashSet<>();
        Set<String> b = new java.util.HashSet<>();
        if (left != null) {
            for (String line : left.split("\\R", -1)) {
                if (!line.isBlank()) a.add(line.trim());
            }
        }
        if (right != null) {
            for (String line : right.split("\\R", -1)) {
                if (!line.isBlank()) b.add(line.trim());
            }
        }
        int unchanged = 0;
        for (String s : a) {
            if (b.contains(s)) unchanged++;
        }
        int removed = a.size() - unchanged;
        int added = b.size() - unchanged;
        return new int[]{added, removed, unchanged};
    }

    private static String fmt(Double v) {
        if (v == null) return "-";
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static Map<String, Object> merge(String tool, Map<String, Object> src) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (src != null) {
            m.putAll(src);
        }
        m.put("tool", tool);
        m.putIfAbsent("autoChange", false);
        if (!m.containsKey("detail") && src != null) {
            Map<String, Object> detail = new LinkedHashMap<>(src);
            detail.remove("ok");
            detail.remove("error");
            m.put("detail", detail);
        }
        return m;
    }

    private static Map<String, Object> fail(String tool, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("tool", tool);
        m.put("error", error);
        m.put("autoChange", false);
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
