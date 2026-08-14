package com.ensp.nms.service;

import com.ensp.nms.device.DeviceType;
import com.ensp.nms.device.MonitorMode;
import com.ensp.nms.dto.DiscoverCandidate;
import com.ensp.nms.dto.DiscoverJobStatus;
import com.ensp.nms.entity.Device;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.snmp.SnmpClient;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceDiscoveryService {

    private static final int MAX_JOBS = 20;
    private static final long JOB_TTL_MS = TimeUnit.HOURS.toMillis(1);

    private final SnmpClient snmpClient;
    private final DeviceRepository deviceRepository;
    private final DeviceCapabilityService deviceCapabilityService;
    private final ExecutorService scanExecutor = Executors.newFixedThreadPool(32);
    private final ExecutorService jobExecutor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, DiscoverJobStatus> jobs = new ConcurrentHashMap<>();

    /**
     * 启动异步扫描任务，仅返回候选设备，不自动入库。
     */
    public DiscoverJobStatus startScan(String network, int timeoutSeconds, String community, int snmpPort) {
        cleanupExpiredJobs();
        if (jobs.size() >= MAX_JOBS) {
            throw new RuntimeException("发现任务过多，请稍后再试");
        }

        String baseIp = parseBaseIp(network);
        String jobId = UUID.randomUUID().toString().replace("-", "");
        DiscoverJobStatus job = new DiscoverJobStatus();
        job.setJobId(jobId);
        job.setTotal(254);
        job.setNetwork(network);
        job.setCommunity(blankToDefault(community, "public"));
        job.setSnmpPort(snmpPort > 0 ? snmpPort : 161);
        job.setMessage("扫描中…");
        jobs.put(jobId, job);

        String resolvedCommunity = job.getCommunity();
        int resolvedPort = job.getSnmpPort();
        int timeout = Math.max(5, Math.min(timeoutSeconds, 120));

        jobExecutor.submit(() -> runScan(job, baseIp, resolvedCommunity, resolvedPort, timeout));
        return snapshot(job);
    }

    public Optional<DiscoverJobStatus> getJob(String jobId) {
        DiscoverJobStatus job = jobs.get(jobId);
        return job == null ? Optional.empty() : Optional.of(snapshot(job));
    }

    /**
     * 兼容旧接口：扫描并自动导入新设备。
     */
    public List<Device> discoverDevices(String network, int timeoutSeconds) {
        return discoverDevices(network, timeoutSeconds, "public", 161);
    }

    public List<Device> discoverDevices(String network, int timeoutSeconds, String community, int snmpPort) {
        DiscoverJobStatus started = startScan(network, timeoutSeconds, community, snmpPort);
        DiscoverJobStatus finished = awaitJob(started.getJobId(), timeoutSeconds + 30);
        List<DiscoverCandidate> toImport = finished.getCandidates().stream()
                .filter(c -> !c.isAlreadyExists())
                .toList();
        return importCandidates(toImport);
    }

    @Transactional
    public List<Device> importCandidates(List<DiscoverCandidate> candidates) {
        List<Device> saved = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return saved;
        }
        for (DiscoverCandidate c : candidates) {
            if (c == null || c.getIpAddress() == null || c.getIpAddress().isBlank()) {
                continue;
            }
            String ip = c.getIpAddress().trim();
            Optional<Device> existing = deviceRepository.findByIpAddress(ip);
            if (existing.isPresent()) {
                Device device = existing.get();
                device.setLastSeen(LocalDateTime.now());
                device.setStatus("online");
                saved.add(deviceRepository.save(device));
                continue;
            }
            Device device = new Device();
            device.setIpAddress(ip);
            String name = c.getName() != null && !c.getName().isBlank() ? c.getName().trim() : ("Device-" + ip);
            device.setName(name);
            device.setModel(blankToDefault(c.getModel(), "eNSP Device"));
            device.setVendor(blankToDefault(c.getVendor(), "Huawei"));
            device.setStatus("online");
            String deviceType = blankToDefault(c.getDeviceType(), "other");
            String monitorMode = blankToDefault(c.getMonitorMode(),
                    DeviceType.PC.getCode().equals(deviceType) ? MonitorMode.ICMP.getCode() : MonitorMode.AUTO.getCode());
            device.setDeviceType(deviceType);
            device.setMonitorMode(monitorMode);
            if (DeviceType.PC.getCode().equals(deviceType) || MonitorMode.ICMP.getCode().equals(monitorMode)) {
                device.setSnmpVersion(blankToDefault(c.getSnmpVersion(), "v2c"));
                device.setSnmpCommunity(c.getSnmpCommunity());
                device.setSnmpPort(c.getSnmpPort() != null && c.getSnmpPort() > 0 ? c.getSnmpPort() : 161);
                device.setMonitorMode(MonitorMode.ICMP.getCode());
                device.setDeviceType(DeviceType.PC.getCode());
            } else {
                device.setSnmpVersion(blankToDefault(c.getSnmpVersion(), "v2c"));
                device.setSnmpCommunity(blankToDefault(c.getSnmpCommunity(), "public"));
                device.setSnmpPort(c.getSnmpPort() != null && c.getSnmpPort() > 0 ? c.getSnmpPort() : 161);
            }
            device.setSshPort(22);
            device.setLastSeen(LocalDateTime.now());
            device.setDescription(c.getDescription() != null ? c.getDescription() : "");
            deviceCapabilityService.normalize(device);
            Device savedDevice = deviceRepository.save(device);
            deviceCapabilityService.enrich(savedDevice);
            saved.add(savedDevice);
        }
        return saved;
    }

    /**
     * 从已纳管且具备拓扑发现能力的交换机/路由器 ARP 表中发现终端（虚拟 PC）候选。
     */
    public List<DiscoverCandidate> discoverEndpointsFromArp() {
        List<Device> sources = deviceRepository.findAll().stream()
                .filter(d -> "online".equals(d.getStatus()))
                .filter(d -> Boolean.TRUE.equals(
                        deviceCapabilityService.resolveCapabilities(d).get("topologyDiscover")))
                .toList();

        Map<String, DiscoverCandidate> byIp = new LinkedHashMap<>();
        for (Device source : sources) {
            try {
                String ip = source.getIpAddress();
                int snmpPort = source.getSnmpPort() != null ? source.getSnmpPort() : 161;
                String community = source.getSnmpCommunity() != null ? source.getSnmpCommunity() : "public";
                Map<String, String> arpTable = snmpClient.getArpTable(ip, snmpPort, community);
                for (Map.Entry<String, String> entry : arpTable.entrySet()) {
                    String neighborIp = entry.getKey();
                    if (neighborIp == null || neighborIp.isBlank()) {
                        continue;
                    }
                    neighborIp = neighborIp.trim();
                    if (byIp.containsKey(neighborIp)) {
                        continue;
                    }
                    // 跳过源设备自身及已纳管网元
                    if (neighborIp.equals(source.getIpAddress())) {
                        continue;
                    }
                    Optional<Device> existing = deviceRepository.findByIpAddress(neighborIp);
                    DiscoverCandidate candidate = new DiscoverCandidate();
                    candidate.setIpAddress(neighborIp);
                    candidate.setName("PC-" + neighborIp);
                    candidate.setModel("Virtual PC");
                    candidate.setVendor("eNSP");
                    candidate.setDeviceType(DeviceType.PC.getCode());
                    candidate.setMonitorMode(MonitorMode.ICMP.getCode());
                    candidate.setDiscoverSource("arp_endpoint");
                    candidate.setMacAddress(entry.getValue());
                    candidate.setLearnedFromDevice(source.getName());
                    candidate.setLearnedFromDeviceId(source.getId());
                    candidate.setDescription("从 " + source.getName() + " ARP 表发现的终端");
                    if (existing.isPresent()) {
                        candidate.setAlreadyExists(true);
                        candidate.setExistingDeviceId(existing.get().getId());
                    }
                    byIp.put(neighborIp, candidate);
                }
            } catch (Exception e) {
                log.warn("从设备 {} 读取 ARP 失败: {}", source.getName(), e.getMessage());
            }
        }
        return new ArrayList<>(byIp.values());
    }

    private void runScan(DiscoverJobStatus job, String baseIp, String community, int snmpPort, int timeoutSeconds) {
        List<Future<DiscoverCandidate>> futures = new ArrayList<>(254);
        for (int i = 1; i <= 254; i++) {
            String ip = baseIp + i;
            futures.add(scanExecutor.submit(() -> scanIp(ip, snmpPort, community)));
        }

        long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            for (Future<DiscoverCandidate> future : futures) {
                long remainingNs = deadlineNs - System.nanoTime();
                DiscoverCandidate candidate = null;
                try {
                    if (remainingNs <= 0) {
                        future.cancel(true);
                    } else {
                        candidate = future.get(remainingNs, TimeUnit.NANOSECONDS);
                    }
                } catch (TimeoutException te) {
                    future.cancel(true);
                } catch (Exception e) {
                    log.debug("扫描任务异常: {}", e.getMessage());
                } finally {
                    job.setScanned(job.getScanned() + 1);
                }
                if (candidate != null) {
                    Optional<Device> existing = deviceRepository.findByIpAddress(candidate.getIpAddress());
                    if (existing.isPresent()) {
                        candidate.setAlreadyExists(true);
                        candidate.setExistingDeviceId(existing.get().getId());
                    }
                    job.addCandidate(candidate);
                }
            }
            job.setStatus(DiscoverJobStatus.Status.COMPLETED);
            job.setMessage(String.format("扫描完成：发现 %d 台（其中已存在 %d 台）",
                    job.getFound(),
                    (int) job.getCandidates().stream().filter(DiscoverCandidate::isAlreadyExists).count()));
        } catch (Exception e) {
            log.error("设备发现失败", e);
            job.setStatus(DiscoverJobStatus.Status.FAILED);
            job.setMessage("扫描失败: " + e.getMessage());
        } finally {
            job.setFinishedAt(Instant.now());
        }
    }

    private DiscoverCandidate scanIp(String ip, int snmpPort, String community) {
        try {
            // eNSP 虚拟机常不响应 ICMP，直接 SNMP 探测
            if (!snmpClient.isReachable(ip, snmpPort, community)) {
                return null;
            }
            Map<String, String> deviceInfo = snmpClient.getDeviceInfo(ip, snmpPort, community);
            DiscoverCandidate candidate = new DiscoverCandidate();
            candidate.setIpAddress(ip);
            String sysName = deviceInfo.getOrDefault("name", "").trim();
            if (sysName.isEmpty()) {
                sysName = "Device";
            }
            String sysDescr = deviceInfo.getOrDefault("description", "");
            String sysObjectId = deviceInfo.getOrDefault("sysObjectId", "");
            String deviceType = deviceCapabilityService.detectTypeCode(sysName, sysDescr, sysObjectId, null);
            candidate.setName(sysName + "-" + ip);
            candidate.setModel("eNSP Device");
            candidate.setVendor("Huawei");
            candidate.setDeviceType(deviceType);
            candidate.setMonitorMode(MonitorMode.AUTO.getCode());
            candidate.setDiscoverSource("snmp");
            candidate.setSnmpVersion("v2c");
            candidate.setSnmpCommunity(community);
            candidate.setSnmpPort(snmpPort);
            candidate.setDescription(sysDescr);
            return candidate;
        } catch (Exception e) {
            log.debug("IP {} 不可达: {}", ip, e.getMessage());
            return null;
        }
    }

    private DiscoverJobStatus awaitJob(String jobId, int maxWaitSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(maxWaitSeconds, 10));
        while (System.currentTimeMillis() < deadline) {
            DiscoverJobStatus job = jobs.get(jobId);
            if (job == null) {
                throw new RuntimeException("发现任务不存在");
            }
            if (job.getStatus() != DiscoverJobStatus.Status.RUNNING) {
                return snapshot(job);
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("发现任务被中断");
            }
        }
        DiscoverJobStatus job = jobs.get(jobId);
        if (job != null && job.getStatus() == DiscoverJobStatus.Status.RUNNING) {
            job.setStatus(DiscoverJobStatus.Status.FAILED);
            job.setMessage("扫描超时");
            job.setFinishedAt(Instant.now());
        }
        return snapshot(job != null ? job : new DiscoverJobStatus());
    }

    private DiscoverJobStatus snapshot(DiscoverJobStatus job) {
        DiscoverJobStatus copy = new DiscoverJobStatus();
        copy.setJobId(job.getJobId());
        copy.setStatus(job.getStatus());
        copy.setTotal(job.getTotal());
        copy.setScanned(job.getScanned());
        copy.setFound(job.getFound());
        copy.setMessage(job.getMessage());
        copy.setNetwork(job.getNetwork());
        copy.setCommunity(job.getCommunity());
        copy.setSnmpPort(job.getSnmpPort());
        copy.setStartedAt(job.getStartedAt());
        copy.setFinishedAt(job.getFinishedAt());
        copy.setCandidates(new ArrayList<>(job.getCandidates()));
        return copy;
    }

    private String parseBaseIp(String network) {
        if (network == null || network.isBlank()) {
            throw new RuntimeException("网段不能为空");
        }
        String[] parts = network.trim().split("\\.");
        if (parts.length < 3) {
            throw new RuntimeException("网段格式无效，例如: 192.168.56.0");
        }
        return parts[0] + "." + parts[1] + "." + parts[2] + ".";
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private void cleanupExpiredJobs() {
        long now = System.currentTimeMillis();
        jobs.entrySet().removeIf(e -> {
            Instant finished = e.getValue().getFinishedAt();
            Instant started = e.getValue().getStartedAt();
            Instant anchor = finished != null ? finished : started;
            return anchor != null && now - anchor.toEpochMilli() > JOB_TTL_MS;
        });
    }

    @PreDestroy
    public void shutdown() {
        jobExecutor.shutdownNow();
        scanExecutor.shutdownNow();
    }
}
