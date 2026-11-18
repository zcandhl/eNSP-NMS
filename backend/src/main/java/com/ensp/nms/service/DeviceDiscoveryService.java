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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /** 网段扫描之外的附加探测 IP（逗号分隔）。用于管理地址不在管理网段的设备，如出口路由器 10.20.255.21。 */
    @Value("${nms.discovery.extra-ips:}")
    private String extraIpsConfig;

    /**
     * 启动异步扫描任务，仅返回候选设备，不自动入库。
     */
    public DiscoverJobStatus startScan(String network, int timeoutSeconds, String community, int snmpPort) {
        cleanupExpiredJobs();
        if (jobs.size() >= MAX_JOBS) {
            throw new RuntimeException("发现任务过多，请稍后再试");
        }

        String baseIp = parseBaseIp(network);
        List<String> extraIps = resolveExtraIps();
        String jobId = UUID.randomUUID().toString().replace("-", "");
        DiscoverJobStatus job = new DiscoverJobStatus();
        job.setJobId(jobId);
        job.setTotal(254 + extraIps.size());
        job.setNetwork(network);
        job.setCommunity(blankToDefault(community, "public"));
        job.setSnmpPort(snmpPort > 0 ? snmpPort : 161);
        job.setMessage("扫描中…");
        jobs.put(jobId, job);

        String resolvedCommunity = job.getCommunity();
        int resolvedPort = job.getSnmpPort();
        int timeout = Math.max(5, Math.min(timeoutSeconds, 120));

        jobExecutor.submit(() -> runScan(job, baseIp, resolvedCommunity, resolvedPort, timeout, extraIps));
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
        return importCandidates(candidates, null);
    }

    /**
     * 发现设备入库并绑定工程。
     * projectId 为空/负数时不绑定（归入「未分配」）；已存在的设备只在「未分配」时补绑，
     * 不抢占已归属其他工程的设备。
     */
    @Transactional
    public List<Device> importCandidates(List<DiscoverCandidate> candidates, Long projectId) {
        Long bindProject = (projectId != null && projectId > 0) ? projectId : null;
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
                if (bindProject != null && device.getProjectId() == null) {
                    device.setProjectId(bindProject);
                }
                saved.add(deviceRepository.save(device));
                continue;
            }
            Device device = new Device();
            device.setIpAddress(ip);
            if (bindProject != null) {
                device.setProjectId(bindProject);
            }
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

    private void runScan(DiscoverJobStatus job, String baseIp, String community, int snmpPort, int timeoutSeconds,
                         List<String> extraIps) {
        List<Future<DiscoverCandidate>> futures = new ArrayList<>(254 + extraIps.size());
        for (int i = 1; i <= 254; i++) {
            String ip = baseIp + i;
            futures.add(scanExecutor.submit(() -> scanIp(ip, snmpPort, community)));
        }
        // 附加探测 IP：管理地址不在扫描网段内的设备（如出口路由 LoopBack）
        for (String extraIp : extraIps) {
            futures.add(scanExecutor.submit(() -> scanIp(extraIp, snmpPort, community)));
        }

        // 每个地址最多要试两种 community（主值 + 大小写变体），耗时略高于单次探测；
        // 这里给 timeout 额外 20s 宽限，避免按裸 timeout 硬卡时把队列尾部的地址整段取消。
        long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds + 20L);
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
        // 设备侧 community 区分大小写：AC6005 有复杂度校验，只允许 Public（小写 public 被拒绝），
        // 而网管默认配置是 public。若主 community 无响应，再按首字母大写变体复检一次，
        // 命中后把「实际生效」的 community 写进候选，避免入库后方站一直离线。
        List<String> candidates = communityCandidates(community);
        DiscoverCandidate hit = scanIpWithCommunity(ip, snmpPort, candidates.get(0), 2000, 1);
        if (hit != null) {
            return hit;
        }
        // 变体用短超时+不重试：局域网往返通常 < 50ms，600ms 足够，
        // 且能避免 254 个地址 × 两轮探测把整轮扫描时间翻倍。
        // 但 eNSP 的 AC6005 agent 在扫描风暴下响应会抖到 600ms 以上，给到 1200ms 一次机会。
        for (int i = 1; i < candidates.size(); i++) {
            hit = scanIpWithCommunity(ip, snmpPort, candidates.get(i), 1200, 0);
            if (hit != null) {
                log.info("IP {} 使用 community '{}' 命中（主值 '{}' 无响应）", ip, candidates.get(i), candidates.get(0));
                return hit;
            }
        }
        return null;
    }

    private List<String> communityCandidates(String community) {
        String base = blankToDefault(community, "public");
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(base);
        if (!base.isEmpty()) {
            String capitalized = Character.toUpperCase(base.charAt(0)) + base.substring(1);
            set.add(capitalized);
        }
        return new ArrayList<>(set);
    }

    private DiscoverCandidate scanIpWithCommunity(String ip, int snmpPort, String community) {
        return scanIpWithCommunity(ip, snmpPort, community, 2000, 1);
    }

    private DiscoverCandidate scanIpWithCommunity(String ip, int snmpPort, String community, int timeoutMs, int retries) {
        try {
            // eNSP 虚拟机常不响应 ICMP，直接 SNMP 探测
            if (!snmpClient.isReachable(ip, snmpPort, community, timeoutMs, retries)) {
                return null;
            }
            Map<String, String> deviceInfo = snmpClient.getDeviceInfo(ip, snmpPort, community);

            // 只认"设备本机接口地址"：VRRP 虚拟网关（如 10.20.10.254）也会响应 SNMP，
            // 但它的地址不在对端 ipAdEntAddr 表里，若不剔除会多出一台假设备。
            // walk 失败（返回空）时不做判断，退回原行为，避免因权限/超时误杀真实设备。
            List<Map<String, String>> addrRows = snmpClient.walk(ip, snmpPort, community, "1.3.6.1.2.1.4.20.1.1");
            if (!addrRows.isEmpty()) {
                boolean owned = false;
                for (Map<String, String> row : addrRows) {
                    for (String v : row.values()) {
                        if (ip.equals(v == null ? "" : v.trim())) {
                            owned = true;
                            break;
                        }
                    }
                    if (owned) {
                        break;
                    }
                }
                if (!owned) {
                    log.debug("{} 非设备本机接口地址（VRRP 虚拟地址或重复管理地址），跳过", ip);
                    return null;
                }
            }

            DiscoverCandidate candidate = new DiscoverCandidate();
            candidate.setIpAddress(ip);
            String sysName = deviceInfo.getOrDefault("name", "").trim();
            if (sysName.isEmpty()) {
                sysName = "Device-" + ip;
            }
            String sysDescr = deviceInfo.getOrDefault("description", "");
            String sysObjectId = deviceInfo.getOrDefault("sysObjectId", "");
            String deviceType = deviceCapabilityService.detectTypeCode(sysName, sysDescr, sysObjectId, null);
            // 设备名直接用 sysName（与拓扑、CLI 一致），不再拼 IP 后缀
            candidate.setName(sysName);
            candidate.setModel(parseModel(sysDescr));
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

    /**
     * 从 sysDescr 第一行解析型号：eNSP 回显形如
     * "S5700-28C-HI \r\nHuawei Versatile Routing Platform Software..." 或
     * "Huawei AR2220 Huawei Versatile Routing Platform Software..."。
     */
    private String parseModel(String sysDescr) {
        if (sysDescr == null || sysDescr.isBlank()) {
            return "eNSP Device";
        }
        String firstLine = sysDescr.split("\\r?\\n")[0].trim();
        String[] parts = firstLine.split("\\s+");
        if (parts.length == 0) {
            return "eNSP Device";
        }
        String first = parts[0];
        if ("Huawei".equalsIgnoreCase(first) && parts.length > 1) {
            return parts[1];
        }
        return first.length() <= 32 ? first : "eNSP Device";
    }

    private DiscoverJobStatus awaitJob(String jobId, int maxWaitSeconds) {        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(maxWaitSeconds, 10));
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

    /** 解析附加扫描 IP 配置：逗号/分号分隔，去重去空，仅保留合法 IPv4 */
    private List<String> resolveExtraIps() {
        if (extraIpsConfig == null || extraIpsConfig.isBlank()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> ips = new java.util.LinkedHashSet<>();
        for (String raw : extraIpsConfig.split("[,;，；\\s]+")) {
            String ip = raw.trim();
            if (ip.isEmpty() || !ip.matches("(\\d{1,3}\\.){3}\\d{1,3}")) {
                continue;
            }
            boolean valid = true;
            for (String octet : ip.split("\\.")) {
                int v = Integer.parseInt(octet);
                if (v < 0 || v > 255) {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                ips.add(ip);
            }
        }
        return new ArrayList<>(ips);
    }

    private String parseBaseIp(String network) {        if (network == null || network.isBlank()) {
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
