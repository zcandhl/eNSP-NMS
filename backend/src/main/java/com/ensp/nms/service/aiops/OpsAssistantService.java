package com.ensp.nms.service.aiops;

import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.ConfigChangeLog;
import com.ensp.nms.entity.PerformanceData;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.service.ConfigChangeLogService;
import com.ensp.nms.service.PerformanceMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本地知识库运维助手（规则匹配 + 证据上下文，不自动改配）。
 */
@Service
@RequiredArgsConstructor
public class OpsAssistantService {

    private final DeviceRepository deviceRepository;
    private final AlarmRepository alarmRepository;
    private final ConfigChangeLogService configChangeLogService;
    private final RcaService rcaService;
    private final PerformanceMonitorService performanceMonitorService;

    private static final List<Knowledge> KB = List.of(
            new Knowledge(List.of("离线", "offline", "不通", "ping", "宕机"),
                    "设备离线排查",
                    "1) 在设备管理刷新状态或 Ping；2) 检查 eNSP 设备是否启动及网卡桥接；3) 在拓扑查看上联邻居；4) 若为连带告警，优先恢复上游；5) 需要时可在智能中心对根因设备「确认后备份」当前配置再操作。",
                    List.of("/devices", "/topology", "/aiops")),
            new Knowledge(List.of("cpu", "内存", "memory", "性能", "基线", "越限"),
                    "性能异常排查",
                    "1) 打开性能监控查看历史曲线，并确认指标来源是 SNMP 真采还是仿真；2) 仿真指标不参与阈值/基线告警；3) 区分固定阈值告警与基线异常；4) 结合端口流量与业务验证后再调整。",
                    List.of("/performance", "/alarms", "/aiops")),
            new Knowledge(List.of("配置", "备份", "恢复", "回滚", "下发", "变更"),
                    "配置变更与回滚",
                    "1) 变更前先备份；2) 在配置管理对比版本；3) 若变更后出现告警，查看变更记录时间窗；4) 智能中心可对风险设备「确认后回滚到最新备份」（回滚前会自动再备份）。助手本身不会自动改配。",
                    List.of("/configs", "/alarms", "/aiops")),
            new Knowledge(List.of("拓扑", "链路", "lldp", "邻居", "根因", "rca"),
                    "拓扑与根因",
                    "1) 执行拓扑发现刷新 LLDP/CDP；2) 查看智能中心根因候选（离线/性能/配置变更）；3) 优先处理高影响离线节点；4) 核对链路两端端口状态；5) 对根因结果可反馈「正确/不准确」以改善排序。",
                    List.of("/topology", "/aiops")),
            new Knowledge(List.of("告警", "风暴", "收敛", "连带"),
                    "告警风暴处理",
                    "1) 智能中心查看收敛后的事件；2) 连带告警先不逐条处理；3) 批量确认已知问题；4) 清除根因后观察子告警是否自动平息；5) 风暴时间窗可在策略中调整。",
                    List.of("/aiops", "/alarms")),
            new Knowledge(List.of("阈值", "策略", "基线", "sigma", "窗口"),
                    "策略与阈值",
                    "1) 在智能中心「策略」面板可调整 CPU/内存阈值、风暴窗口、基线 k·σ；2) 运行时修改立即生效，重启后回退 yml；3) 默认不对仿真指标做基线分析。",
                    List.of("/aiops", "/performance")),
            new Knowledge(List.of("仿真", "模拟", "假数据", "来源", "snmp"),
                    "指标可信度",
                    "1) 性能数据带有 snmp/simulated 来源标注；2) 仿真值仅用于曲线展示；3) 阈值告警与基线检测默认忽略仿真；4) 若设备长期无真实 OID，请检查 SNMP 团体字与 eNSP 设备能力。",
                    List.of("/performance", "/aiops"))
    );

    @Transactional(readOnly = true)
    public Map<String, Object> ask(String question, Long deviceId, Long alarmId) {
        String q = question == null ? "" : question.trim();
        Knowledge matched = match(q);

        Map<String, Object> context = new LinkedHashMap<>();
        List<Map<String, Object>> evidence = new ArrayList<>();

        if (deviceId != null) {
            deviceRepository.findById(deviceId).ifPresent(d -> {
                context.put("device", Map.of(
                        "id", d.getId(),
                        "name", d.getName() != null ? d.getName() : "",
                        "ipAddress", d.getIpAddress() != null ? d.getIpAddress() : "",
                        "status", d.getStatus() != null ? d.getStatus() : ""
                ));
                evidence.add(Map.of("type", "device", "summary",
                        "设备 " + d.getName() + " 状态=" + d.getStatus()));
            });
            PerformanceData latest = performanceMonitorService.getLatestPerformance(deviceId);
            if (latest != null) {
                Map<String, Object> perf = new LinkedHashMap<>();
                perf.put("cpu", latest.getCpuUsage());
                perf.put("memory", latest.getMemoryUsage());
                perf.put("cpuSource", latest.getCpuSource());
                perf.put("memorySource", latest.getMemorySource());
                perf.put("metricSource", latest.getMetricSourceSummary());
                context.put("performance", perf);
                evidence.add(Map.of("type", "performance", "summary",
                        "最新指标来源=" + String.valueOf(latest.getMetricSourceSummary())
                                + " CPU=" + String.valueOf(latest.getCpuUsage())
                                + " MEM=" + String.valueOf(latest.getMemoryUsage())));
            }
            List<Alarm> recentAlarms = alarmRepository.findByStatusInOrderByOccurredAtDesc(
                            List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED))
                    .stream()
                    .filter(a -> deviceId.equals(a.getDeviceId()))
                    .limit(5)
                    .toList();
            if (!recentAlarms.isEmpty()) {
                List<Map<String, Object>> alarmMaps = new ArrayList<>();
                for (Alarm a : recentAlarms) {
                    Map<String, Object> am = new LinkedHashMap<>();
                    am.put("id", a.getId());
                    am.put("title", a.getTitle() != null ? a.getTitle() : "");
                    am.put("severity", a.getSeverity() != null ? a.getSeverity().name() : "");
                    am.put("secondary", a.isSecondaryAlarm());
                    alarmMaps.add(am);
                }
                context.put("openAlarms", alarmMaps);
                evidence.add(Map.of("type", "alarms", "summary",
                        "该设备活跃告警 " + recentAlarms.size() + " 条"));
            }
            List<Map<String, Object>> changes = relatedConfigChanges(deviceId, LocalDateTime.now(), 120);
            if (!changes.isEmpty()) {
                context.put("recentChanges", changes);
                evidence.add(Map.of("type", "config", "summary",
                        "近 2 小时配置变更 " + changes.size() + " 条"));
            }
        }
        if (alarmId != null) {
            alarmRepository.findById(alarmId).ifPresent(a -> {
                context.put("alarm", Map.of(
                        "id", a.getId(),
                        "title", a.getTitle() != null ? a.getTitle() : "",
                        "severity", a.getSeverity() != null ? a.getSeverity().name() : "",
                        "secondary", a.isSecondaryAlarm(),
                        "correlationNote", a.getCorrelationNote() != null ? a.getCorrelationNote() : ""
                ));
                evidence.add(Map.of("type", "alarm", "summary",
                        "告警「" + a.getTitle() + "」"
                                + (a.isSecondaryAlarm() ? "（连带）" : "")));
            });
        }

        List<String> actions = new ArrayList<>();
        if (matched != null) {
            actions.addAll(matched.links());
        }
        if (q.toLowerCase(Locale.ROOT).contains("根因") || q.contains("rca")) {
            actions.add("/aiops");
            context.put("rca", rcaService.analyze().get("candidates"));
            evidence.add(Map.of("type", "rca", "summary", "已附带当前根因候选列表"));
        }

        String answerText = matched != null ? matched.answer() : defaultAnswer(q, context);
        answerText = enrichWithContext(answerText, context);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", q);
        result.put("title", matched != null ? matched.title() : "运维建议");
        result.put("answer", answerText);
        result.put("links", actions.isEmpty() ? List.of("/aiops", "/alarms") : actions);
        result.put("context", context);
        result.put("evidence", evidence);
        result.put("autoChange", false);
        result.put("disclaimer", "本助手基于规则与本地知识库，仅提供排查建议与证据摘要，不会自动修改设备配置。半闭环备份/回滚需在智能中心显式确认后执行。");
        return result;
    }

    public List<Map<String, Object>> relatedConfigChanges(Long deviceId, LocalDateTime around, int windowMinutes) {
        if (deviceId == null || around == null) {
            return List.of();
        }
        LocalDateTime from = around.minusMinutes(windowMinutes);
        LocalDateTime to = around.plusMinutes(5);
        return configChangeLogService.queryLogs(deviceId, null, null, null, from, to, PageRequest.of(0, 20))
                .getContent()
                .stream()
                .map(this::toChangeMap)
                .toList();
    }

    private Map<String, Object> toChangeMap(ConfigChangeLog log) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", log.getId());
        m.put("changeType", log.getChangeType());
        m.put("operator", log.getOperator());
        m.put("status", log.getStatus());
        m.put("reason", log.getReason());
        m.put("createdAt", log.getCreatedAt());
        m.put("beforeVersion", log.getBeforeVersion());
        m.put("afterVersion", log.getAfterVersion());
        return m;
    }

    private Knowledge match(String q) {
        if (q.isBlank()) {
            return KB.get(4);
        }
        String lower = q.toLowerCase(Locale.ROOT);
        Knowledge best = null;
        int bestScore = 0;
        for (Knowledge k : KB) {
            int score = 0;
            for (String kw : k.keywords()) {
                if (lower.contains(kw.toLowerCase(Locale.ROOT)) || q.contains(kw)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = k;
            }
        }
        return bestScore > 0 ? best : null;
    }

    @SuppressWarnings("unchecked")
    private String enrichWithContext(String base, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder(base);
        if (context.containsKey("alarm")) {
            Map<String, Object> alarm = (Map<String, Object>) context.get("alarm");
            if (Boolean.TRUE.equals(alarm.get("secondary"))) {
                sb.append(" 【当前告警可能为拓扑连带】请优先恢复上游根因设备，再观察本告警是否平息。");
            }
            Object note = alarm.get("correlationNote");
            if (note != null && !String.valueOf(note).isBlank()) {
                sb.append(" 关联说明：").append(note).append("。");
            }
        }
        if (context.containsKey("device")) {
            Map<String, Object> device = (Map<String, Object>) context.get("device");
            Object status = device.get("status");
            if (status != null && !"online".equalsIgnoreCase(String.valueOf(status))) {
                sb.append(" 目标设备当前状态为「").append(status).append("」，建议先恢复连通性。");
            }
        }
        if (context.containsKey("performance")) {
            Map<String, Object> perf = (Map<String, Object>) context.get("performance");
            Object src = perf.get("metricSource");
            if (PerformanceData.SOURCE_SIMULATED.equals(src) || "mixed".equals(src)) {
                sb.append(" 注意：当前性能含仿真数据（").append(src).append("），勿仅凭曲线下结论。");
            }
        }
        return sb.toString();
    }

    private String defaultAnswer(String q, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("未命中专项知识条目。建议：查看智能中心健康分与根因候选，并在告警中心按设备过滤。");
        if (context.containsKey("device")) {
            sb.append(" 已附带当前设备上下文与证据摘要。");
        }
        if (context.containsKey("alarm")) {
            sb.append(" 已附带当前告警上下文，若标记为连带请优先处理上游。");
        }
        if (!q.isBlank()) {
            sb.append(" 你的问题：「").append(q).append("」。");
        }
        return sb.toString();
    }

    private record Knowledge(List<String> keywords, String title, String answer, List<String> links) {}
}
