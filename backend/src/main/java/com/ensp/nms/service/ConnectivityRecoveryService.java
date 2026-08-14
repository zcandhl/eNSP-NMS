package com.ensp.nms.service;

import com.ensp.nms.config.NmsProbeProperties;
import com.ensp.nms.entity.Device;
import com.ensp.nms.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连通性探测结果落库与恢复去抖：
 * 连续成功 → online + 自动清除连通性告警；连续失败 → offline。
 * 单次探测成功仅累计「疑似恢复」，不立刻闭环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectivityRecoveryService {

    private final DeviceRepository deviceRepository;
    private final AlarmService alarmService;
    private final NmsProbeProperties probeProperties;

    private final ConcurrentHashMap<Long, Integer> successCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> failCounts = new ConcurrentHashMap<>();

    /**
     * 应用一次探测结果并写回设备状态。
     *
     * @param device   已加载实体（会被更新并 save）
     * @param reachable 本次是否可达
     * @param probeMethod snmp / icmp
     */
    @Transactional
    public ProbeApplyResult applyProbeResult(Device device, boolean reachable, String probeMethod) {
        if (device == null || device.getId() == null) {
            return ProbeApplyResult.empty();
        }
        Long id = device.getId();
        int needOk = Math.max(1, probeProperties.getOnlineAfterSuccesses());
        int needFail = Math.max(1, probeProperties.getOfflineAfterFailures());
        String previous = device.getStatus();
        String probe = probeMethod != null && !probeMethod.isBlank() ? probeMethod : "icmp";
        device.setLastProbeMethod(probe);

        ProbeApplyResult r = new ProbeApplyResult();
        r.setDeviceId(id);
        r.setDeviceName(device.getName());
        r.setProbeMethod(probe);
        r.setReachable(reachable);
        r.setPreviousStatus(previous);
        r.setOnlineAfterSuccesses(needOk);
        r.setOfflineAfterFailures(needFail);

        if (reachable) {
            int ok = successCounts.merge(id, 1, Integer::sum);
            failCounts.put(id, 0);
            device.setLastSeen(LocalDateTime.now());
            r.setConsecutiveSuccess(ok);
            r.setConsecutiveFail(0);

            if (ok >= needOk) {
                // 达标：确认在线，并清除仍未关闭的连通性告警（幂等）
                boolean justConfirmed = ok == needOk;
                successCounts.put(id, needOk);
                boolean wasOffline = !"online".equalsIgnoreCase(previous);
                device.setStatus("online");
                r.setStatus("online");
                r.setRecoveryConfirmed(true);
                r.setStatusChanged(wasOffline);
                Device saved = deviceRepository.save(device);
                int cleared = 0;
                String reason = String.format("网管探测确认恢复：%s 连续可达 %d/%d 次",
                        probe.toUpperCase(), needOk, needOk);
                // 仅「离线→在线」时清除整机离线告警；设备一直在线时绝不能清 linkDown
                if (wasOffline) {
                    try {
                        cleared = alarmService.clearConnectivityAlarmsOnRecovery(
                                saved.getId(), saved.getIpAddress(), reason);
                    } catch (Exception e) {
                        log.warn("恢复后清除连通性告警失败 deviceId={}: {}", id, e.getMessage());
                    }
                }
                r.setAlarmsCleared(cleared);
                r.setCloseReason(wasOffline ? reason : null);
                r.setMessage(wasOffline
                        ? String.format("已确认恢复在线（%s，连续 %d 次），清除整机离线告警 %d 条",
                        probe, needOk, cleared)
                        : String.format("保持在线（%s，连续确认 %d/%d）", probe, needOk, needOk));
                if (wasOffline || justConfirmed || cleared > 0) {
                    log.info("设备 {} 探测确认在线 probe={} ok={}/{} cleared={} wasOffline={}",
                            device.getName(), probe, ok, needOk, cleared, wasOffline);
                }
            } else {
                // 疑似恢复：不改 online，不关告警
                if (!"online".equalsIgnoreCase(previous)) {
                    device.setStatus("offline");
                }
                r.setStatus(device.getStatus());
                r.setRecoveryConfirmed(false);
                r.setSuspectedRecovery(true);
                deviceRepository.save(device);
                r.setMessage(String.format("本次探测可达（%s），疑似恢复 %d/%d，待连续确认后关闭告警",
                        probe, ok, needOk));
                log.info("设备 {} 疑似恢复 probe={} ok={}/{}", device.getName(), probe, ok, needOk);
            }
        } else {
            successCounts.put(id, 0);
            int fails = failCounts.merge(id, 1, Integer::sum);
            r.setConsecutiveSuccess(0);
            r.setConsecutiveFail(fails);
            r.setReachable(false);
            r.setRecoveryConfirmed(false);

            if (fails >= needFail) {
                boolean wasOnline = "online".equalsIgnoreCase(previous);
                device.setStatus("offline");
                r.setStatus("offline");
                r.setStatusChanged(wasOnline);
                deviceRepository.save(device);
                // 探测确认离线 → 确保有 DEVICE_OFFLINE（在线→离线时合并计数；稳态离线仅补缺）
                int raised = 0;
                try {
                    var alarm = alarmService.ensureDeviceOfflineAlarm(device, probe, wasOnline);
                    if (alarm.isPresent()) {
                        raised = 1;
                        r.setAlarmId(alarm.get().getId());
                    }
                } catch (Exception e) {
                    log.warn("创建设备离线告警失败 deviceId={}: {}", id, e.getMessage());
                }
                r.setAlarmsRaised(raised);
                r.setMessage(String.format("连续不可达 %d/%d，已标记离线（%s）%s",
                        fails, needFail, probe,
                        raised > 0 ? (wasOnline ? "，已生成/更新离线告警" : "，已对齐离线告警") : ""));
                if (wasOnline || raised > 0) {
                    log.warn("设备 {} 已离线 probe={} fails={}/{} alarmId={}",
                            device.getName(), probe, fails, needFail, r.getAlarmId());
                }
            } else {
                // 未达离线阈值：保持原状态
                r.setStatus(previous);
                deviceRepository.save(device);
                r.setMessage(String.format("本次不可达（%s），连续失败 %d/%d，暂不改状态",
                        probe, fails, needFail));
            }
        }
        return r;
    }

    public Map<String, Object> snapshot(Long deviceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (deviceId == null) {
            return m;
        }
        int needOk = Math.max(1, probeProperties.getOnlineAfterSuccesses());
        int needFail = Math.max(1, probeProperties.getOfflineAfterFailures());
        int ok = successCounts.getOrDefault(deviceId, 0);
        int fail = failCounts.getOrDefault(deviceId, 0);
        m.put("consecutiveSuccess", ok);
        m.put("consecutiveFail", fail);
        m.put("onlineAfterSuccesses", needOk);
        m.put("offlineAfterFailures", needFail);
        m.put("suspectedRecovery", ok > 0 && ok < needOk);
        m.put("recoveryConfirmed", ok >= needOk);
        return m;
    }

    public void resetCounters(Long deviceId) {
        if (deviceId == null) {
            return;
        }
        successCounts.remove(deviceId);
        failCounts.remove(deviceId);
    }

    @lombok.Data
    public static class ProbeApplyResult {
        private Long deviceId;
        private String deviceName;
        private boolean reachable;
        private String probeMethod;
        private String previousStatus;
        private String status;
        private int consecutiveSuccess;
        private int consecutiveFail;
        private int onlineAfterSuccesses;
        private int offlineAfterFailures;
        private boolean recoveryConfirmed;
        private boolean suspectedRecovery;
        private boolean statusChanged;
        private int alarmsCleared;
        private int alarmsRaised;
        private Long alarmId;
        private String closeReason;
        private String message;

        static ProbeApplyResult empty() {
            ProbeApplyResult r = new ProbeApplyResult();
            r.setMessage("无设备");
            return r;
        }

        public boolean isOnline() {
            return status != null && "online".equalsIgnoreCase(status);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", deviceId);
            m.put("deviceName", deviceName);
            m.put("reachable", reachable);
            m.put("probeMethod", probeMethod);
            m.put("previousStatus", previousStatus);
            m.put("status", status);
            m.put("consecutiveSuccess", consecutiveSuccess);
            m.put("consecutiveFail", consecutiveFail);
            m.put("onlineAfterSuccesses", onlineAfterSuccesses);
            m.put("offlineAfterFailures", offlineAfterFailures);
            m.put("recoveryConfirmed", recoveryConfirmed);
            m.put("suspectedRecovery", suspectedRecovery);
            m.put("statusChanged", statusChanged);
            m.put("alarmsCleared", alarmsCleared);
            m.put("alarmsRaised", alarmsRaised);
            m.put("alarmId", alarmId);
            m.put("closeReason", closeReason);
            m.put("message", message);
            return m;
        }
    }
}
