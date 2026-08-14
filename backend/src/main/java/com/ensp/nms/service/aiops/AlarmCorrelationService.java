package com.ensp.nms.service.aiops;

import com.ensp.nms.config.AiopsPolicyProperties;
import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.TopologyLink;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.TopologyLinkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 告警风暴收敛、链路两端关联、拓扑连带降权。
 */
@Slf4j
@Service
public class AlarmCorrelationService {

    private final AlarmRepository alarmRepository;
    private final DeviceRepository deviceRepository;
    private final TopologyLinkRepository topologyLinkRepository;
    private final AiopsPolicyProperties policyProperties;
    private final AiopsPostCorrelateHook postCorrelateHook;

    /** 最近一次关联结果快照，供 overview 等只读接口使用，避免 GET 触发全量写库 */
    private final AtomicReference<Map<String, Object>> lastSnapshot = new AtomicReference<>(emptySnapshot());

    public AlarmCorrelationService(
            AlarmRepository alarmRepository,
            DeviceRepository deviceRepository,
            TopologyLinkRepository topologyLinkRepository,
            AiopsPolicyProperties policyProperties,
            @Lazy AiopsPostCorrelateHook postCorrelateHook) {
        this.alarmRepository = alarmRepository;
        this.deviceRepository = deviceRepository;
        this.topologyLinkRepository = topologyLinkRepository;
        this.policyProperties = policyProperties;
        this.postCorrelateHook = postCorrelateHook;
    }

    @Scheduled(fixedRate = 30000, initialDelay = 20000)
    @Transactional
    public void scheduledCorrelate() {
        try {
            correlateOpenAlarms();
        } catch (Exception e) {
            log.warn("告警关联任务失败: {}", e.getMessage());
        }
    }

    /** 只读：返回定时任务缓存的关联统计，不写库 */
    public Map<String, Object> getCorrelationSnapshot() {
        return new LinkedHashMap<>(lastSnapshot.get());
    }

    @Transactional
    public Map<String, Object> correlateOpenAlarms() {
        List<Alarm> open = alarmRepository.findByStatusInOrderByOccurredAtDesc(
                List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED));

        // 重置关联字段后重算
        for (Alarm a : open) {
            a.setParentAlarmId(null);
            a.setCorrelationType(null);
            a.setSecondaryAlarm(false);
            a.setCorrelationNote(null);
        }

        int stormGroups = applyStormGrouping(open);
        int linkPairs = applyLinkCorrelation(open);
        int secondary = applyTopologySecondary(open);

        alarmRepository.saveAll(open);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openAlarms", open.size());
        result.put("stormGroups", stormGroups);
        result.put("linkPairs", linkPairs);
        result.put("secondaryMarked", secondary);
        result.put("representativeCount", open.stream().filter(a -> a.getParentAlarmId() == null).count());
        result.put("suppressedCount", open.stream().filter(a -> a.getParentAlarmId() != null).count());
        result.put("correlatedAt", LocalDateTime.now().toString());
        lastSnapshot.set(freeze(result));
        log.info("告警关联完成: open={}, storm={}, link={}, secondary={}",
                open.size(), stormGroups, linkPairs, secondary);
        try {
            postCorrelateHook.afterCorrelate(result);
        } catch (Exception e) {
            log.debug("关联后钩子调度失败: {}", e.getMessage());
        }
        return result;
    }

    private static Map<String, Object> emptySnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("openAlarms", 0);
        m.put("stormGroups", 0);
        m.put("linkPairs", 0);
        m.put("secondaryMarked", 0);
        m.put("representativeCount", 0);
        m.put("suppressedCount", 0);
        return freeze(m);
    }

    private static Map<String, Object> freeze(Map<String, Object> src) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(src));
    }

    /** 同设备 + 同标题 + 10 分钟窗口 → 最早一条为代表，其余挂 parent */
    private int applyStormGrouping(List<Alarm> open) {
        Map<String, List<Alarm>> groups = new HashMap<>();
        for (Alarm a : open) {
            String deviceKey = a.getDeviceId() != null ? "d:" + a.getDeviceId() : "ip:" + nullToEmpty(a.getDeviceIp());
            LocalDateTime t = a.getOccurredAt() != null ? a.getOccurredAt() : LocalDateTime.now();
            long epochMinute = t.atZone(ZoneId.systemDefault()).toEpochSecond() / 60;
            int stormWindow = Math.max(1, policyProperties.getStormWindowMinutes());
            long bucket = epochMinute / stormWindow;
            String key = deviceKey + "|" + nullToEmpty(a.getTitle()).toLowerCase(Locale.ROOT) + "|" + bucket;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
        }
        int groupsWithChildren = 0;
        for (List<Alarm> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator.comparing(Alarm::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder())));
            Alarm parent = group.get(0);
            parent.setCorrelationType("STORM");
            parent.setCorrelationNote("风暴收敛代表告警，共 " + group.size() + " 条同类事件");
            if (parent.getId() != null) {
                for (int i = 1; i < group.size(); i++) {
                    Alarm child = group.get(i);
                    child.setParentAlarmId(parent.getId());
                    child.setCorrelationType("STORM");
                    child.setCorrelationNote("已收敛至告警 #" + parent.getId());
                }
            }
            groupsWithChildren++;
        }
        return groupsWithChildren;
    }

    /** 拓扑直连两端在时间窗内均有离线/链路类告警 → 标记 LINK */
    private int applyLinkCorrelation(List<Alarm> open) {
        List<TopologyLink> links = topologyLinkRepository.findAll();
        Map<Long, List<Alarm>> byDevice = open.stream()
                .filter(a -> a.getDeviceId() != null)
                .collect(Collectors.groupingBy(Alarm::getDeviceId));

        int pairs = 0;
        Set<String> seen = new HashSet<>();
        for (TopologyLink link : links) {
            Long aId = link.getSourceNodeId();
            Long bId = link.getTargetNodeId();
            if (aId == null || bId == null) {
                continue;
            }
            List<Alarm> aAlarms = byDevice.getOrDefault(aId, List.of()).stream()
                    .filter(this::isLinkOrDownAlarm).toList();
            List<Alarm> bAlarms = byDevice.getOrDefault(bId, List.of()).stream()
                    .filter(this::isLinkOrDownAlarm).toList();
            if (aAlarms.isEmpty() || bAlarms.isEmpty()) {
                continue;
            }
            for (Alarm aa : aAlarms) {
                for (Alarm bb : bAlarms) {
                    if (!withinMinutes(aa.getOccurredAt(), bb.getOccurredAt(),
                            Math.max(1, policyProperties.getLinkWindowMinutes()))) {
                        continue;
                    }
                    if (aa.getId() == null || bb.getId() == null) {
                        continue;
                    }
                    String pairKey = Math.min(aa.getId(), bb.getId()) + "-" + Math.max(aa.getId(), bb.getId());
                    if (!seen.add(pairKey)) {
                        continue;
                    }
                    Alarm primary = !bb.getOccurredAt().isBefore(aa.getOccurredAt()) ? aa : bb;
                    Alarm other = primary == aa ? bb : aa;
                    // 已被风暴收敛的子告警不再改挂父级，避免环/覆盖
                    if (other.getParentAlarmId() != null || primary.getId() == null) {
                        continue;
                    }
                    if (primary.getParentAlarmId() == null) {
                        if (primary.getCorrelationType() == null) {
                            primary.setCorrelationType("LINK");
                        }
                        String note = "链路两端关联：设备 " + aId + " ↔ " + bId;
                        primary.setCorrelationNote(mergeNote(primary.getCorrelationNote(), note));
                    }
                    if (!"STORM".equals(other.getCorrelationType())
                            && !Objects.equals(other.getId(), primary.getId())) {
                        other.setParentAlarmId(primary.getId());
                        other.setCorrelationType("LINK");
                        other.setCorrelationNote("链路关联至告警 #" + primary.getId());
                    }
                    pairs++;
                }
            }
        }
        return pairs;
    }

    /** 上游离线时，将邻居侧的离线/不可达告警标为连带 */
    private int applyTopologySecondary(List<Alarm> open) {
        List<Device> devices = deviceRepository.findAll();
        Set<Long> offlineIds = devices.stream()
                .filter(d -> d.getId() != null && !"online".equalsIgnoreCase(d.getStatus()))
                .map(Device::getId)
                .collect(Collectors.toSet());
        if (offlineIds.isEmpty()) {
            return 0;
        }

        Map<Long, Set<Long>> adj = buildAdjacency();
        Map<Long, String> nameById = devices.stream()
                .filter(d -> d.getId() != null)
                .collect(Collectors.toMap(Device::getId, d -> d.getName() != null ? d.getName() : ("#" + d.getId()), (a, b) -> a));

        int marked = 0;
        for (Alarm alarm : open) {
            Long deviceId = alarm.getDeviceId();
            if (deviceId == null || !isLinkOrDownAlarm(alarm)) {
                continue;
            }
            Set<Long> neighbors = adj.getOrDefault(deviceId, Set.of());
            Long upstream = pickBestUpstream(neighbors, offlineIds, adj);
            if (upstream == null) {
                continue;
            }
            int upDegree = adj.getOrDefault(upstream, Set.of()).size();
            int selfDegree = neighbors.size();
            int upOfflineNbr = (int) adj.getOrDefault(upstream, Set.of()).stream().filter(offlineIds::contains).count();
            int selfOfflineNbr = (int) neighbors.stream().filter(offlineIds::contains).count();
            // 自身也离线：仅当邻居更像枢纽（度数更高或离线邻居更多）时标连带
            if (offlineIds.contains(deviceId) && !Objects.equals(deviceId, upstream)) {
                boolean hubber = upDegree > selfDegree
                        || (upDegree == selfDegree && upOfflineNbr >= selfOfflineNbr);
                if (hubber) {
                    alarm.setSecondaryAlarm(true);
                    alarm.setCorrelationType(alarm.getCorrelationType() == null ? "SECONDARY" : alarm.getCorrelationType());
                    alarm.setCorrelationNote(mergeNote(alarm.getCorrelationNote(),
                            "可能是连带：上游枢纽离线「" + nameById.getOrDefault(upstream, String.valueOf(upstream))
                                    + "」（度=" + upDegree + "）"));
                    marked++;
                }
            } else if (!offlineIds.contains(deviceId)) {
                // 设备在线但有不可达类告警，邻居离线
                alarm.setSecondaryAlarm(true);
                alarm.setCorrelationType(alarm.getCorrelationType() == null ? "SECONDARY" : alarm.getCorrelationType());
                alarm.setCorrelationNote(mergeNote(alarm.getCorrelationNote(),
                        "可能是连带：邻居设备离线「" + nameById.getOrDefault(upstream, String.valueOf(upstream)) + "」"));
                marked++;
            }
        }
        return marked;
    }

    /**
     * 在离线邻居中优先选拓扑度数高、离线邻居多的枢纽作为上游根。
     */
    private Long pickBestUpstream(Set<Long> neighbors, Set<Long> offlineIds, Map<Long, Set<Long>> adj) {
        return neighbors.stream()
                .filter(offlineIds::contains)
                .max(Comparator
                        .comparingInt((Long id) -> adj.getOrDefault(id, Set.of()).size())
                        .thenComparingInt(id -> (int) adj.getOrDefault(id, Set.of()).stream()
                                .filter(offlineIds::contains).count())
                        .thenComparingLong(id -> -id))
                .orElse(null);
    }

    private Map<Long, Set<Long>> buildAdjacency() {
        Map<Long, Set<Long>> adj = new HashMap<>();
        for (TopologyLink link : topologyLinkRepository.findAll()) {
            Long s = link.getSourceNodeId();
            Long t = link.getTargetNodeId();
            if (s == null || t == null) {
                continue;
            }
            adj.computeIfAbsent(s, k -> new HashSet<>()).add(t);
            adj.computeIfAbsent(t, k -> new HashSet<>()).add(s);
        }
        return adj;
    }

    private boolean isLinkOrDownAlarm(Alarm a) {
        return AlarmKeywordMatcher.isLinkOrDown(a);
    }

    private boolean withinMinutes(LocalDateTime a, LocalDateTime b, int minutes) {
        if (a == null || b == null) {
            return false;
        }
        return Math.abs(ChronoUnit.MINUTES.between(a, b)) <= minutes;
    }

    private String mergeNote(String existing, String add) {
        if (existing == null || existing.isBlank()) {
            return add;
        }
        if (existing.contains(add)) {
            return existing;
        }
        return existing + "；" + add;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 构建收敛后的事件列表（仅代表告警；含近期已关闭便于筛选） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listIncidents() {
        List<Alarm> open = alarmRepository.findByStatusInOrderByOccurredAtDesc(
                List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED));
        LocalDateTime clearedSince = LocalDateTime.now().minusDays(7);
        List<Alarm> recentlyCleared = alarmRepository.findRecentClearedRoots(
                Alarm.Status.CLEARED,
                clearedSince,
                org.springframework.data.domain.PageRequest.of(0, 50));
        List<Alarm> all = new ArrayList<>(open);
        all.addAll(recentlyCleared);

        Map<Long, Long> childCount = new HashMap<>();
        for (Alarm a : open) {
            if (a.getParentAlarmId() != null) {
                childCount.merge(a.getParentAlarmId(), 1L, Long::sum);
            }
        }
        List<Map<String, Object>> incidents = new ArrayList<>();
        for (Alarm a : all) {
            if (a.getParentAlarmId() != null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("title", a.getTitle());
            row.put("severity", a.getSeverity() != null ? a.getSeverity().name() : null);
            row.put("status", a.getStatus() != null ? a.getStatus().name() : null);
            row.put("deviceId", a.getDeviceId());
            row.put("deviceName", a.getDeviceName());
            row.put("deviceIp", a.getDeviceIp());
            row.put("occurredAt", a.getOccurredAt());
            row.put("repeatCount", a.getRepeatCount());
            row.put("childCount", childCount.getOrDefault(a.getId(), 0L));
            row.put("correlationType", a.getCorrelationType());
            row.put("secondary", a.isSecondaryAlarm());
            row.put("correlationNote", a.getCorrelationNote());
            row.put("trapType", a.getTrapType());
            // phase: pending=待处理, in_progress=处理中, closed=已关闭
            String phase = phaseOf(a.getStatus());
            boolean closed = a.getStatus() == Alarm.Status.CLEARED;
            row.put("phase", phase);
            row.put("closed", closed);
            // handled 仅表示已关闭（兼容旧字段；ACK≠办结）
            row.put("handled", closed);
            row.put("inProgress", a.getStatus() == Alarm.Status.ACKNOWLEDGED);
            // 排序：待处理 → 处理中 → 已关闭；连带靠后
            int priority = closed ? 3
                    : (a.getStatus() == Alarm.Status.ACKNOWLEDGED ? 2
                    : (a.isSecondaryAlarm() ? 1 : 0));
            row.put("displayPriority", priority);
            incidents.add(row);
        }
        incidents.sort((x, y) -> {
            int px = x.get("displayPriority") instanceof Number n ? n.intValue() : 0;
            int py = y.get("displayPriority") instanceof Number n ? n.intValue() : 0;
            int p = Integer.compare(px, py);
            if (p != 0) {
                return p;
            }
            LocalDateTime tx = x.get("occurredAt") instanceof LocalDateTime t ? t : null;
            LocalDateTime ty = y.get("occurredAt") instanceof LocalDateTime t ? t : null;
            if (tx == null && ty == null) return 0;
            if (tx == null) return 1;
            if (ty == null) return -1;
            return ty.compareTo(tx);
        });
        return incidents;
    }

    private static String phaseOf(Alarm.Status status) {
        if (status == Alarm.Status.CLEARED) {
            return "closed";
        }
        if (status == Alarm.Status.ACKNOWLEDGED) {
            return "in_progress";
        }
        return "pending";
    }
}
