package com.ensp.nms.service;

import com.ensp.nms.device.DeviceType;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.PerformanceData;
import com.ensp.nms.net.IcmpClient;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.PerformanceDataRepository;
import com.ensp.nms.snmp.SnmpClient;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SNMP 性能采集：优先写入真实 OID 指标；缺失时对缺失指标做平滑回退（lab 风格）。
 */
@Slf4j
@Service
public class PerformanceMonitorService {

    private static final int COLLECT_POOL_SIZE = 4;
    private static final long COLLECT_CYCLE_TIMEOUT_SEC = 25;
    private static final int HISTORY_FETCH_CAP = 2000;
    private static final int HISTORY_SAMPLE_POINTS = 200;
    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private PerformanceDataRepository performanceDataRepository;

    @Autowired
    private SnmpClient snmpClient;

    @Autowired
    private DeviceIpAliasService deviceIpAliasService;

    @Autowired
    private DeviceCapabilityService deviceCapabilityService;

    @Autowired
    private IcmpClient icmpClient;

    @Autowired
    private ConnectivityRecoveryService connectivityRecoveryService;

    @Autowired
    private AlarmService alarmService;

    private final Map<Long, Map<Integer, Map<String, Long>>> lastPortData = new ConcurrentHashMap<>();
    private final Map<Long, LocalDateTime> lastCollectTime = new ConcurrentHashMap<>();
    /** 设备接口 ifOperStatus 上次快照，用于检测 up→down（挂在本设备上，非拓扑造告警） */
    private final ConcurrentHashMap<String, String> lastIfOperStatus = new ConcurrentHashMap<>();

    private final ExecutorService collectExecutor = Executors.newFixedThreadPool(COLLECT_POOL_SIZE, r -> {
        Thread t = new Thread(r, "snmp-collect");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdownCollectExecutor() {
        collectExecutor.shutdownNow();
    }

    @Scheduled(fixedRate = 30000)
    public void collectPerformanceData() {
        log.info("开始采集性能数据...");
        List<Device> devices = deviceRepository.findAll();
        if (devices.isEmpty()) {
            return;
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>(devices.size());
        for (Device device : devices) {
            futures.add(CompletableFuture.runAsync(() -> pollOneDevice(device), collectExecutor));
        }

        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(COLLECT_CYCLE_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("性能采集整轮超时（{}s），部分设备可能未完成", COLLECT_CYCLE_TIMEOUT_SEC);
        } catch (Exception e) {
            log.error("性能采集等待异常: {}", e.getMessage());
        }
    }

    private void pollOneDevice(Device device) {
        try {
            deviceCapabilityService.normalize(device);
            Map<String, Boolean> caps = deviceCapabilityService.resolveCapabilities(device);
            String ip = device.getIpAddress();

            // 无 SNMP 性能能力：仅做 ICMP 在线探测，不采性能
            if (!Boolean.TRUE.equals(caps.get("performance"))) {
                boolean up = Boolean.TRUE.equals(caps.get("icmp")) && icmpClient.ping(ip);
                ConnectivityRecoveryService.ProbeApplyResult r =
                        connectivityRecoveryService.applyProbeResult(device, up, "icmp");
                if (r.isStatusChanged() || r.isSuspectedRecovery() || r.isRecoveryConfirmed()) {
                    log.info("设备 {} ({}) ICMP 探测: {}", device.getName(), ip, r.getMessage());
                }
                return;
            }

            int port = device.getSnmpPort() != null ? device.getSnmpPort() : 161;
            String community = snmpCommunity(device);

            boolean reachable = snmpClient.isReachable(ip, port, community);
            String probe = "snmp";

            if (!reachable && Boolean.TRUE.equals(caps.get("icmp"))) {
                reachable = icmpClient.ping(ip);
                if (reachable) {
                    probe = "icmp";
                }
            }

            ConnectivityRecoveryService.ProbeApplyResult r =
                    connectivityRecoveryService.applyProbeResult(device, reachable, probe);
            if (r.isStatusChanged() || r.isSuspectedRecovery() || (r.isRecoveryConfirmed() && r.getAlarmsCleared() > 0)) {
                log.info("设备 {} ({}) 探测: {}", device.getName(), ip, r.getMessage());
            }

            // 仅确认在线后采集性能；疑似恢复仍离线则不写性能
            if (reachable && r.isOnline() && "snmp".equals(probe)) {
                Device fresh = deviceRepository.findById(device.getId()).orElse(device);
                collectDeviceData(fresh);
                try {
                    deviceIpAliasService.refreshDeviceAliases(fresh);
                } catch (Exception ex) {
                    log.debug("刷新设备 {} 接口 IP 别名失败: {}", device.getName(), ex.getMessage());
                }
            } else if (reachable && r.isOnline() && "icmp".equals(probe)) {
                log.debug("设备 {} 仅 ICMP 可达，跳过 SNMP 性能采集", device.getName());
            }
        } catch (Exception e) {
            log.error("检测设备 {} 状态失败: {}", device.getName(), e.getMessage());
            try {
                connectivityRecoveryService.applyProbeResult(device, false,
                        DeviceType.fromCode(device.getDeviceType()) == DeviceType.PC ? "icmp" : "snmp");
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    @Transactional
    protected void collectDeviceData(Device device) {
        String ip = device.getIpAddress();
        int port = device.getSnmpPort() != null ? device.getSnmpPort() : 161;
        String community = snmpCommunity(device);

        LocalDateTime now = LocalDateTime.now();
        PerformanceData systemData = new PerformanceData(device, now);

        boolean hasRealCpu = false;
        boolean hasRealMem = false;
        Double cpuUsage = snmpClient.getCpuUsage(ip, port, community, null);
        Map<String, Long> memData = snmpClient.getMemoryUsage(ip, port, community, null, null);

        if (cpuUsage != null) {
            systemData.setCpuUsage(cpuUsage);
            systemData.setCpuSource(PerformanceData.SOURCE_SNMP);
            hasRealCpu = true;
            log.info("设备 {} CPU: {}% (snmp)", device.getName(), String.format("%.1f", cpuUsage));
        }

        if (!memData.isEmpty()) {
            Long total = memData.get("total");
            Long free = memData.get("free");
            if (total != null && free != null && total > 0) {
                long used = total - free;
                systemData.setMemoryTotal(total);
                systemData.setMemoryUsed(used);
                systemData.setMemoryUsage((double) used / total * 100);
                systemData.setMemorySource(PerformanceData.SOURCE_SNMP);
                hasRealMem = true;
                log.info("设备 {} 内存: {}% (snmp)", device.getName(), String.format("%.1f", systemData.getMemoryUsage()));
            }
        }

        fillMissingSystemMetricsWithSimulation(device, systemData, now, hasRealCpu, hasRealMem);
        performanceDataRepository.save(systemData);

        Map<Integer, SnmpClient.PortMetrics> portMetrics = snmpClient.getPortMetrics(ip, port, community);
        LocalDateTime lastTime = lastCollectTime.get(device.getId());
        Map<Integer, Map<String, Long>> lastData = lastPortData.computeIfAbsent(device.getId(), k -> new ConcurrentHashMap<>());

        // 不论是否写流量点，都根据 ifOperStatus 边沿生成/清除本设备链路告警
        syncInterfaceOperAlarms(device, portMetrics);

        if (portMetrics.isEmpty()) {
            log.debug("设备 {} 无端口 SNMP 数据", device.getName());
        } else {
            List<PerformanceData> portRows = new ArrayList<>(portMetrics.size());
            for (Map.Entry<Integer, SnmpClient.PortMetrics> entry : portMetrics.entrySet()) {
                int portIndex = entry.getKey();
                SnmpClient.PortMetrics pm = entry.getValue();

                // 跳过完全无流量计数的端口（常见于未启用接口）
                if (pm.inOctets == null && pm.outOctets == null) {
                    continue;
                }

                PerformanceData pd = new PerformanceData(device, now);
                pd.setPortIndex(portIndex);
                pd.setPortName(pm.name);
                pd.setIfInOctets(pm.inOctets);
                pd.setIfOutOctets(pm.outOctets);
                pd.setPortOperStatus(pm.operStatus);

                if (lastTime != null) {
                    Map<String, Long> last = lastData.get(portIndex);
                    if (last != null) {
                        long seconds = java.time.Duration.between(lastTime, now).getSeconds();
                        if (seconds > 0) {
                            Long lastIn = last.get("inOctets");
                            Long lastOut = last.get("outOctets");
                            if (lastIn != null && pm.inOctets != null && pm.inOctets >= lastIn) {
                                pd.setIfInRate((pm.inOctets - lastIn) * 8.0 / seconds);
                            }
                            if (lastOut != null && pm.outOctets != null && pm.outOctets >= lastOut) {
                                pd.setIfOutRate((pm.outOctets - lastOut) * 8.0 / seconds);
                            }
                        }
                    }
                }

                portRows.add(pd);

                Map<String, Long> current = new HashMap<>();
                current.put("inOctets", pm.inOctets);
                current.put("outOctets", pm.outOctets);
                lastData.put(portIndex, current);
            }
            if (!portRows.isEmpty()) {
                performanceDataRepository.saveAll(portRows);
            }
        }

        lastCollectTime.put(device.getId(), now);
        lastPortData.put(device.getId(), lastData);
    }

    /**
     * 用本设备 SNMP ifOperStatus 边沿检测：仅当曾观测为 up 再变为 down 时告警。
     * 挂在 10.10.10.1 自身，不依赖对端 Trap，也不按拓扑“编造”邻居告警。
     */
    private void syncInterfaceOperAlarms(Device device, Map<Integer, SnmpClient.PortMetrics> portMetrics) {
        if (device == null || device.getId() == null || portMetrics == null || portMetrics.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, SnmpClient.PortMetrics> entry : portMetrics.entrySet()) {
            SnmpClient.PortMetrics pm = entry.getValue();
            if (pm == null || pm.operStatus == null || pm.operStatus.isBlank()) {
                continue;
            }
            if (isIgnoredInterfaceName(pm.name)) {
                continue;
            }
            String status = pm.operStatus.trim().toLowerCase();
            String key = device.getId() + ":" + pm.index;
            String prev = lastIfOperStatus.put(key, status);
            boolean nowDown = "down".equals(status) || "lowerlayerdown".equals(status);
            boolean nowUp = "up".equals(status);
            if (prev == null) {
                // 首轮建基线；当前已是 up 时仍清理历史残留断开告警（含处理中）
                if (nowUp) {
                    try {
                        alarmService.clearLinkDownAlarmsOnLinkUp(
                                device.getId(),
                                device.getIpAddress(),
                                pm.name,
                                String.valueOf(pm.index),
                                "SNMP 探测接口已 up，自动关闭残留链路断开告警");
                    } catch (Exception e) {
                        log.warn("首轮对账清除链路断开告警失败 device={} ifIndex={}: {}",
                                device.getName(), pm.index, e.getMessage());
                    }
                }
                continue;
            }
            boolean wasUp = "up".equals(prev);
            boolean wasDown = "down".equals(prev) || "lowerlayerdown".equals(prev);
            try {
                if (wasUp && nowDown) {
                    alarmService.raiseInterfaceDownFromSnmpPoll(device, pm.index, pm.name);
                } else if (nowUp) {
                    alarmService.clearLinkDownAlarmsOnLinkUp(
                            device.getId(),
                            device.getIpAddress(),
                            pm.name,
                            String.valueOf(pm.index),
                            wasDown
                                    ? "SNMP 轮询 ifOperStatus=up，关闭对应链路断开告警"
                                    : "SNMP 探测接口已 up，自动关闭残留链路断开告警");
                }
            } catch (Exception e) {
                log.warn("同步接口状态告警失败 device={} ifIndex={}: {}",
                        device.getName(), pm.index, e.getMessage());
            }
        }
    }

    private static boolean isIgnoredInterfaceName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.trim().toLowerCase();
        return n.contains("null") || n.contains("inloop") || n.contains("loopback")
                || n.contains("console") || n.contains("virt") || n.startsWith("vt")
                || n.contains("tunnel") || n.contains("dialer");
    }

    /** 对缺失的 CPU/内存/温度填入平滑回退值（低于告警阈值），保证历史曲线有点可画。 */
    private void fillMissingSystemMetricsWithSimulation(
            Device device, PerformanceData data, LocalDateTime now,
            boolean hasRealCpu, boolean hasRealMem) {
        long deviceId = device.getId() != null ? device.getId() : 0L;
        long epoch = now.atZone(ZoneId.systemDefault()).toEpochSecond();
        double phase = epoch / 50.0 + deviceId * 0.73;
        Random rnd = new Random(deviceId * 9973L + (epoch / 30));

        if (!hasRealCpu) {
            double cpu = 18 + (deviceId % 15) + 9 * Math.sin(phase) + 2.5 * Math.sin(phase * 2.1)
                    + (rnd.nextDouble() - 0.5) * 2;
            data.setCpuUsage(clamp(cpu, 8, 58));
            data.setCpuSource(PerformanceData.SOURCE_SIMULATED);
        }

        if (!hasRealMem) {
            double memPct = 32 + (deviceId % 12) + 7 * Math.sin(phase * 0.85 + 1.2)
                    + (rnd.nextDouble() - 0.5) * 1.5;
            memPct = clamp(memPct, 15, 65);
            long totalMb = 512L + (deviceId % 4) * 256L;
            long total = totalMb * 1024L * 1024L;
            long used = Math.round(total * memPct / 100.0);
            data.setMemoryTotal(total);
            data.setMemoryUsed(used);
            data.setMemoryUsage(memPct);
            data.setMemorySource(PerformanceData.SOURCE_SIMULATED);
        }

        if (data.getTemperature() == null) {
            double temp = 36 + (deviceId % 5) + 3.5 * Math.sin(phase * 0.6)
                    + (rnd.nextDouble() - 0.5);
            data.setTemperature(clamp(temp, 30, 48));
        }

        if (data.getFanStatus() == null) {
            data.setFanStatus("normal");
        }
        if (data.getPowerStatus() == null) {
            data.setPowerStatus("normal");
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String snmpCommunity(Device device) {
        return (device.getSnmpCommunity() != null && !device.getSnmpCommunity().isBlank())
                ? device.getSnmpCommunity() : "public";
    }

    public PerformanceData getLatestPerformance(Long deviceId) {
        List<PerformanceData> list = performanceDataRepository.findDeviceMetricsByDeviceId(deviceId);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<PerformanceData> getPerformanceHistory(Long deviceId, LocalDateTime start, LocalDateTime end) {
        List<PerformanceData> data = performanceDataRepository.findByDeviceIdAndTimestampBetween(
                deviceId, start, end, PageRequest.of(0, HISTORY_FETCH_CAP));
        if (data.size() <= HISTORY_SAMPLE_POINTS) {
            return data;
        }
        int step = Math.max(1, data.size() / HISTORY_SAMPLE_POINTS);
        List<PerformanceData> sampled = new ArrayList<>(HISTORY_SAMPLE_POINTS + 1);
        for (int i = 0; i < data.size(); i += step) {
            sampled.add(data.get(i));
        }
        PerformanceData last = data.get(data.size() - 1);
        if (sampled.isEmpty() || sampled.get(sampled.size() - 1) != last) {
            sampled.add(last);
        }
        return sampled;
    }

    public List<PerformanceData> getLatestPortMetrics(Long deviceId) {
        return performanceDataRepository.findLatestPortMetricsByDeviceId(deviceId);
    }
}
