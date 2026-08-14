package com.ensp.nms.service;

import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.DevicePort;
import com.ensp.nms.entity.TopologyLink;
import com.ensp.nms.entity.TopologyNode;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.BackupScheduleRepository;
import com.ensp.nms.repository.ConfigChangeLogRepository;
import com.ensp.nms.repository.DeviceConfigRepository;
import com.ensp.nms.repository.DeviceIpAliasRepository;
import com.ensp.nms.repository.DevicePortRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.PerformanceAlertRepository;
import com.ensp.nms.repository.PerformanceDataRepository;
import com.ensp.nms.repository.TopologyLinkRepository;
import com.ensp.nms.repository.TopologyNodeRepository;
import com.ensp.nms.device.DeviceType;
import com.ensp.nms.device.MonitorMode;
import com.ensp.nms.net.IcmpClient;
import com.ensp.nms.snmp.SnmpClient;
import com.ensp.nms.ssh.SshClient;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final DevicePortRepository devicePortRepository;
    private final PerformanceDataRepository performanceDataRepository;
    private final SnmpClient snmpClient;
    private final IcmpClient icmpClient;
    private final SshClient sshClient;
    private final DeviceCapabilityService deviceCapabilityService;
    private final DeviceIpAliasService deviceIpAliasService;
    private final DeviceIpAliasRepository deviceIpAliasRepository;
    private final TopologyNodeRepository topologyNodeRepository;
    private final TopologyLinkRepository topologyLinkRepository;
    private final DeviceConfigRepository deviceConfigRepository;
    private final BackupScheduleRepository backupScheduleRepository;
    private final ConfigChangeLogRepository configChangeLogRepository;
    private final PerformanceAlertRepository performanceAlertRepository;
    private final AlarmRepository alarmRepository;
    private final ConnectivityRecoveryService connectivityRecoveryService;

    public List<Device> getAllDevices() {
        List<Device> devices = deviceRepository.findAll();
        devices.forEach(deviceCapabilityService::enrich);
        return devices;
    }

    public Page<Device> queryDevices(String keyword, String status, Long groupId, Pageable pageable) {
        return queryDevices(keyword, status, groupId, null, null, null, pageable);
    }

    public Page<Device> queryDevices(String keyword, String status, Long groupId,
                                     String deviceType, String monitorMode, String vendor,
                                     Pageable pageable) {
        Specification<Device> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("ipAddress")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("model"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("vendor"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("deviceType"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("location"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("serialNumber"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("contact"), "")), like)
                ));
            }
            if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
                predicates.add(cb.equal(root.get("status"), status.trim().toLowerCase()));
            }
            if (groupId != null) {
                if (groupId < 0) {
                    predicates.add(cb.isNull(root.get("groupId")));
                } else {
                    predicates.add(cb.equal(root.get("groupId"), groupId));
                }
            }
            if (deviceType != null && !deviceType.isBlank() && !"all".equalsIgnoreCase(deviceType)) {
                predicates.add(cb.equal(cb.lower(root.get("deviceType")), deviceType.trim().toLowerCase()));
            }
            if (monitorMode != null && !monitorMode.isBlank() && !"all".equalsIgnoreCase(monitorMode)) {
                predicates.add(cb.equal(cb.lower(root.get("monitorMode")), monitorMode.trim().toLowerCase()));
            }
            if (vendor != null && !vendor.isBlank()) {
                String like = "%" + vendor.trim().toLowerCase() + "%";
                predicates.add(cb.like(cb.lower(cb.coalesce(root.get("vendor"), "")), like));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<Device> page = deviceRepository.findAll(spec, pageable);
        page.forEach(deviceCapabilityService::enrich);
        return page;
    }

    /** 设备资产统计（真实网管首页条） */
    public Map<String, Object> getDeviceStats() {
        List<Device> all = deviceRepository.findAll();
        long total = all.size();
        long online = all.stream().filter(d -> "online".equalsIgnoreCase(d.getStatus())).count();
        long offline = total - online;
        long ungrouped = all.stream().filter(d -> d.getGroupId() == null).count();
        long withSsh = all.stream()
                .filter(d -> d.getSshUsername() != null && !d.getSshUsername().isBlank())
                .count();
        Map<String, Long> byType = new LinkedHashMap<>();
        for (Device d : all) {
            String t = d.getDeviceType() != null ? d.getDeviceType() : "other";
            byType.merge(t, 1L, Long::sum);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("online", online);
        m.put("offline", offline);
        m.put("ungrouped", ungrouped);
        m.put("withSsh", withSsh);
        m.put("byType", byType);
        return m;
    }

    @Transactional
    public Map<String, Object> batchRefresh(List<Long> ids) {
        List<Long> safe = normalizeIds(ids);
        int success = 0;
        int failed = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (Long id : safe) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            try {
                Device d = refreshDeviceStatus(id);
                row.put("ok", true);
                row.put("name", d.getName());
                row.put("status", d.getStatus());
                row.put("probeMethod", d.getLastProbeMethod());
                success++;
            } catch (Exception e) {
                row.put("ok", false);
                row.put("message", e.getMessage());
                failed++;
            }
            details.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested", safe.size());
        result.put("success", success);
        result.put("failed", failed);
        result.put("details", details);
        return result;
    }

    @Transactional
    public Map<String, Object> batchDelete(List<Long> ids) {
        List<Long> safe = normalizeIds(ids);
        int success = 0;
        int failed = 0;
        for (Long id : safe) {
            try {
                deleteDevice(id);
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("批量删除设备失败 id={}: {}", id, e.getMessage());
            }
        }
        return Map.of("requested", safe.size(), "success", success, "failed", failed);
    }

    @Transactional
    public Map<String, Object> batchUpdateGroup(List<Long> ids, Long groupId) {
        List<Long> safe = normalizeIds(ids);
        int n = 0;
        for (Long id : safe) {
            Optional<Device> opt = deviceRepository.findById(id);
            if (opt.isEmpty()) continue;
            Device d = opt.get();
            d.setGroupId(groupId);
            deviceRepository.save(d);
            n++;
        }
        return Map.of("updated", n, "groupId", groupId != null ? groupId : -1);
    }

    @Transactional
    public Map<String, Object> batchUpdateCredentials(List<Long> ids, Map<String, Object> cred) {
        List<Long> safe = normalizeIds(ids);
        if (cred == null) {
            throw new RuntimeException("凭证参数为空");
        }
        int n = 0;
        for (Long id : safe) {
            Optional<Device> opt = deviceRepository.findById(id);
            if (opt.isEmpty()) continue;
            Device d = opt.get();
            if (cred.containsKey("snmpCommunity") && cred.get("snmpCommunity") != null) {
                String c = String.valueOf(cred.get("snmpCommunity")).trim();
                if (!c.isEmpty()) d.setSnmpCommunity(c);
            }
            if (cred.containsKey("snmpPort") && cred.get("snmpPort") != null) {
                d.setSnmpPort(Integer.parseInt(String.valueOf(cred.get("snmpPort"))));
            }
            if (cred.containsKey("snmpVersion") && cred.get("snmpVersion") != null) {
                String v = String.valueOf(cred.get("snmpVersion")).trim();
                if (!v.isEmpty() && !"v3".equalsIgnoreCase(v)) d.setSnmpVersion(v);
            }
            if (cred.containsKey("sshUsername") && cred.get("sshUsername") != null) {
                d.setSshUsername(String.valueOf(cred.get("sshUsername")).trim());
            }
            if (cred.containsKey("sshPassword") && cred.get("sshPassword") != null) {
                String p = String.valueOf(cred.get("sshPassword"));
                if (!p.isBlank()) d.setSshPassword(p.trim());
            }
            if (cred.containsKey("sshPort") && cred.get("sshPort") != null) {
                d.setSshPort(Integer.parseInt(String.valueOf(cred.get("sshPort"))));
            }
            deviceCapabilityService.normalize(d);
            deviceRepository.save(d);
            n++;
        }
        return Map.of("updated", n);
    }

    private static List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().limit(200).toList();
    }

    public Optional<Device> getDeviceById(Long id) {
        return deviceRepository.findById(id).map(device -> {
            deviceCapabilityService.enrich(device);
            return device;
        });
    }

    @Transactional
    public Device createDevice(Device device) {
        if (deviceRepository.existsByIpAddress(device.getIpAddress())) {
            throw new RuntimeException("Device with IP " + device.getIpAddress() + " already exists");
        }
        deviceCapabilityService.normalize(device);
        Device saved = deviceRepository.save(device);
        deviceCapabilityService.enrich(saved);
        return saved;
    }

    @Transactional
    public Device updateDevice(Long id, Device deviceDetails) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Device not found"));

        if (deviceDetails.getIpAddress() != null && !deviceDetails.getIpAddress().isBlank()) {
            Optional<Device> ipOwner = deviceRepository.findByIpAddress(deviceDetails.getIpAddress().trim());
            if (ipOwner.isPresent() && !ipOwner.get().getId().equals(id)) {
                throw new RuntimeException("IP 地址已被其它设备占用: " + deviceDetails.getIpAddress());
            }
            device.setIpAddress(deviceDetails.getIpAddress().trim());
        }

        if (deviceDetails.getName() != null) {
            device.setName(deviceDetails.getName().trim());
        }
        if (deviceDetails.getModel() != null) {
            device.setModel(deviceDetails.getModel());
        }
        if (deviceDetails.getVendor() != null) {
            device.setVendor(deviceDetails.getVendor());
        }
        if (deviceDetails.getSnmpVersion() != null) {
            device.setSnmpVersion(deviceDetails.getSnmpVersion());
        }
        if (deviceDetails.getSnmpCommunity() != null) {
            device.setSnmpCommunity(deviceDetails.getSnmpCommunity());
        }
        if (deviceDetails.getSnmpPort() != null) {
            device.setSnmpPort(deviceDetails.getSnmpPort());
        }
        if (deviceDetails.getSshUsername() != null) {
            device.setSshUsername(deviceDetails.getSshUsername());
        }
        // 空密码表示本次不改密码，避免编辑同名设备时把密码清空后看起来像「恢复了」
        if (deviceDetails.getSshPassword() != null && !deviceDetails.getSshPassword().isBlank()) {
            device.setSshPassword(deviceDetails.getSshPassword());
        }
        if (deviceDetails.getSshPort() != null) {
            device.setSshPort(deviceDetails.getSshPort());
        }
        if (deviceDetails.getDescription() != null) {
            device.setDescription(deviceDetails.getDescription());
        }
        if (deviceDetails.getDeviceType() != null && !deviceDetails.getDeviceType().isBlank()) {
            device.setDeviceType(deviceDetails.getDeviceType().trim());
        }
        if (deviceDetails.getMonitorMode() != null && !deviceDetails.getMonitorMode().isBlank()) {
            device.setMonitorMode(deviceDetails.getMonitorMode().trim());
        }
        if (deviceDetails.getLocation() != null) {
            device.setLocation(deviceDetails.getLocation().isBlank() ? null : deviceDetails.getLocation().trim());
        }
        if (deviceDetails.getContact() != null) {
            device.setContact(deviceDetails.getContact().isBlank() ? null : deviceDetails.getContact().trim());
        }
        if (deviceDetails.getSerialNumber() != null) {
            device.setSerialNumber(deviceDetails.getSerialNumber().isBlank()
                    ? null : deviceDetails.getSerialNumber().trim());
        }
        // 允许置空分组（前端传 null）
        device.setGroupId(deviceDetails.getGroupId());

        deviceCapabilityService.normalize(device);
        Device saved = deviceRepository.save(device);
        deviceCapabilityService.enrich(saved);
        return saved;
    }

    @Transactional
    public void deleteDevice(Long id) {
        if (!deviceRepository.existsById(id)) {
            throw new RuntimeException("Device not found");
        }

        // 拓扑：链路端点存的是 device_id，与 topology_node.id 无关
        List<TopologyLink> links = topologyLinkRepository.findBySourceNodeIdOrTargetNodeId(id, id);
        if (!links.isEmpty()) {
            topologyLinkRepository.deleteAll(links);
        }
        List<TopologyNode> nodes = topologyNodeRepository.findAllByDeviceId(id);
        if (!nodes.isEmpty()) {
            topologyNodeRepository.deleteAll(nodes);
        }

        devicePortRepository.deleteByDeviceId(id);
        deviceConfigRepository.deleteByDeviceId(id);
        backupScheduleRepository.deleteByDeviceId(id);
        configChangeLogRepository.deleteByDeviceId(id);
        performanceAlertRepository.deleteByDeviceId(id);
        performanceDataRepository.deleteByDeviceId(id);
        deviceIpAliasRepository.deleteByDeviceId(id);
        alarmRepository.clearDeviceReference(id);

        deviceRepository.deleteById(id);
        log.info("已级联删除设备及相关数据: deviceId={}", id);
    }

    @Transactional
    public Device refreshDeviceStatus(Long id) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Device not found"));
        deviceCapabilityService.normalize(device);
        Map<String, Boolean> caps = deviceCapabilityService.resolveCapabilities(device);
        MonitorMode mode = MonitorMode.fromCode(device.getMonitorMode());
        DeviceType type = DeviceType.fromCode(device.getDeviceType());

        String ip = device.getIpAddress();
        int port = device.getSnmpPort() != null ? device.getSnmpPort() : 161;
        String community = (device.getSnmpCommunity() != null && !device.getSnmpCommunity().isBlank())
                ? device.getSnmpCommunity() : "public";

        boolean reachable = false;
        String probe = null;

        boolean trySnmp = Boolean.TRUE.equals(caps.get("snmp")) || mode == MonitorMode.AUTO;
        boolean tryIcmp = Boolean.TRUE.equals(caps.get("icmp"))
                || mode == MonitorMode.ICMP
                || type == DeviceType.PC
                || mode == MonitorMode.AUTO;

        if (trySnmp && type != DeviceType.PC && mode != MonitorMode.ICMP) {
            log.info("刷新设备状态(SNMP): IP={}, Port={}", ip, port);
            reachable = snmpClient.isReachable(ip, port, community);
            if (reachable) {
                probe = "snmp";
            }
        }

        if (!reachable && tryIcmp) {
            log.info("刷新设备状态(ICMP): IP={}", ip);
            reachable = icmpClient.ping(ip);
            if (reachable) {
                probe = "icmp";
            }
        }

        if (probe == null) {
            probe = (mode == MonitorMode.ICMP || type == DeviceType.PC) ? "icmp" : "snmp";
        }

        log.info("设备 {} 可达性: {} (probe={})", ip, reachable, probe);

        // 去抖落库：连续成功才 online+CLEAR，连续失败才 offline
        ConnectivityRecoveryService.ProbeApplyResult probeResult =
                connectivityRecoveryService.applyProbeResult(device, reachable, probe);
        Device saved = deviceRepository.findById(id).orElse(device);
        if (reachable && "snmp".equals(probe) && probeResult.isOnline()) {
            try {
                deviceIpAliasService.refreshDeviceAliases(saved);
            } catch (Exception e) {
                log.debug("刷新设备接口 IP 别名失败: {}", e.getMessage());
            }
        }
        deviceCapabilityService.enrich(saved);
        return saved;
    }

    /** 最近一次探测去抖快照（连续成功/失败计数） */
    public Map<String, Object> getProbeSnapshot(Long deviceId) {
        Map<String, Object> snap = connectivityRecoveryService.snapshot(deviceId);
        deviceRepository.findById(deviceId).ifPresent(d -> {
            if (d.getLastProbeMethod() != null) {
                snap.put("probeMethod", d.getLastProbeMethod());
            }
            if (d.getStatus() != null) {
                snap.put("status", d.getStatus());
            }
        });
        return snap;
    }

    /**
     * 连通性三联测：ICMP / SNMP / SSH（不改库状态，仅诊断）。
     */
    public Map<String, Object> testConnectivity(Long id) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Device not found"));
        deviceCapabilityService.normalize(device);
        Map<String, Boolean> caps = deviceCapabilityService.resolveCapabilities(device);

        String ip = device.getIpAddress();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", id);
        result.put("deviceName", device.getName());
        result.put("ipAddress", ip);
        result.put("testedAt", LocalDateTime.now().toString());

        Map<String, Object> icmp = new LinkedHashMap<>();
        boolean icmpEnabled = Boolean.TRUE.equals(caps.get("icmp"));
        icmp.put("enabled", icmpEnabled);
        if (icmpEnabled && ip != null && !ip.isBlank()) {
            long t0 = System.currentTimeMillis();
            boolean ok = false;
            String err = null;
            try {
                ok = icmpClient.ping(ip);
            } catch (Exception e) {
                err = e.getMessage();
            }
            icmp.put("ok", ok);
            icmp.put("latencyMs", System.currentTimeMillis() - t0);
            if (err != null) icmp.put("error", err);
        } else {
            icmp.put("ok", null);
            icmp.put("skipped", true);
            icmp.put("reason", "设备能力未启用 ICMP");
        }
        result.put("icmp", icmp);

        Map<String, Object> snmp = new LinkedHashMap<>();
        boolean snmpEnabled = Boolean.TRUE.equals(caps.get("snmp"));
        snmp.put("enabled", snmpEnabled);
        if (snmpEnabled && ip != null && !ip.isBlank()) {
            int port = device.getSnmpPort() != null ? device.getSnmpPort() : 161;
            String community = (device.getSnmpCommunity() != null && !device.getSnmpCommunity().isBlank())
                    ? device.getSnmpCommunity() : "public";
            long t0 = System.currentTimeMillis();
            boolean ok = false;
            String err = null;
            try {
                ok = snmpClient.isReachable(ip, port, community);
            } catch (Exception e) {
                err = e.getMessage();
            }
            snmp.put("ok", ok);
            snmp.put("latencyMs", System.currentTimeMillis() - t0);
            snmp.put("port", port);
            snmp.put("community", community != null && community.length() > 2 ? community.substring(0, 1) + "***" : "***");
            if (err != null) snmp.put("error", err);
        } else {
            snmp.put("ok", null);
            snmp.put("skipped", true);
            snmp.put("reason", "设备能力未启用 SNMP（如仅 Ping / 虚拟PC）");
        }
        result.put("snmp", snmp);

        Map<String, Object> ssh = new LinkedHashMap<>();
        boolean sshEnabled = Boolean.TRUE.equals(caps.get("webssh"))
                || (device.getSshUsername() != null && !device.getSshUsername().isBlank());
        ssh.put("enabled", sshEnabled);
        if (sshEnabled) {
            if (device.getSshUsername() == null || device.getSshUsername().isBlank()
                    || device.getSshPassword() == null || device.getSshPassword().isBlank()) {
                ssh.put("ok", false);
                ssh.put("skipped", true);
                ssh.put("reason", "未配置 SSH 用户名或密码");
            } else {
                int port = device.getSshPort() != null ? device.getSshPort() : 22;
                long t0 = System.currentTimeMillis();
                boolean ok = false;
                String err = null;
                try {
                    ok = sshClient.testConnection(ip, port, device.getSshUsername(),
                            device.getSshPassword());
                } catch (Exception e) {
                    err = e.getMessage();
                }
                ssh.put("ok", ok);
                ssh.put("latencyMs", System.currentTimeMillis() - t0);
                ssh.put("port", port);
                ssh.put("username", device.getSshUsername());
                if (err != null) ssh.put("error", err);
            }
        } else {
            ssh.put("ok", null);
            ssh.put("skipped", true);
            ssh.put("reason", "未启用 SSH");
        }
        result.put("ssh", ssh);

        boolean anyFail = Boolean.FALSE.equals(icmp.get("ok"))
                || Boolean.FALSE.equals(snmp.get("ok"))
                || Boolean.FALSE.equals(ssh.get("ok"));
        boolean anyOk = Boolean.TRUE.equals(icmp.get("ok"))
                || Boolean.TRUE.equals(snmp.get("ok"))
                || Boolean.TRUE.equals(ssh.get("ok"));
        result.put("summary", anyOk && !anyFail ? "全部通过"
                : anyOk ? "部分通过"
                : "全部失败或跳过");
        return result;
    }

    /** 按筛选条件导出（最多 5000 条） */
    public List<Device> listForExport(String keyword, String status, Long groupId,
                                      String deviceType, String monitorMode, String vendor) {
        Pageable pageable = PageRequest.of(0, 5000, Sort.by(Sort.Direction.ASC, "id"));
        return queryDevices(keyword, status, groupId, deviceType, monitorMode, vendor, pageable).getContent();
    }

    /**
     * 获取设备的真实端口列表 - 优先从 performance_data 表（SNMP 采集数据）中读取，
     * 确保端口名与设备实际型号匹配（如交换机使用 Ethernet，路由器使用 GigabitEthernet）。
     * 若无性能数据则回退到 device_port 表。
     */
    public List<DevicePort> getDevicePorts(Long deviceId) {
        List<DevicePort> result = new java.util.ArrayList<>();

        // 第一步：从 performance_data 获取真实端口（各端口最新一条记录）
        List<com.ensp.nms.entity.PerformanceData> portMetrics =
                performanceDataRepository.findLatestPortMetricsByDeviceId(deviceId);

        if (portMetrics != null && !portMetrics.isEmpty()) {
            for (com.ensp.nms.entity.PerformanceData metric : portMetrics) {
                if (metric.getPortName() == null || metric.getPortName().trim().isEmpty()) {
                    continue;
                }
                DevicePort port = new DevicePort();
                port.setId(metric.getId());
                port.setDeviceId(deviceId);
                port.setPortName(metric.getPortName().trim());
                port.setIfIndex(metric.getPortIndex());
                double inRate = metric.getIfInRate() != null ? metric.getIfInRate() : 0;
                double outRate = metric.getIfOutRate() != null ? metric.getIfOutRate() : 0;
                String oper = metric.getPortOperStatus();
                if (oper == null || oper.isBlank()) {
                    oper = (inRate > 0 || outRate > 0) ? "up" : "unknown";
                }
                port.setOperStatus(oper);
                port.setSpeed((long) Math.max(inRate, outRate));
                result.add(port);
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        // 第二步：回退到 device_port 表（如仍有旧的种子数据）
        List<DevicePort> fallback = devicePortRepository.findByDeviceId(deviceId);
        if (fallback != null && !fallback.isEmpty()) {
            return fallback;
        }

        // 无数据，返回空
        return result;
    }
}
