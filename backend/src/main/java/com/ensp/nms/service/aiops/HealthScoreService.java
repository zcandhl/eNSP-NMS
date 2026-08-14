package com.ensp.nms.service.aiops;

import com.ensp.nms.dto.ConfigHealthOverview;
import com.ensp.nms.dto.DeviceBackupHealth;
import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.PerformanceAlert;
import com.ensp.nms.repository.AiopsFeedbackRepository;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.PerformanceAlertRepository;
import com.ensp.nms.service.DeviceConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HealthScoreService {

    private final DeviceRepository deviceRepository;
    private final AlarmRepository alarmRepository;
    private final PerformanceAlertRepository performanceAlertRepository;
    private final DeviceConfigService deviceConfigService;
    private final AiopsFeedbackRepository feedbackRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> compute() {
        List<Device> devices = deviceRepository.findAll();
        // 健康评估：待处理(ACTIVE)+处理中(ACK)均扣分；仅已关闭(CLEARED)不计
        List<Alarm> openAlarms = alarmRepository.findByStatusInOrderByOccurredAtDesc(
                List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED));
        List<PerformanceAlert> openPerf = performanceAlertRepository.findByStatusInOrderByCreatedAtDesc(
                List.of("active"));

        Map<Long, List<Alarm>> alarmsByDevice = openAlarms.stream()
                .filter(a -> a.getDeviceId() != null)
                .collect(Collectors.groupingBy(Alarm::getDeviceId));
        Map<Long, List<PerformanceAlert>> perfByDevice = openPerf.stream()
                .filter(a -> a.getDeviceId() != null)
                .collect(Collectors.groupingBy(PerformanceAlert::getDeviceId));

        Map<Long, String> backupHealth = new HashMap<>();
        try {
            ConfigHealthOverview overview = deviceConfigService.getBackupHealthOverview();
            if (overview.getDevices() != null) {
                for (DeviceBackupHealth row : overview.getDevices()) {
                    if (row.getDeviceId() != null) {
                        backupHealth.put(row.getDeviceId(), row.getHealth());
                    }
                }
            }
        } catch (Exception ignored) {
            // 配置健康不可用时不扣备份分
        }

        List<Map<String, Object>> deviceScores = new ArrayList<>();
        int online = 0;
        double sum = 0;
        for (Device d : devices) {
            if ("online".equalsIgnoreCase(d.getStatus())) {
                online++;
            }
            Map<String, Object> row = scoreDevice(d,
                    alarmsByDevice.getOrDefault(d.getId(), List.of()),
                    perfByDevice.getOrDefault(d.getId(), List.of()),
                    backupHealth.get(d.getId()));
            deviceScores.add(row);
            sum += toDouble(row.get("score"));
        }

        deviceScores.sort(Comparator.comparingDouble(m -> toDouble(m.get("score"))));

        double networkScore = devices.isEmpty() ? 100.0 : sum / devices.size();
        double onlineRate = devices.isEmpty() ? 1.0 : (double) online / devices.size();
        // 全网分再按在线率微调
        networkScore = clamp(networkScore * (0.55 + 0.45 * onlineRate), 0, 100);

        List<Map<String, Object>> suggestions = buildSuggestions(devices, online, openAlarms, backupHealth, deviceScores);
        rankSuggestionsByFeedback(suggestions);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("networkScore", Math.round(networkScore * 10) / 10.0);
        result.put("level", scoreLevel(networkScore));
        result.put("deviceTotal", devices.size());
        result.put("onlineCount", online);
        result.put("offlineCount", devices.size() - online);
        result.put("activeAlarms", openAlarms.size());
        result.put("criticalAlarms", openAlarms.stream()
                .filter(a -> a.getSeverity() == Alarm.Severity.CRITICAL || a.getSeverity() == Alarm.Severity.MAJOR)
                .count());
        result.put("riskDevices", deviceScores.stream().filter(m -> toDouble(m.get("score")) < 70).limit(10).toList());
        result.put("deviceScores", deviceScores);
        result.put("suggestions", suggestions);
        return result;
    }

    private Map<String, Object> scoreDevice(Device d, List<Alarm> alarms, List<PerformanceAlert> perf, String backup) {
        double score = 100;
        List<String> reasons = new ArrayList<>();
        if (!"online".equalsIgnoreCase(d.getStatus())) {
            score -= 35;
            reasons.add("设备离线");
        }
        long critical = alarms.stream().filter(a -> a.getSeverity() == Alarm.Severity.CRITICAL).count();
        long major = alarms.stream().filter(a -> a.getSeverity() == Alarm.Severity.MAJOR).count();
        long other = alarms.size() - critical - major;
        if (critical > 0) {
            score -= Math.min(30, critical * 15);
            reasons.add("严重告警 ×" + critical);
        }
        if (major > 0) {
            score -= Math.min(20, major * 8);
            reasons.add("主要告警 ×" + major);
        }
        if (other > 0) {
            score -= Math.min(15, other * 3);
            reasons.add("其他告警 ×" + other);
        }
        long dangerPerf = perf.stream().filter(p -> "danger".equalsIgnoreCase(p.getLevel())).count();
        long warnPerf = perf.size() - dangerPerf;
        if (dangerPerf > 0) {
            score -= Math.min(15, dangerPerf * 8);
            reasons.add("性能严重越限");
        }
        if (warnPerf > 0) {
            score -= Math.min(10, warnPerf * 4);
            reasons.add("性能告警");
        }
        if ("never".equals(backup)) {
            score -= 10;
            reasons.add("从未配置备份");
        } else if ("stale".equals(backup)) {
            score -= 6;
            reasons.add("备份过期");
        } else if ("failed".equals(backup)) {
            score -= 8;
            reasons.add("备份失败");
        }
        score = clamp(score, 0, 100);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deviceId", d.getId());
        row.put("name", d.getName());
        row.put("ipAddress", d.getIpAddress());
        row.put("status", d.getStatus());
        row.put("deviceType", d.getDeviceType());
        row.put("score", Math.round(score * 10) / 10.0);
        row.put("level", scoreLevel(score));
        row.put("reasons", reasons);
        row.put("alarmCount", alarms.size());
        return row;
    }

    private List<Map<String, Object>> buildSuggestions(
            List<Device> devices, int online, List<Alarm> openAlarms,
            Map<Long, String> backupHealth, List<Map<String, Object>> deviceScores) {
        List<Map<String, Object>> list = new ArrayList<>();
        int offline = devices.size() - online;
        if (offline > 0) {
            list.add(suggestion("check_offline", "high",
                    "有 " + offline + " 台设备离线：执行将批量刷新连通性并写回状态",
                    "refresh_offline", true));
        }
        long secondary = openAlarms.stream()
                .filter(a -> a.getStatus() == Alarm.Status.ACTIVE)
                .filter(Alarm::isSecondaryAlarm)
                .count();
        long children = openAlarms.stream()
                .filter(a -> a.getStatus() == Alarm.Status.ACTIVE)
                .filter(a -> a.getParentAlarmId() != null)
                .count();
        long noise = secondary + children;
        if (noise > 0) {
            list.add(suggestion("secondary_alarms", "medium",
                    noise + " 条关联告警待确认：执行将批量写入确认（不改配）",
                    "ack_noise", true));
        }
        long never = backupHealth.values().stream().filter("never"::equals).count();
        if (never > 0) {
            Device firstNever = devices.stream()
                    .filter(d -> "never".equals(backupHealth.get(d.getId())))
                    .findFirst()
                    .orElse(null);
            Map<String, Object> s = suggestion("backup", "medium",
                    never + " 台设备从未备份：执行将为优先设备真实备份 running 配置",
                    "backup", true);
            if (firstNever != null) {
                s.put("deviceId", firstNever.getId());
                s.put("deviceName", firstNever.getName());
            }
            list.add(s);
        }
        long risk = deviceScores.stream().filter(m -> toDouble(m.get("score")) < 60).count();
        if (risk > 0) {
            list.add(suggestion("risk_devices", "high",
                    risk + " 台设备健康分低于 60：执行将重算关联/根因并刷新离线设备",
                    "inspect_and_refresh", true));
        }
        long dangerPerf = openAlarms.stream()
                .filter(a -> a.getStatus() == Alarm.Status.ACTIVE)
                .filter(a -> "PERFORMANCE".equalsIgnoreCase(a.getTrapType())
                        || "BASELINE_ANOMALY".equalsIgnoreCase(a.getTrapType()))
                .count();
        if (dangerPerf > 0) {
            list.add(suggestion("perf_alarms", "high",
                    dangerPerf + " 条性能/基线 ACTIVE 告警：执行将重跑关联巡检",
                    "inspect", true));
        }
        if (list.isEmpty()) {
            list.add(suggestion("ok", "low", "当前无需处置动作，可继续巡检", "noop", false));
        }
        return list;
    }

    /**
     * 根据历史反馈重排建议：无用票多的降权靠后，有用票多的上浮。
     */
    private void rankSuggestionsByFeedback(List<Map<String, Object>> suggestions) {
        Map<String, long[]> stats = new HashMap<>();
        try {
            for (Object[] row : feedbackRepository.aggregateByTargetType("suggestion")) {
                if (row == null || row.length < 3 || row[0] == null) {
                    continue;
                }
                String code = String.valueOf(row[0]);
                long useful = row[1] instanceof Number n ? n.longValue() : 0L;
                long useless = row[2] instanceof Number n ? n.longValue() : 0L;
                stats.put(code, new long[]{useful, useless});
            }
        } catch (Exception ignored) {
            return;
        }
        if (stats.isEmpty()) {
            return;
        }
        for (Map<String, Object> s : suggestions) {
            String code = String.valueOf(s.get("code"));
            long[] st = stats.getOrDefault(code, new long[]{0, 0});
            // 净有用分：有用 - 1.5×无用
            double feedbackScore = st[0] - 1.5 * st[1];
            s.put("feedbackScore", feedbackScore);
            s.put("feedbackUseful", st[0]);
            s.put("feedbackUseless", st[1]);
            if (st[1] >= 3 && st[1] > st[0]) {
                s.put("priority", "low");
                s.put("text", s.get("text") + "（历史反馈偏少认同，已降权）");
            }
        }
        suggestions.sort((a, b) -> {
            int pa = priorityWeight(String.valueOf(a.get("priority")));
            int pb = priorityWeight(String.valueOf(b.get("priority")));
            int p = Integer.compare(pb, pa);
            if (p != 0) {
                return p;
            }
            double fa = a.get("feedbackScore") instanceof Number n ? n.doubleValue() : 0;
            double fb = b.get("feedbackScore") instanceof Number n ? n.doubleValue() : 0;
            return Double.compare(fb, fa);
        });
    }

    private int priorityWeight(String p) {
        return switch (p) {
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }

    private Map<String, Object> suggestion(String code, String priority, String text,
                                           String action, boolean executable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("priority", priority);
        m.put("text", text);
        m.put("action", action);
        m.put("executable", executable);
        m.put("real", executable);
        return m;
    }

    private String scoreLevel(double score) {
        if (score >= 85) return "good";
        if (score >= 70) return "fair";
        if (score >= 50) return "poor";
        return "critical";
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return 0;
    }
}
