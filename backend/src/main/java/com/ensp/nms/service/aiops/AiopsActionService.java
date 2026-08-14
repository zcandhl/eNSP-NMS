package com.ensp.nms.service.aiops;

import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.DeviceConfig;
import com.ensp.nms.entity.TopologyLink;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceConfigRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.TopologyLinkRepository;
import com.ensp.nms.security.SecurityUtils;
import com.ensp.nms.service.AlarmService;
import com.ensp.nms.service.DeviceConfigService;
import com.ensp.nms.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AIOps 半闭环动作：备份/回滚需确认；连带/收敛噪音确认不改配。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiopsActionService {

    private final DeviceRepository deviceRepository;
    private final DeviceConfigRepository deviceConfigRepository;
    private final DeviceConfigService deviceConfigService;
    private final DeviceService deviceService;
    private final AlarmRepository alarmRepository;
    private final AlarmService alarmService;
    private final TopologyLinkRepository topologyLinkRepository;

    /**
     * 批量刷新离线设备连通性（真实 Ping/状态写回）。
     */
    @Transactional
    public Map<String, Object> refreshOfflineDevices(boolean confirmed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "refresh_offline");
        result.put("autoChange", false);
        if (!confirmed) {
            result.put("ok", false);
            result.put("error", "需要确认（confirmed=true）后才会批量刷新");
            return result;
        }
        List<Device> offline = deviceRepository.findAll().stream()
                .filter(d -> d.getId() != null && !"online".equalsIgnoreCase(d.getStatus()))
                .toList();
        int ok = 0;
        int fail = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (Device d : offline) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deviceId", d.getId());
            row.put("name", d.getName());
            try {
                Device refreshed = deviceService.refreshDeviceStatus(d.getId());
                Map<String, Object> snap = deviceService.getProbeSnapshot(d.getId());
                row.put("ok", true);
                row.put("status", refreshed.getStatus());
                row.put("probeMethod", refreshed.getLastProbeMethod());
                row.put("consecutiveSuccess", snap.get("consecutiveSuccess"));
                row.put("onlineAfterSuccesses", snap.get("onlineAfterSuccesses"));
                row.put("recoveryConfirmed", snap.get("recoveryConfirmed"));
                row.put("suspectedRecovery", snap.get("suspectedRecovery"));
                ok++;
            } catch (Exception e) {
                row.put("ok", false);
                row.put("error", e.getMessage());
                fail++;
            }
            details.add(row);
        }
        result.put("ok", true);
        result.put("total", offline.size());
        result.put("success", ok);
        result.put("failed", fail);
        result.put("details", details);
        result.put("message", String.format("已刷新离线设备 %d 台（成功 %d / 失败 %d）", offline.size(), ok, fail));
        log.info("AIOps 批量刷新离线设备 total={} ok={} fail={}", offline.size(), ok, fail);
        return result;
    }

    /**
     * 批量确认噪音告警（不改配）。
     */
    @Transactional
    public Map<String, Object> ackSecondaryWithConfirm(Long deviceId, boolean confirmed, String operatorOverride) {
        return ackSecondaryWithConfirm(deviceId, null, confirmed, operatorOverride);
    }

    @Transactional
    public Map<String, Object> ackSecondaryWithConfirm(Long deviceId, Long alarmId,
                                                       boolean confirmed, String operatorOverride) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "ack_secondary");
        result.put("autoChange", false);
        if (!confirmed) {
            result.put("ok", false);
            result.put("error", "需要确认（confirmed=true）后才会批量确认连带/收敛告警");
            return result;
        }
        String by = operatorOverride != null && !operatorOverride.isBlank()
                ? operatorOverride.trim()
                : safeOperator();

        List<Alarm> open = alarmRepository.findByStatusInOrderByOccurredAtDesc(
                List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED));

        Alarm root = alarmId != null
                ? open.stream().filter(a -> Objects.equals(a.getId(), alarmId)).findFirst()
                .orElseGet(() -> alarmRepository.findById(alarmId).orElse(null))
                : null;
        Long rootDeviceId = root != null && root.getDeviceId() != null ? root.getDeviceId() : deviceId;
        Set<Long> impactDeviceIds = rootDeviceId != null ? impactDevices(rootDeviceId) : Set.of();

        int secondaryAcked = 0;
        int childAcked = 0;
        int alreadyAcked = 0;
        int eligible = 0;
        int ackClosed = 0;

        for (Alarm a : open) {
            if (!isAckCandidate(a, alarmId, rootDeviceId, impactDeviceIds, deviceId)) {
                continue;
            }
            eligible++;
            if (a.getStatus() != Alarm.Status.ACTIVE) {
                // 已进入处理中的阅知类：一并办结，避免长期占「处理中」
                if (a.getStatus() == Alarm.Status.ACKNOWLEDGED && alarmService.isAckClosesAlarm(a)) {
                    alarmService.acknowledgeAlarm(a.getId(), by, "AIOps 阅知关闭残留");
                    ackClosed++;
                    if (a.getParentAlarmId() != null && !a.isSecondaryAlarm()) {
                        childAcked++;
                    } else {
                        secondaryAcked++;
                    }
                } else {
                    alreadyAcked++;
                }
                continue;
            }
            boolean willClose = alarmService.isAckClosesAlarm(a);
            String note = a.isSecondaryAlarm() || "SECONDARY".equalsIgnoreCase(nullToEmpty(a.getCorrelationType()))
                    ? "AIOps 确认连带告警"
                    : "AIOps 确认收敛子告警";
            alarmService.acknowledgeAlarm(a.getId(), by, note);
            if (willClose) {
                ackClosed++;
            }
            if (a.getParentAlarmId() != null && !a.isSecondaryAlarm()) {
                childAcked++;
            } else {
                secondaryAcked++;
            }
        }

        int n = secondaryAcked + childAcked;
        result.put("ok", true);
        result.put("count", n);
        result.put("secondaryAcked", secondaryAcked);
        result.put("childAcked", childAcked);
        result.put("ackClosed", ackClosed);
        result.put("alreadyAcked", alreadyAcked);
        result.put("eligible", eligible);
        result.put("alarmId", alarmId);
        result.put("deviceId", rootDeviceId != null ? rootDeviceId : deviceId);
        result.put("operator", by);
        if (n > 0) {
            if (ackClosed > 0 && ackClosed == n) {
                result.put("message", String.format("已阅知关闭 %d 条关联告警（连带 %d / 收敛子告警 %d）",
                        n, secondaryAcked, childAcked));
            } else if (ackClosed > 0) {
                result.put("message", String.format(
                        "已处理 %d 条（阅知关闭 %d，其余进入处理中；连带 %d / 收敛子告警 %d）",
                        n, ackClosed, secondaryAcked, childAcked));
            } else {
                result.put("message", String.format("已确认 %d 条（连带 %d / 收敛子告警 %d）",
                        n, secondaryAcked, childAcked));
            }
        } else if (eligible > 0) {
            result.put("message", "没有待确认的 ACTIVE 噪音告警（" + alreadyAcked + " 条此前已确认）");
        } else {
            result.put("message", "未找到可确认的连带/收敛告警（请先执行智能巡检完成关联）");
        }
        log.info("AIOps 确认噪音 count={} ackClosed={} secondary={} child={} already={} alarmId={} deviceId={} by={}",
                n, ackClosed, secondaryAcked, childAcked, alreadyAcked, alarmId, deviceId, by);
        return result;
    }

    /**
     * 是否纳入本次确认范围。
     */
    private boolean isAckCandidate(Alarm a, Long alarmId, Long rootDeviceId,
                                   Set<Long> impactDeviceIds, Long deviceIdOnly) {
        if (a == null || a.getId() == null) {
            return false;
        }
        // 不确认根因代表告警本身（除非它自己也是连带）
        if (alarmId != null && Objects.equals(a.getId(), alarmId) && !a.isSecondaryAlarm()) {
            return false;
        }

        boolean isSecondary = a.isSecondaryAlarm();
        boolean isChild = a.getParentAlarmId() != null;

        if (alarmId != null) {
            // 本事件：直接子告警
            if (isChild && Objects.equals(a.getParentAlarmId(), alarmId)) {
                return true;
            }
            // 本事件影响域内的拓扑连带
            if (isSecondary && a.getDeviceId() != null && impactDeviceIds.contains(a.getDeviceId())) {
                return true;
            }
            return false;
        }

        if (deviceIdOnly != null) {
            Set<Long> scope = impactDevices(deviceIdOnly);
            if (isSecondary && a.getDeviceId() != null && scope.contains(a.getDeviceId())) {
                return true;
            }
            // 该设备上代表告警的子告警
            if (isChild && a.getDeviceId() != null && Objects.equals(a.getDeviceId(), deviceIdOnly)) {
                return true;
            }
            return false;
        }

        // 全网：所有连带 + 所有收敛子告警
        return isSecondary || isChild;
    }

    /** 设备自身 + 一跳邻居 */
    private Set<Long> impactDevices(Long deviceId) {
        Set<Long> ids = new HashSet<>();
        if (deviceId == null) {
            return ids;
        }
        ids.add(deviceId);
        Map<Long, Set<Long>> adj = buildAdjacency();
        ids.addAll(adj.getOrDefault(deviceId, Set.of()));
        return ids;
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 收敛事件标准处置（真实落库，不改配）：
     * 1) 刷新设备 2) 确认关联噪音 3) 恢复则清除(CLEARED)，未恢复则确认进入处理中(ACK)。
     */
    @Transactional
    public Map<String, Object> disposeIncidentWithConfirm(Long alarmId, Long deviceId, boolean confirmed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "dispose_incident");
        result.put("autoChange", false);
        List<Map<String, Object>> steps = new ArrayList<>();
        result.put("steps", steps);
        if (!confirmed) {
            result.put("ok", false);
            result.put("error", "需要确认（confirmed=true）后才会执行标准处置");
            return result;
        }
        if (alarmId == null && deviceId == null) {
            result.put("ok", false);
            result.put("error", "缺少 alarmId 或 deviceId");
            return result;
        }

        Alarm root = alarmId != null ? alarmRepository.findById(alarmId).orElse(null) : null;
        Long targetDeviceId = deviceId;
        if (targetDeviceId == null && root != null) {
            targetDeviceId = root.getDeviceId();
        }

        // Step 1: refresh device（恢复在线时会自动清除连通性告警）
        Map<String, Object> refreshStep = new LinkedHashMap<>();
        refreshStep.put("code", "refresh_device");
        refreshStep.put("title", "刷新设备连通性");
        try {
            if (targetDeviceId == null) {
                refreshStep.put("ok", false);
                refreshStep.put("message", "无设备可刷新，已跳过");
            } else {
                Device d = deviceService.refreshDeviceStatus(targetDeviceId);
                Map<String, Object> snap = deviceService.getProbeSnapshot(targetDeviceId);
                refreshStep.put("ok", true);
                refreshStep.put("deviceId", targetDeviceId);
                refreshStep.put("deviceName", d.getName());
                refreshStep.put("status", d.getStatus());
                refreshStep.put("probeMethod", d.getLastProbeMethod());
                refreshStep.put("consecutiveSuccess", snap.get("consecutiveSuccess"));
                refreshStep.put("consecutiveFail", snap.get("consecutiveFail"));
                refreshStep.put("onlineAfterSuccesses", snap.get("onlineAfterSuccesses"));
                refreshStep.put("recoveryConfirmed", snap.get("recoveryConfirmed"));
                refreshStep.put("suspectedRecovery", snap.get("suspectedRecovery"));
                String probe = d.getLastProbeMethod() != null ? d.getLastProbeMethod() : "-";
                Object okN = snap.getOrDefault("consecutiveSuccess", 0);
                Object needN = snap.getOrDefault("onlineAfterSuccesses", 2);
                refreshStep.put("message", String.format(
                        "设备「%s」状态 → %s（探测=%s，连续可达 %s/%s）",
                        d.getName(), d.getStatus(), probe, okN, needN));
            }
        } catch (Exception e) {
            refreshStep.put("ok", false);
            refreshStep.put("message", "刷新失败: " + (e.getMessage() != null ? e.getMessage() : "unknown"));
        }
        steps.add(refreshStep);

        // Step 2: ack noise（仅 ACTIVE 关联告警）
        Map<String, Object> ackNoise = ackSecondaryWithConfirm(targetDeviceId, alarmId, true, null);
        Map<String, Object> noiseStep = new LinkedHashMap<>();
        noiseStep.put("code", "ack_noise");
        noiseStep.put("title", "处理关联告警");
        noiseStep.put("ok", Boolean.TRUE.equals(ackNoise.get("ok")));
        noiseStep.put("count", ackNoise.getOrDefault("count", 0));
        noiseStep.put("secondaryAcked", ackNoise.getOrDefault("secondaryAcked", 0));
        noiseStep.put("childAcked", ackNoise.getOrDefault("childAcked", 0));
        noiseStep.put("message", ackNoise.getOrDefault("message", ""));
        steps.add(noiseStep);

        // Step 3: 根告警 — 恢复则 CLEARED，未恢复则 ACK=处理中
        Map<String, Object> rootStep = new LinkedHashMap<>();
        rootStep.put("code", "resolve_root");
        rootStep.put("title", "闭环本事件代表告警");
        Alarm rootFresh = alarmId != null ? alarmRepository.findById(alarmId).orElse(root) : root;
        String outcome = "unchanged";
        if (rootFresh == null) {
            rootStep.put("ok", false);
            rootStep.put("message", "未找到事件告警");
        } else if (rootFresh.getStatus() == Alarm.Status.CLEARED) {
            rootStep.put("ok", true);
            rootStep.put("skipped", true);
            rootStep.put("alarmId", rootFresh.getId());
            rootStep.put("status", "CLEARED");
            rootStep.put("closeReason", rootFresh.getClearNote());
            rootStep.put("message", rootFresh.getClearNote() != null && !rootFresh.getClearNote().isBlank()
                    ? "代表告警已关闭：" + rootFresh.getClearNote()
                    : "代表告警已关闭（网管已确认故障条件消失）");
            outcome = "closed";
        } else {
            boolean recovered = isFaultRecovered(rootFresh, refreshStep);
            boolean suspected = Boolean.TRUE.equals(refreshStep.get("suspectedRecovery"));
            String by = safeOperator();
            if (recovered) {
                String closeReason = buildCloseReason(refreshStep);
                alarmService.clearAlarm(rootFresh.getId(), closeReason);
                for (Alarm child : alarmRepository.findByParentAlarmId(rootFresh.getId())) {
                    if (child.getStatus() != Alarm.Status.CLEARED) {
                        alarmService.clearAlarm(child.getId(), closeReason);
                    }
                }
                alarmRepository.flush();
                rootFresh = alarmRepository.findById(rootFresh.getId()).orElse(rootFresh);
                boolean closedOk = rootFresh.getStatus() == Alarm.Status.CLEARED;
                rootStep.put("ok", closedOk);
                rootStep.put("alarmId", rootFresh.getId());
                rootStep.put("status", rootFresh.getStatus() != null ? rootFresh.getStatus().name() : null);
                rootStep.put("recovered", true);
                rootStep.put("closeReason", closeReason);
                rootStep.put("message", closedOk
                        ? "网管确认已恢复，已关闭代表告警 #" + rootFresh.getId() + "（" + closeReason + "）"
                        : "尝试关闭失败");
                outcome = closedOk ? "closed" : "unchanged";
            } else if (rootFresh.getStatus() == Alarm.Status.ACTIVE
                    || (rootFresh.getStatus() == Alarm.Status.ACKNOWLEDGED
                    && alarmService.isAckClosesAlarm(rootFresh))) {
                boolean willAckClose = alarmService.isAckClosesAlarm(rootFresh);
                String note = suspected
                        ? "AIOps 标准处置：本次探测可达但未连续确认（疑似恢复），进入处理中"
                        : "AIOps 标准处置：故障未恢复，进入处理中";
                if (willAckClose) {
                    note = "AIOps 标准处置：阅知关闭";
                }
                alarmService.acknowledgeAlarm(rootFresh.getId(), by, note);
                alarmRepository.flush();
                rootFresh = alarmRepository.findById(rootFresh.getId()).orElse(rootFresh);
                boolean closedByAck = rootFresh.getStatus() == Alarm.Status.CLEARED;
                boolean ack = rootFresh.getStatus() == Alarm.Status.ACKNOWLEDGED || closedByAck;
                rootStep.put("ok", ack);
                rootStep.put("alarmId", rootFresh.getId());
                rootStep.put("status", rootFresh.getStatus() != null ? rootFresh.getStatus().name() : null);
                rootStep.put("recovered", closedByAck);
                rootStep.put("suspectedRecovery", suspected);
                if (closedByAck) {
                    rootStep.put("closeReason", rootFresh.getClearNote());
                    rootStep.put("message", "阅知类告警已确认并关闭 #" + rootFresh.getId());
                    outcome = "closed";
                } else {
                    rootStep.put("message", ack
                            ? (suspected
                            ? "本次可达但未达连续确认阈值，已进入「处理中」；请等待轮询或再次标准处置 #" + rootFresh.getId()
                            : "故障仍未恢复，已进入「处理中」（不等于已修好）#" + rootFresh.getId())
                            : "确认失败");
                    outcome = ack ? "in_progress" : "unchanged";
                }
            } else {
                rootStep.put("ok", true);
                rootStep.put("skipped", true);
                rootStep.put("alarmId", rootFresh.getId());
                rootStep.put("status", rootFresh.getStatus() != null ? rootFresh.getStatus().name() : null);
                rootStep.put("recovered", false);
                rootStep.put("suspectedRecovery", suspected);
                rootStep.put("message", suspected
                        ? "疑似恢复中，保持「处理中」。连续确认达标后将自动关闭，或再次标准处置"
                        : "故障仍未恢复，保持「处理中」。网管确认恢复后将自动关闭，或再次标准处置");
                outcome = "in_progress";
            }
        }
        steps.add(rootStep);

        int noiseCount = ackNoise.get("count") instanceof Number n ? n.intValue() : 0;
        boolean refreshOk = Boolean.TRUE.equals(refreshStep.get("ok"));
        boolean closed = "closed".equals(outcome);
        boolean inProgress = "in_progress".equals(outcome);
        boolean ok = closed || inProgress || noiseCount > 0 || refreshOk;
        result.put("ok", ok);
        result.put("alarmId", alarmId);
        result.put("deviceId", targetDeviceId);
        result.put("noiseAcked", noiseCount);
        result.put("outcome", outcome);
        result.put("closed", closed);
        result.put("inProgress", inProgress);
        result.put("handled", closed);
        result.put("status", rootFresh != null && rootFresh.getStatus() != null
                ? rootFresh.getStatus().name() : null);
        result.put("deviceStatus", refreshStep.get("status"));
        result.put("probeMethod", refreshStep.get("probeMethod"));
        result.put("consecutiveSuccess", refreshStep.get("consecutiveSuccess"));
        result.put("onlineAfterSuccesses", refreshStep.get("onlineAfterSuccesses"));
        result.put("recoveryConfirmed", refreshStep.get("recoveryConfirmed"));
        result.put("suspectedRecovery", refreshStep.get("suspectedRecovery"));
        result.put("closeReason", rootStep.get("closeReason"));
        String outcomeText = closed ? "已关闭（网管确认恢复）"
                : (inProgress
                ? (Boolean.TRUE.equals(refreshStep.get("suspectedRecovery"))
                ? "已进入处理中（疑似恢复，待连续确认）"
                : "已进入处理中（故障未恢复）")
                : "未变更");
        result.put("message", String.format(
                "标准处置完成：刷新%s，关联告警确认 %d 条，代表告警%s",
                refreshOk ? "成功" : "跳过/失败",
                noiseCount,
                outcomeText));
        log.info("AIOps 标准处置 alarmId={} deviceId={} noise={} outcome={} deviceStatus={} probe={}",
                alarmId, targetDeviceId, noiseCount, outcome, refreshStep.get("status"),
                refreshStep.get("probeMethod"));
        return result;
    }

    private boolean isFaultRecovered(Alarm root, Map<String, Object> refreshStep) {
        if (root == null) {
            return false;
        }
        if (root.getStatus() == Alarm.Status.CLEARED) {
            return true;
        }
        String trap = nullToEmpty(root.getTrapType());
        String title = nullToEmpty(root.getTitle());
        String blob = (title + " " + nullToEmpty(root.getDescription()) + " " + trap).toLowerCase(Locale.ROOT);
        // 仅整机离线可由探测在线判定恢复；linkDown/接口 down 不能因主机仍 ping 通就关闭
        boolean deviceOffline = "DEVICE_OFFLINE".equalsIgnoreCase(trap)
                || title.contains("设备离线")
                || blob.contains("device offline")
                || ((blob.contains("offline") || blob.contains("离线") || blob.contains("不可达")
                || blob.contains("unreachable"))
                && !blob.contains("linkdown") && !blob.contains("link down")
                && !blob.contains("接口") && !trap.toLowerCase(Locale.ROOT).contains("link"));
        if (!deviceOffline) {
            return false;
        }
        // 必须以网管连续探测确认恢复为准，单次可达不算
        if (refreshStep != null && Boolean.TRUE.equals(refreshStep.get("recoveryConfirmed"))) {
            return true;
        }
        Object status = refreshStep != null ? refreshStep.get("status") : null;
        Object ok = refreshStep != null ? refreshStep.get("consecutiveSuccess") : null;
        Object need = refreshStep != null ? refreshStep.get("onlineAfterSuccesses") : null;
        if (status != null && "online".equalsIgnoreCase(String.valueOf(status))
                && ok instanceof Number o && need instanceof Number n
                && o.intValue() >= n.intValue()) {
            return true;
        }
        return false;
    }

    private static String buildCloseReason(Map<String, Object> refreshStep) {
        if (refreshStep == null) {
            return "网管探测确认设备已恢复";
        }
        Object probe = refreshStep.get("probeMethod");
        Object ok = refreshStep.get("consecutiveSuccess");
        Object need = refreshStep.get("onlineAfterSuccesses");
        String p = probe != null ? String.valueOf(probe).toUpperCase(Locale.ROOT) : "PROBE";
        return String.format("网管探测确认恢复：%s 连续可达 %s/%s 次", p, ok != null ? ok : "?", need != null ? need : "?");
    }

    public Map<String, Object> backupWithConfirm(Long deviceId, boolean confirmed, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "backup");
        result.put("autoChange", false);
        if (!confirmed) {
            result.put("ok", false);
            result.put("error", "需要确认（confirmed=true）后才会执行备份");
            return result;
        }
        if (deviceId == null) {
            result.put("ok", false);
            result.put("error", "缺少 deviceId");
            return result;
        }
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            result.put("ok", false);
            result.put("error", "设备不存在");
            return result;
        }
        try {
            String operator = safeOperator();
            String desc = reason != null && !reason.isBlank()
                    ? "AIOps半闭环备份: " + reason
                    : "AIOps半闭环一键备份";
            DeviceConfig config = deviceConfigService.backupConfig(deviceId, "running", operator, desc, true);
            result.put("ok", true);
            result.put("deviceId", deviceId);
            result.put("deviceName", device.getName());
            result.put("configId", config.getId());
            result.put("version", config.getConfigVersion());
            result.put("message", "备份成功，版本 " + config.getConfigVersion());
            log.info("AIOps 半闭环备份成功 deviceId={} configId={} by={}", deviceId, config.getId(), operator);
        } catch (Exception e) {
            log.warn("AIOps 半闭环备份失败 deviceId={}: {}", deviceId, e.getMessage());
            result.put("ok", false);
            result.put("error", e.getMessage() != null ? e.getMessage() : "备份失败");
        }
        return result;
    }

    public Map<String, Object> restoreLatestWithConfirm(Long deviceId, boolean confirmed, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "restore");
        result.put("autoChange", false);
        if (!confirmed) {
            result.put("ok", false);
            result.put("error", "需要确认（confirmed=true）后才会执行回滚");
            return result;
        }
        if (deviceId == null) {
            result.put("ok", false);
            result.put("error", "缺少 deviceId");
            return result;
        }
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            result.put("ok", false);
            result.put("error", "设备不存在");
            return result;
        }
        List<DeviceConfig> configs = deviceConfigRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId);
        DeviceConfig target = configs.stream()
                .filter(c -> c.getId() != null)
                .max(Comparator.comparing(DeviceConfig::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        if (target == null) {
            result.put("ok", false);
            result.put("error", "该设备尚无备份可回滚，请先执行备份");
            return result;
        }
        try {
            Map<String, Object> restoreResult = deviceConfigService.restoreConfig(
                    deviceId, target.getId(), null, true, false);
            result.put("ok", true);
            result.put("deviceId", deviceId);
            result.put("deviceName", device.getName());
            result.put("configId", target.getId());
            result.put("version", target.getConfigVersion());
            result.put("reason", reason);
            result.put("restoreResult", restoreResult);
            result.put("message", "已按最新备份回滚（回滚前已自动备份）");
            log.info("AIOps 半闭环回滚成功 deviceId={} configId={} by={}",
                    deviceId, target.getId(), safeOperator());
        } catch (Exception e) {
            log.warn("AIOps 半闭环回滚失败 deviceId={}: {}", deviceId, e.getMessage());
            result.put("ok", false);
            result.put("error", e.getMessage() != null ? e.getMessage() : "回滚失败");
        }
        return result;
    }

    private String safeOperator() {
        try {
            return SecurityUtils.currentOperator();
        } catch (Exception e) {
            return "aiops";
        }
    }
}
