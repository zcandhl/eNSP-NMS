package com.ensp.nms.service.aiops;

import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.ConfigChangeLog;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.PerformanceAlert;
import com.ensp.nms.entity.TopologyLink;
import com.ensp.nms.repository.AiopsFeedbackRepository;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.PerformanceAlertRepository;
import com.ensp.nms.repository.TopologyLinkRepository;
import com.ensp.nms.service.ConfigChangeLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 轻量根因候选：离线拓扑影响 + 性能严重越限 + 近期配置变更时间窗；
 * 历史「不准确」反馈会对对应设备降权。
 */
@Service
@RequiredArgsConstructor
public class RcaService {

    private static final int CONFIG_WINDOW_MINUTES = 90;
    private static final int MAX_CANDIDATES = 5;

    private final DeviceRepository deviceRepository;
    private final TopologyLinkRepository topologyLinkRepository;
    private final AlarmRepository alarmRepository;
    private final PerformanceAlertRepository performanceAlertRepository;
    private final ConfigChangeLogService configChangeLogService;
    private final AiopsFeedbackRepository feedbackRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> analyze() {
        List<Device> devices = deviceRepository.findAll();
        Map<Long, Device> byId = devices.stream()
                .filter(d -> d.getId() != null)
                .collect(Collectors.toMap(Device::getId, d -> d, (a, b) -> a));

        Set<Long> offline = devices.stream()
                .filter(d -> d.getId() != null && !"online".equalsIgnoreCase(d.getStatus()))
                .map(Device::getId)
                .collect(Collectors.toSet());

        Map<Long, Set<Long>> adj = new HashMap<>();
        for (TopologyLink link : topologyLinkRepository.findAll()) {
            Long s = link.getSourceNodeId();
            Long t = link.getTargetNodeId();
            if (s == null || t == null) continue;
            adj.computeIfAbsent(s, k -> new HashSet<>()).add(t);
            adj.computeIfAbsent(t, k -> new HashSet<>()).add(s);
        }

        List<Alarm> open = alarmRepository.findByStatusInOrderByOccurredAtDesc(
                List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED));
        Set<Long> alarmedDevices = open.stream()
                .map(Alarm::getDeviceId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        Map<Long, Map<String, Object>> byDevice = new LinkedHashMap<>();

        // 1) 离线拓扑根因：影响面大、拓扑枢纽优先（贴合「关核心 → 风暴」）
        for (Long candidateId : offline) {
            Set<Long> allNeighbors = adj.getOrDefault(candidateId, Set.of());
            Set<Long> offlineNeighbors = allNeighbors.stream()
                    .filter(offline::contains)
                    .collect(Collectors.toSet());
            Set<Long> impact = reachWithin(candidateId, offline, adj);
            int degree = allNeighbors.size();
            // 枢纽加成：度数高的上游更可能是根因
            int hubBonus = Math.min(24, degree * 3);
            int impactScore = impact.size() * 12;
            int nbrScore = offlineNeighbors.size() * 6;
            int alarmBonus = alarmedDevices.contains(candidateId) ? 8 : 0;
            int score = impactScore + nbrScore + hubBonus + alarmBonus;

            Device d = byId.get(candidateId);
            Map<String, Object> row = baseRow(candidateId, d, score, "OFFLINE");
            row.put("impactCount", impact.size());
            row.put("impactDeviceIds", new ArrayList<>(impact));
            row.put("offlineNeighborCount", offlineNeighbors.size());
            row.put("topologyDegree", degree);
            row.put("reason", impact.size() > 1
                    ? "离线枢纽，影响同故障域 " + impact.size() + " 台邻居/设备（拓扑度 " + degree + "）"
                    : "设备离线，建议优先排查本节点及上联链路（拓扑度 " + degree + "）");
            List<String> evidence = new ArrayList<>();
            evidence.add("设备状态=离线");
            evidence.add("影响下游/同域 " + impact.size() + " 台（得分 +" + impactScore + "）");
            evidence.add("离线邻居 " + offlineNeighbors.size() + " 台（得分 +" + nbrScore + "）");
            evidence.add("拓扑度数 " + degree + "（枢纽加成 +" + hubBonus + "）");
            if (alarmedDevices.contains(candidateId)) {
                evidence.add("存在活跃告警（得分 +8）");
            }
            row.put("evidence", evidence);
            byDevice.put(candidateId, row);
        }

        // 2) 性能严重越限（在线设备）
        List<PerformanceAlert> dangerPerf = performanceAlertRepository
                .findByStatusInOrderByCreatedAtDesc(List.of("active", "acknowledged"))
                .stream()
                .filter(p -> "danger".equalsIgnoreCase(p.getLevel()))
                .toList();
        for (PerformanceAlert pa : dangerPerf) {
            Long deviceId = pa.getDeviceId();
            if (deviceId == null || offline.contains(deviceId)) {
                continue;
            }
            Device d = byId.get(deviceId);
            int score = 28 + ("cpu".equalsIgnoreCase(pa.getMetric()) ? 4 : 2);
            Map<String, Object> row = byDevice.get(deviceId);
            if (row == null) {
                row = baseRow(deviceId, d, score, "PERFORMANCE");
                row.put("impactCount", 1);
                row.put("impactDeviceIds", new ArrayList<>(List.of(deviceId)));
                row.put("reason", "性能严重越限：" + (pa.getMessage() != null ? pa.getMessage() : pa.getMetric()));
                row.put("evidence", new ArrayList<>(List.of(
                        "性能告警级别=danger（得分 +" + score + "）",
                        "指标=" + pa.getMetric() + " 当前值=" + pa.getCurrentValue()
                )));
                byDevice.put(deviceId, row);
            } else {
                bumpScore(row, 12);
                row.put("category", mergeCategory(String.valueOf(row.get("category")), "PERFORMANCE"));
                row.put("reason", mergeReason(String.valueOf(row.get("reason")),
                        "同时存在性能严重越限"));
                addEvidence(row, "叠加性能严重越限（得分 +12）");
            }
        }

        // 3) 活跃告警前后配置变更时间窗
        LocalDateTime now = LocalDateTime.now();
        Set<Long> devicesWithRecentChange = new HashSet<>();
        for (Alarm alarm : open) {
            Long deviceId = alarm.getDeviceId();
            if (deviceId == null || devicesWithRecentChange.contains(deviceId)) {
                continue;
            }
            LocalDateTime around = alarm.getOccurredAt() != null ? alarm.getOccurredAt() : now;
            List<ConfigChangeLog> changes = configChangeLogService.queryLogs(
                    deviceId, null, null, null,
                    around.minusMinutes(CONFIG_WINDOW_MINUTES),
                    around.plusMinutes(10),
                    PageRequest.of(0, 3)).getContent();
            if (changes.isEmpty()) {
                continue;
            }
            devicesWithRecentChange.add(deviceId);
            Device d = byId.get(deviceId);
            ConfigChangeLog latest = changes.get(0);
            String changeHint = "告警前有配置变更（" + nullToEmpty(latest.getChangeType())
                    + "，操作人 " + nullToEmpty(latest.getOperator()) + "）";
            Map<String, Object> row = byDevice.get(deviceId);
            if (row == null) {
                row = baseRow(deviceId, d, 22, "CONFIG");
                row.put("impactCount", 1);
                row.put("impactDeviceIds", new ArrayList<>(List.of(deviceId)));
                row.put("reason", changeHint + "，建议核对变更内容");
                row.put("evidence", new ArrayList<>(List.of(
                        "告警前后 " + CONFIG_WINDOW_MINUTES + " 分钟内存在配置变更（得分 +22）",
                        "最近变更类型=" + nullToEmpty(latest.getChangeType())
                )));
                byDevice.put(deviceId, row);
            } else {
                bumpScore(row, 15);
                row.put("category", mergeCategory(String.valueOf(row.get("category")), "CONFIG"));
                row.put("reason", mergeReason(String.valueOf(row.get("reason")), changeHint));
                addEvidence(row, "叠加配置变更时间窗（得分 +15）");
            }
            row.put("recentChangeType", latest.getChangeType());
        }

        // 链路类活跃告警加分（已在候选中的设备）
        for (Alarm alarm : open) {
            Long deviceId = alarm.getDeviceId();
            if (deviceId == null || !byDevice.containsKey(deviceId)) {
                continue;
            }
            if (isLinkOrDown(alarm)) {
                bumpScore(byDevice.get(deviceId), 6);
                addEvidence(byDevice.get(deviceId), "存在链路/离线类活跃告警（得分 +6）");
            }
        }

        // 历史反馈降权：多次标记「不准确」的 RCA 候选得分下调
        Map<Long, Double> penalty = rcaFeedbackPenalty();
        for (Map.Entry<Long, Map<String, Object>> e : byDevice.entrySet()) {
            Double p = penalty.get(e.getKey());
            if (p != null && p > 0) {
                int score = ((Number) e.getValue().get("score")).intValue();
                int adjusted = Math.max(1, (int) Math.round(score - p));
                e.getValue().put("score", adjusted);
                e.getValue().put("feedbackPenalty", p);
                e.getValue().put("reason", mergeReason(String.valueOf(e.getValue().get("reason")),
                        "历史反馈偏不认同，已降权"));
                addEvidence(e.getValue(), "历史「不准确」反馈降权 -" + Math.round(p));
            }
        }

        List<Map<String, Object>> candidates = new ArrayList<>(byDevice.values());
        candidates.sort(Comparator.comparingInt(m -> -((Number) m.get("score")).intValue()));
        if (candidates.size() > MAX_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_CANDIDATES));
        }

        Set<Long> highlight = new HashSet<>();
        for (Map<String, Object> c : candidates) {
            highlight.add((Long) c.get("deviceId"));
            @SuppressWarnings("unchecked")
            List<Long> impact = (List<Long>) c.get("impactDeviceIds");
            if (impact != null) {
                highlight.addAll(impact);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidates", candidates);
        result.put("highlightDeviceIds", new ArrayList<>(highlight));
        result.put("offlineCount", offline.size());
        result.put("generatedAt", LocalDateTime.now().toString());
        return result;
    }

    private Map<Long, Double> rcaFeedbackPenalty() {
        Map<Long, Double> penalty = new HashMap<>();
        try {
            for (Object[] row : feedbackRepository.aggregateByTargetType("rca")) {
                if (row == null || row[0] == null) {
                    continue;
                }
                long useful = row[1] instanceof Number n ? n.longValue() : 0L;
                long useless = row[2] instanceof Number n ? n.longValue() : 0L;
                try {
                    Long deviceId = Long.valueOf(String.valueOf(row[0]));
                    double p = Math.max(0, useless * 8.0 - useful * 3.0);
                    if (p > 0) {
                        penalty.put(deviceId, p);
                    }
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return penalty;
    }

    private Map<String, Object> baseRow(Long deviceId, Device d, int score, String category) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deviceId", deviceId);
        row.put("name", d != null ? d.getName() : ("#" + deviceId));
        row.put("ipAddress", d != null ? d.getIpAddress() : null);
        row.put("score", score);
        row.put("category", category);
        row.put("offlineNeighborCount", 0);
        row.put("evidence", new ArrayList<String>());
        return row;
    }

    @SuppressWarnings("unchecked")
    private void addEvidence(Map<String, Object> row, String line) {
        Object raw = row.get("evidence");
        List<String> list;
        if (raw instanceof List<?> existing) {
            list = (List<String>) existing;
        } else {
            list = new ArrayList<>();
            row.put("evidence", list);
        }
        if (!list.contains(line)) {
            list.add(line);
        }
    }

    private void bumpScore(Map<String, Object> row, int delta) {
        int score = ((Number) row.get("score")).intValue() + delta;
        row.put("score", score);
    }

    private String mergeCategory(String existing, String add) {
        if (existing == null || existing.isBlank() || "null".equals(existing)) {
            return add;
        }
        if (existing.contains(add)) {
            return existing;
        }
        return existing + "+" + add;
    }

    private String mergeReason(String existing, String add) {
        if (existing == null || existing.isBlank() || "null".equals(existing)) {
            return add;
        }
        if (existing.contains(add)) {
            return existing;
        }
        return existing + "；" + add;
    }

    private boolean isLinkOrDown(Alarm a) {
        return AlarmKeywordMatcher.isLinkOrDown(a);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private Set<Long> reachWithin(Long start, Set<Long> allowed, Map<Long, Set<Long>> adj) {
        Set<Long> seen = new HashSet<>();
        List<Long> queue = new ArrayList<>();
        if (!allowed.contains(start)) {
            return seen;
        }
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            Long cur = queue.remove(0);
            for (Long n : adj.getOrDefault(cur, Set.of())) {
                if (allowed.contains(n) && seen.add(n)) {
                    queue.add(n);
                }
            }
        }
        return seen;
    }
}
