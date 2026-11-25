package com.ensp.nms.service;

import com.ensp.nms.device.DeviceType;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.TopologyLink;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.TopologyLinkRepository;
import com.ensp.nms.repository.TopologyNodeRepository;
import com.ensp.nms.snmp.SnmpClient;
import com.ensp.nms.ssh.SshClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 基于 LLDP/CDP/ARP 的拓扑链路发现与对齐。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Slf4j
@Service
@RequiredArgsConstructor
public class TopologyDiscoveryService {

    private final DeviceRepository deviceRepository;
    private final TopologyLinkRepository topologyLinkRepository;
    private final TopologyNodeRepository topologyNodeRepository;
    private final SnmpClient snmpClient;
    private final DeviceCapabilityService deviceCapabilityService;
    private final SshClient sshClient;

    @Value("${nms.ssh.default-username:}")
    private String defaultSshUsername;

    @Value("${nms.ssh.default-password:}")
    private String defaultSshPassword;

    @Value("${nms.topology.arp-fallback-enabled:false}")
    private boolean arpFallbackEnabled;

    @Transactional
    public Map<String, Object> discoverTopology() {
        return discoverTopology(null);
    }

    @Transactional
    public Map<String, Object> discoverTopology(Map<String, Object> options) {
        List<Device> allDevices = deviceRepository.findAll();
        List<Device> devices = filterDevicesForDiscovery(allDevices, options);
        String method = resolveDiscoveryMethod(options);

        Map<String, Object> result = new HashMap<>();

        // clearLinks=true：先清空历史链路，避免ARP兜底等旧数据残留
        if (options != null && Boolean.parseBoolean(String.valueOf(options.get("clearLinks")))) {
            long removed = topologyLinkRepository.count();
            topologyLinkRepository.deleteAll();
            result.put("clearedLinks", removed);
            log.info("clearLinks=true：已清空历史链路 {} 条", removed);
        }

        result.put("totalDevices", devices.size());
        result.put("discoveryMethod", method);

        int discoveredLinks = 0;
        int skippedOffline = 0;
        int scannedOnline = 0;
        // 是否真的读到了邻居表（LLDP/CDP/SSH）。只要读到，就说明这些是「设备自己声明的直连」
        // 绝不能再拿ARP兜底去补，否则每轮都会新增一批假链路。
        boolean lldpAuthoritative = false;
        String primaryMethod = "LLDP/CDP";
        Set<String> addedLinks = new HashSet<>();
        List<Map<String, Object>> deviceResults = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        List<Map<String, Object>> unmatchedNeighbors = new ArrayList<>();

        // 预热MAC→IP
        Map<String, String> macToIp = buildMacToIpCache(devices);

        if (!"arp".equals(method)) {
            for (Device device : devices) {
            if (!"online".equals(device.getStatus())) {
                skippedOffline++;
                Map<String, Object> skipped = new HashMap<>();
                skipped.put("deviceId", device.getId());
                skipped.put("deviceName", device.getName());
                skipped.put("ip", device.getIpAddress());
                skipped.put("status", "skipped");
                skipped.put("reason", "设备离线，跳过 SNMP 发现");
                deviceResults.add(skipped);
                continue;
            }

            Map<String, Boolean> caps = deviceCapabilityService.resolveCapabilities(device);
            if (!Boolean.TRUE.equals(caps.get("topologyDiscover"))) {
                Map<String, Object> skipped = new HashMap<>();
                skipped.put("deviceId", device.getId());
                skipped.put("deviceName", device.getName());
                skipped.put("ip", device.getIpAddress());
                skipped.put("status", "skipped");
                skipped.put("reason", "设备类型不支持拓扑发现（如虚拟 PC）");
                deviceResults.add(skipped);
                continue;
            }

            scannedOnline++;
            Map<String, Object> deviceResult = new HashMap<>();
            deviceResult.put("deviceId", device.getId());
            deviceResult.put("deviceName", device.getName());
            deviceResult.put("ip", device.getIpAddress());
            deviceResult.put("status", "ok");

            int linksCreated = 0;
            List<String> sources = new ArrayList<>();

            try {
                String ip = device.getIpAddress();
                int port = device.getSnmpPort() != null ? device.getSnmpPort() : 161;
                String community = device.getSnmpCommunity() != null ? device.getSnmpCommunity() : "public";

                log.info("正在发现设备 {} ({}) 的邻居...", device.getName(), ip);

                List<Map<String, String>> neighbors = new ArrayList<>();

                List<Map<String, String>> lldpNeighbors = snmpClient.discoverLLDPNeighbors(ip, port, community);
                if (!lldpNeighbors.isEmpty()) {
                    neighbors.addAll(lldpNeighbors);
                    sources.add("LLDP");
                    log.info("  LLDP发现 {} 个邻居", lldpNeighbors.size());
                }

                List<Map<String, String>> cdpNeighbors = snmpClient.discoverCDPNeighbors(ip, port, community);
                if (!cdpNeighbors.isEmpty()) {
                    neighbors.addAll(cdpNeighbors);
                    sources.add("CDP");
                    log.info("  CDP发现 {} 个邻居", cdpNeighbors.size());
                }

                List<Map<String, String>> hwLldpNeighbors = snmpClient.discoverHuaweiLLDPNeighbors(ip, port, community);
                if (!hwLldpNeighbors.isEmpty()) {
                    neighbors.addAll(hwLldpNeighbors);
                    sources.add("华为LLDP");
                    log.info("  华为私有LLDP发现 {} 个邻居", hwLldpNeighbors.size());
                }

                // 设备不暴露LLDP MIB，退而通过SSH采集CLI邻居表
                List<Map<String, String>> sshLldpNeighbors = discoverLldpNeighborsViaSsh(device);
                if (!sshLldpNeighbors.isEmpty()) {
                    neighbors.addAll(sshLldpNeighbors);
                    sources.add("SSH-LLDP");
                    log.info("  SSH LLDP发现 {} 个邻居", sshLldpNeighbors.size());
                }

                deviceResult.put("neighborCount", neighbors.size());
                deviceResult.put("sources", sources);
                if (!neighbors.isEmpty()) {
                    lldpAuthoritative = true;
                }

                for (Map<String, String> neighbor : neighbors) {
                    String neighborIp = extractNeighborIp(neighbor, macToIp);

                    Device neighborDevice = null;

                    if (neighborIp != null && !neighborIp.isEmpty()) {
                        log.info("  发现邻居IP: {}", neighborIp);
                        neighborDevice = deviceRepository.findByIpAddress(neighborIp).orElse(null);
                    }

                    if (neighborDevice == null) {
                        String sysName = neighbor.get("sysName");
                        if (sysName != null && !sysName.isEmpty()) {
                            log.info("  尝试通过sysName匹配: {}", sysName);
                            neighborDevice = findDeviceBySysName(sysName, devices);
                        }
                    }

                    if (neighborDevice == null) {
                        log.warn("  无法找到匹配的邻居设备: {}", neighbor);
                        Map<String, Object> unmatched = new HashMap<>();
                        unmatched.put("fromDevice", device.getName());
                        unmatched.put("fromIp", device.getIpAddress());
                        unmatched.put("neighborIp", neighborIp);
                        unmatched.put("sysName", neighbor.get("sysName"));
                        unmatched.put("reason", "邻居未在网管设备库中登记");
                        unmatchedNeighbors.add(unmatched);
                        continue;
                    }

                    if (neighborDevice.getId().equals(device.getId())) {
                        log.debug("  跳过自身连接: {}", neighborIp);
                        continue;
                    }

                    String sourcePort = firstNonBlank(
                            neighbor.get("localPort"),
                            neighbor.get("portId"));
                    String targetPort = firstNonBlank(
                            neighbor.get("remotePort"),
                            neighbor.get("portDescription"),
                            neighbor.get("portId"));

                    String linkKey = createLinkKey(device.getId(), neighborDevice.getId());
                    if (!addedLinks.contains(linkKey)) {
                        Optional<TopologyLink> existing =
                                topologyLinkRepository.findBetweenDevices(device.getId(), neighborDevice.getId());
                        if (existing.isEmpty()) {
                            TopologyLink link = new TopologyLink();
                            link.setSourceNodeId(device.getId());
                            link.setTargetNodeId(neighborDevice.getId());
                            link.setSourcePort(sourcePort);
                            link.setTargetPort(targetPort);
                            link.setStatus("up");
                            topologyLinkRepository.save(link);
                            addedLinks.add(linkKey);
                            discoveredLinks++;
                            linksCreated++;

                            log.info("  ✓ 发现连接: {} ({}) -> {} ({})",
                                    device.getName(), sourcePort,
                                    neighborDevice.getName(), targetPort);
                        } else {
                            // 已存在但可能是ARP兜底留下的「假链路」
                            // 用LLDP的真实端口把它补全，否则前端永远显示unknown ↔ ?
                            boolean upgraded = upgradeSyntheticLink(existing.get(), device.getId(), sourcePort, targetPort);
                            addedLinks.add(linkKey);
                            if (upgraded) {
                                log.info("  ↑ 已用 LLDP 端口补全历史链路: {} ({}) -> {} ({})",
                                        device.getName(), sourcePort, neighborDevice.getName(), targetPort);
                            } else {
                                log.debug("  连接已存在: {} -> {}", device.getName(), neighborDevice.getName());
                            }
                        }
                    }
                }

                deviceResult.put("linksCreated", linksCreated);
                if (neighbors.isEmpty()) {
                    deviceResult.put("hint", "未读到 LLDP/CDP 邻居（超时/Community/设备未开 LLDP）");
                }

            } catch (Exception e) {
                log.error("发现设备 {} 拓扑失败: {}", device.getName(), e.getMessage(), e);
                deviceResult.put("status", "error");
                deviceResult.put("reason", e.getMessage() != null ? e.getMessage() : "SNMP 发现异常");
                Map<String, Object> fail = new HashMap<>();
                fail.put("deviceName", device.getName());
                fail.put("ip", device.getIpAddress());
                fail.put("reason", e.getMessage() != null ? e.getMessage() : "SNMP 发现异常");
                failures.add(fail);
            }

            deviceResults.add(deviceResult);
        }
        }

        // 只要LLDP/CDP/SSH读到了邻居表，就以「设备自己声明的直连」为准
        // 顺手清掉历史ARP兜底留下的假链路。
        if (lldpAuthoritative && !"arp".equals(method)) {
            int purged = purgeSyntheticLinks();
            if (purged > 0) {
                result.put("purgedSyntheticLinks", purged);
                log.info("LLDP 邻居可用，已清理历史 ARP 假链路 {} 条", purged);
            }
        }

        // ARP只在「显式指定」或「完全读不到LLDP且开关打开」时兜底
        // 不能因为「本轮没有新增链路」就回退ARP —— 那会让每轮定时任务都新增一批假链路。
        boolean arpRequested = "arp".equals(method);
        boolean shouldFallback = arpRequested
                || (discoveredLinks == 0 && !lldpAuthoritative && arpFallbackEnabled);
        if (shouldFallback) {
            if (arpRequested) {
                primaryMethod = "ARP/MAC";
                deviceResults.clear();
                failures.clear();
                unmatchedNeighbors.clear();
                skippedOffline = 0;
                scannedOnline = 0;
                for (Device device : devices) {
                    if (!"online".equals(device.getStatus())) {
                        skippedOffline++;
                    } else {
                        scannedOnline++;
                    }
                }
            } else {
                log.info("所有设备均未返回 LLDP/CDP 邻居，回退 ARP/MAC 发现（nms.topology.arp-fallback-enabled=true）...");
                primaryMethod = "ARP/MAC";
            }
            int arpLinks = discoverTopologyByArpAndMac(devices, addedLinks);
            discoveredLinks += arpLinks;
            if (arpLinks == 0) {
                primaryMethod = discoveredLinks > 0 ? primaryMethod : "无新链路";
            } else if (!arpRequested) {
                primaryMethod = primaryMethod + "+ARP";
            }
        } else if (discoveredLinks == 0 && !lldpAuthoritative) {
            primaryMethod = "未读到 LLDP 邻居（ARP 兜底已关闭）";
        }

        result.put("discoveredLinks", discoveredLinks);
        result.put("scannedOnline", scannedOnline);
        result.put("skippedOffline", skippedOffline);
        result.put("primaryMethod", primaryMethod);
        result.put("deviceResults", deviceResults);
        result.put("failures", failures);
        result.put("unmatchedNeighbors", unmatchedNeighbors);
        result.put("message", String.format(
                "拓扑发现完成：共 %d 台设备（在线扫描 %d，离线跳过 %d），新发现 %d 条连接，方式：%s",
                devices.size(), scannedOnline, skippedOffline, discoveredLinks, primaryMethod));

        return result;
    }

    private List<Map<String, String>> discoverLldpNeighborsViaSsh(Device device) {
        // AC类设备SSH服务不可用：TCP能连上、banner正常，但收到KEXINIT后
        // 不响应，且会拖垮设备。故AC一律不走SSH采集
        // 邻居关系靠SNMP或人工登记补齐。
        if (DeviceType.fromCode(device.getDeviceType()) == DeviceType.AC) {
            // AC设备为telnet管理，且不暴露LLDP数据，跳过采集
            log.debug("设备 {} 为 AC，跳过 LLDP 采集（无 LLDP 数据）", device.getName());
            return List.of();
        }
        String user = firstNonBlank(device.getSshUsername(), defaultSshUsername);
        String pass = firstNonBlank(device.getSshPassword(), defaultSshPassword);
        if (user == null || pass == null) {
            log.debug("设备 {} 未登记远程管理凭据且无默认凭据，跳过 LLDP 采集", device.getName());
            return List.of();
        }
        // 按设备管理协议分流
        String protocol = device.isTelnetManagement()
                ? SshClient.PROTOCOL_TELNET : SshClient.PROTOCOL_SSH;
        int mgmtPort = device.getSshPort() != null && device.getSshPort() > 0
                ? device.getSshPort() : SshClient.defaultPort(protocol);
        try {
            String output = sshClient.executeDisplayCommand(
                    device.getIpAddress(), mgmtPort, user, pass, "display lldp neighbor brief", protocol);
            return parseLldpNeighborBrief(output);
        } catch (Exception e) {
            log.info("设备 {} SSH LLDP 采集失败: {}", device.getName(), e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, String>> parseLldpNeighborBrief(String output) {
        List<Map<String, String>> neighbors = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return neighbors;
        }
        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "^\\s*(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)\\s*$");
        for (String line : output.split("\\r?\\n")) {
            java.util.regex.Matcher m = row.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String localPort = m.group(1);
            String sysName = m.group(2);
            String remotePort = m.group(3);
            if ("Local".equalsIgnoreCase(localPort)) {
                continue;
            }
            Map<String, String> n = new HashMap<>();
            n.put("localPort", localPort);
            n.put("sysName", sysName);
            n.put("remotePort", remotePort);
            neighbors.add(n);
        }
        return neighbors;
    }

    @Transactional
    public int discoverTopologyByArpAndMac(List<Device> devices, Set<String> existingLinks) {
        int discoveredLinks = 0;

        log.info("开始基于ARP表+MAC地址表的拓扑发现...");

        // 首先扫描所有设备的IP和MAC信息
        Map<String, Device> deviceByIp = new HashMap<>();
        Map<String, Device> deviceByMac = new HashMap<>();
        
        for (Device device : devices) {
            deviceByIp.put(device.getIpAddress(), device);
            if (!Boolean.TRUE.equals(deviceCapabilityService.resolveCapabilities(device).get("topologyDiscover"))) {
                continue;
            }
            if (!"online".equals(device.getStatus())) {
                continue;
            }
            try {
                String ip = device.getIpAddress();
                int snmpPort = device.getSnmpPort() != null ? device.getSnmpPort() : 161;
                String community = device.getSnmpCommunity() != null ? device.getSnmpCommunity() : "public";

                log.info("正在扫描设备: {} ({})", device.getName(), ip);
                
                // 获取接口MAC地址
                Map<Integer, String> interfaceMacs = snmpClient.getInterfaceMacTable(ip, snmpPort, community);
                for (Map.Entry<Integer, String> entry : interfaceMacs.entrySet()) {
                    String mac = normalizeMac(entry.getValue());
                    if (mac != null && !mac.isEmpty()) {
                        deviceByMac.put(mac, device);
                        log.debug("  设备接口MAC: {} -> Port{} ({})", mac, entry.getKey(), device.getName());
                    }
                }

                // 获取接口表，用于端口信息
                Map<String, Object> interfaceStatus = snmpClient.getInterfaceStatus(ip, snmpPort, community);
                log.info("  发现 {} 个接口", interfaceStatus.size());
            } catch (Exception e) {
                log.warn("扫描设备 {} 基础信息失败: {}", device.getIpAddress(), e.getMessage());
            }
        }

        log.info("已加载 {} 台设备信息", devices.size());

        // 然后进行ARP表发现
        for (Device device : devices) {
            if (!Boolean.TRUE.equals(deviceCapabilityService.resolveCapabilities(device).get("topologyDiscover"))) {
                continue;
            }
            if (!"online".equals(device.getStatus())) {
                continue;
            }
            try {
                String ip = device.getIpAddress();
                int snmpPort = device.getSnmpPort() != null ? device.getSnmpPort() : 161;
                String community = device.getSnmpCommunity() != null ? device.getSnmpCommunity() : "public";

                log.info("正在分析设备 {} 的ARP表...", device.getName());
                
                Map<String, String> arpTable = snmpClient.getArpTable(ip, snmpPort, community);
                log.info("  ARP表包含 {} 条记录", arpTable.size());
                
                Map<Integer, String> interfaceMacs = snmpClient.getInterfaceMacTable(ip, snmpPort, community);

                for (Map.Entry<String, String> entry : arpTable.entrySet()) {
                    String neighborIp = entry.getKey();
                    String neighborMac = normalizeMac(entry.getValue());

                    log.debug("  ARP条目: {} -> {}", neighborIp, neighborMac);

                    // 尝试通过IP查找邻居设备
                    Device neighborDevice = deviceByIp.get(neighborIp);
                    
                    // 如果IP没找到，尝试通过MAC查找
                    if (neighborDevice == null && neighborMac != null) {
                        neighborDevice = deviceByMac.get(neighborMac);
                    }

                    if (neighborDevice != null && !neighborDevice.getId().equals(device.getId())) {
                        String linkKey = createLinkKey(device.getId(), neighborDevice.getId());
                        
                        if (!existingLinks.contains(linkKey)) {
                            if (!linkExistsBetween(device.getId(), neighborDevice.getId())) {
                                TopologyLink link = new TopologyLink();
                                link.setSourceNodeId(device.getId());
                                link.setTargetNodeId(neighborDevice.getId());
                                link.setSourcePort(extractPortFromMac(entry.getValue(), interfaceMacs));
                                link.setBandwidth("1Gbps");
                                link.setStatus("up");
                                topologyLinkRepository.save(link);
                                existingLinks.add(linkKey);
                                discoveredLinks++;

                                log.info("  ✓ ARP发现连接: {} ({}) -> {} ({})", 
                                        device.getName(), device.getIpAddress(),
                                        neighborDevice.getName(), neighborDevice.getIpAddress());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("查询设备 {} ARP表失败: {}", device.getIpAddress(), e.getMessage());
            }
        }

        log.info("ARP+MAC拓扑发现完成: 发现 {} 条连接", discoveredLinks);
        
        // 如果没有发现连接，提供友好的提示
        if (discoveredLinks == 0) {
            log.warn("未发现任何拓扑连接，请检查以下内容：");
            log.warn("1. 设备是否正确配置SNMP？");
            log.warn("2. 设备是否在同一个网络中且相互通信过？");
            log.warn("3. 是否可以尝试手动添加连接？");
        }
        
        return discoveredLinks;
    }
    
    private String normalizeMac(String mac) {
        if (mac == null || mac.isEmpty()) {
            return null;
        }
        // 统一MAC格式为大写，不带分隔符或统一分隔符
        String cleaned = mac.toUpperCase().replaceAll("[^A-F0-9]", "");
        if (cleaned.length() != 12) {
            return null; // invalid MAC length
        }
        // 格式化为XX:XX:XX:XX:XX:XX
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) formatted.append(":");
            formatted.append(cleaned.substring(i, i + 2));
        }
        return formatted.toString();
    }

    private String extractPortFromMac(String mac, Map<Integer, String> interfaceMacs) {
        for (Map.Entry<Integer, String> entry : interfaceMacs.entrySet()) {
            if (entry.getValue() != null && entry.getValue().equals(mac)) {
                return "Port-" + entry.getKey();
            }
        }
        return "unknown";
    }

    public Map<String, Object> discoverDeviceNeighbors(Long deviceId) {
        Map<String, Object> result = new HashMap<>();
        
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            result.put("error", "设备不存在");
            return result;
        }
        if (!Boolean.TRUE.equals(deviceCapabilityService.resolveCapabilities(device).get("topologyDiscover"))) {
            result.put("error", "该设备类型不支持邻居发现（如虚拟 PC）");
            result.put("neighbors", List.of());
            return result;
        }
        
        String ip = device.getIpAddress();
        int port = device.getSnmpPort() != null ? device.getSnmpPort() : 161;
        String community = device.getSnmpCommunity() != null ? device.getSnmpCommunity() : "public";
        
        List<Map<String, Object>> neighbors = new ArrayList<>();
        
        List<Map<String, String>> lldpNeighbors = snmpClient.discoverLLDPNeighbors(ip, port, community);
        for (Map<String, String> neighbor : lldpNeighbors) {
            Map<String, Object> neighborInfo = parseNeighbor(neighbor, "LLDP");
            neighbors.add(neighborInfo);
        }
        
        List<Map<String, String>> cdpNeighbors = snmpClient.discoverCDPNeighbors(ip, port, community);
        for (Map<String, String> neighbor : cdpNeighbors) {
            Map<String, Object> neighborInfo = parseNeighbor(neighbor, "CDP");
            neighbors.add(neighborInfo);
        }
        
        List<Map<String, String>> hwLldpNeighbors = snmpClient.discoverHuaweiLLDPNeighbors(ip, port, community);
        for (Map<String, String> neighbor : hwLldpNeighbors) {
            Map<String, Object> neighborInfo = parseNeighbor(neighbor, "华为LLDP");
            neighbors.add(neighborInfo);
        }

        // 设备不暴露LLDP MIB：改从CLI（display lldp neighbor brief）采集，与全量发现保持一致
        for (Map<String, String> neighbor : discoverLldpNeighborsViaSsh(device)) {
            Map<String, String> normalized = new HashMap<>(neighbor);
            normalized.putIfAbsent("portId", neighbor.get("localPort"));
            neighbors.add(parseNeighbor(normalized, "SSH-LLDP"));
        }

        // ARP补充：仅在完全没有LLDP邻居时兜底
        if (!neighbors.isEmpty()) {
            result.put("device", device.getName());
            result.put("ip", ip);
            result.put("neighbors", neighbors);
            result.put("totalNeighbors", neighbors.size());
            return result;
        }
        try {
            Map<String, String> arp = snmpClient.getArpTable(ip, port, community);
            for (Map.Entry<String, String> e : arp.entrySet()) {
                String nip = e.getKey();
                if (nip == null || nip.isBlank()) continue;
                Device nd = deviceRepository.findByIpAddress(nip).orElse(null);
                if (nd == null || nd.getId().equals(device.getId())) continue;
                boolean already = neighbors.stream().anyMatch(n ->
                        nip.equals(String.valueOf(n.get("ip")))
                                || (n.get("deviceId") != null && nd.getId().equals(n.get("deviceId"))));
                if (already) continue;
                Map<String, Object> info = new HashMap<>();
                info.put("protocol", "ARP");
                info.put("ip", nip);
                info.put("chassisId", e.getValue());
                info.put("deviceId", nd.getId());
                info.put("deviceName", nd.getName());
                info.put("deviceStatus", nd.getStatus());
                neighbors.add(info);
            }
        } catch (Exception e) {
            log.debug("单设备 ARP 邻居补充失败 {}: {}", ip, e.getMessage());
        }

        result.put("device", device.getName());
        result.put("ip", ip);
        result.put("neighbors", neighbors);
        result.put("totalNeighbors", neighbors.size());
        
        return result;
    }

    private Map<String, Object> parseNeighbor(Map<String, String> neighbor, String protocol) {
        Map<String, Object> info = new HashMap<>();
        info.put("protocol", protocol);
        info.put("chassisId", neighbor.get("chassisId"));
        info.put("portId", neighbor.get("portId"));
        info.put("portDescription", neighbor.get("portDescription"));
        info.put("sysName", neighbor.get("sysName"));
        info.put("managementAddress", neighbor.get("managementAddress"));
        
        String ip = extractNeighborIp(neighbor, Map.of());
        info.put("ip", ip);
        
        if (ip != null && !ip.isEmpty()) {
            Device device = deviceRepository.findByIpAddress(ip).orElse(null);
            if (device != null) {
                info.put("deviceId", device.getId());
                info.put("deviceName", device.getName());
                info.put("deviceStatus", device.getStatus());
            }
        }
        if (info.get("deviceId") == null) {
            String sysName = neighbor.get("sysName");
            if (sysName != null && !sysName.isBlank()) {
                Device byName = findDeviceBySysName(sysName, deviceRepository.findAll());
                if (byName != null) {
                    info.put("deviceId", byName.getId());
                    info.put("deviceName", byName.getName());
                    info.put("deviceStatus", byName.getStatus());
                    if (info.get("ip") == null) {
                        info.put("ip", byName.getIpAddress());
                    }
                }
            }
        }
        
        return info;
    }

    private String extractNeighborIp(Map<String, String> neighbor, Map<String, String> macToIp) {
        String mgmtAddr = neighbor.get("managementAddress");
        if (mgmtAddr != null && !mgmtAddr.isEmpty()) {
            String ip = extractIpFromString(mgmtAddr);
            if (ip != null) {
                log.debug("从managementAddress提取IP: {}", ip);
                return ip;
            }
        }
        
        String chassisId = neighbor.get("chassisId");
        if (chassisId != null && !chassisId.isEmpty()) {
            String ip = extractIpFromString(chassisId);
            if (ip != null) {
                log.debug("从chassisId提取IP: {}", ip);
                return ip;
            }
            
            String macBasedIp = resolveMacToIp(chassisId, macToIp);
            if (macBasedIp != null) {
                log.debug("从MAC地址解析IP: {}", macBasedIp);
                return macBasedIp;
            }
        }
        
        String sysName = neighbor.get("sysName");
        if (sysName != null && !sysName.isEmpty()) {
            String ip = extractIpFromString(sysName);
            if (ip != null) {
                log.debug("从sysName提取IP: {}", ip);
                return ip;
            }
        }
        
        return null;
    }
    
    private String extractIpFromString(String str) {
        if (str == null || str.isEmpty()) {
            return null;
        }
        
        String[] parts = str.split("[.\\s/]");
        if (parts.length == 4) {
            try {
                int count = 0;
                for (String part : parts) {
                    int num = Integer.parseInt(part.trim());
                    if (num >= 0 && num <= 255) {
                        count++;
                    }
                }
                if (count == 4) {
                    return str.trim();
                }
            } catch (NumberFormatException ignored) {
            }
        }
        
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "\\b((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b"
        );
        java.util.regex.Matcher matcher = pattern.matcher(str);
        if (matcher.find()) {
            return matcher.group();
        }
        
        return null;
    }
    
    private Map<String, String> buildMacToIpCache(List<Device> devices) {
        Map<String, String> cache = new HashMap<>();
        for (Device device : devices) {
            if (!"online".equals(device.getStatus())) continue;
            if (!Boolean.TRUE.equals(deviceCapabilityService.resolveCapabilities(device).get("topologyDiscover"))) {
                continue;
            }
            try {
                String ip = device.getIpAddress();
                int snmpPort = device.getSnmpPort() != null ? device.getSnmpPort() : 161;
                String community = device.getSnmpCommunity() != null ? device.getSnmpCommunity() : "public";
                Map<String, String> arp = snmpClient.getArpTable(ip, snmpPort, community);
                for (Map.Entry<String, String> e : arp.entrySet()) {
                    String mac = normalizeMac(e.getValue());
                    if (mac != null && e.getKey() != null && !e.getKey().isBlank()) {
                        cache.putIfAbsent(mac, e.getKey().trim());
                    }
                }
                Map<Integer, String> ifMacs = snmpClient.getInterfaceMacTable(ip, snmpPort, community);
                for (String macRaw : ifMacs.values()) {
                    String mac = normalizeMac(macRaw);
                    if (mac != null && device.getIpAddress() != null) {
                        cache.putIfAbsent(mac, device.getIpAddress());
                    }
                }
            } catch (Exception e) {
                log.debug("预热 MAC 缓存失败 {}: {}", device.getIpAddress(), e.getMessage());
            }
        }
        log.info("MAC→IP 缓存已加载 {} 条", cache.size());
        return cache;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank() && !"unknown".equalsIgnoreCase(v.trim())) {
                return v.trim();
            }
        }
        return null;
    }

    private String resolveMacToIp(String mac, Map<String, String> macToIp) {
        String normalized = normalizeMac(mac);
        if (normalized == null || macToIp == null) return null;
        return macToIp.get(normalized);
    }
    
    private Device findDeviceBySysName(String sysName, List<Device> allDevices) {
        if (sysName == null || sysName.isBlank()) return null;
        String needle = sysName.trim();
        // 去掉域名后缀，便于主机名匹配
        int dot = needle.indexOf('.');
        String shortName = dot > 0 ? needle.substring(0, dot) : needle;

        for (Device device : allDevices) {
            if (device.getName() == null) continue;
            String name = device.getName().trim();
            if (name.equalsIgnoreCase(needle) || name.equalsIgnoreCase(shortName)) {
                log.info("  找到匹配的设备: {} (sysName={})", device.getName(), sysName);
                return device;
            }
        }
        for (Device device : allDevices) {
            if (device.getName() == null) continue;
            String name = device.getName().trim();
            if (name.contains(shortName) || shortName.contains(name)
                    || needle.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                log.info("  找到部分匹配的设备: {} (sysName={})", device.getName(), sysName);
                return device;
            }
        }
        // IP作为设备名登记的情况
        for (Device device : allDevices) {
            if (device.getIpAddress() != null && device.getIpAddress().equals(needle)) {
                return device;
            }
        }
        log.warn("  未找到sysName={}的匹配设备", sysName);
        return null;
    }

    private boolean linkExistsBetween(Long deviceA, Long deviceB) {
        return topologyLinkRepository.findBetweenDevices(deviceA, deviceB).isPresent();
    }

    private boolean isSyntheticPort(String port) {
        if (port == null || port.isBlank()) return true;
        String p = port.trim();
        return "unknown".equalsIgnoreCase(p) || p.startsWith("Port-");
    }

    private boolean upgradeSyntheticLink(TopologyLink link, Long currentDeviceId, String localPort, String remotePort) {
        boolean fakeSource = isSyntheticPort(link.getSourcePort());
        boolean fakeTarget = isSyntheticPort(link.getTargetPort());
        if (!fakeSource && !fakeTarget) {
            return false;
        }
        boolean currentIsSource = Objects.equals(link.getSourceNodeId(), currentDeviceId);
        if (fakeSource) {
            link.setSourcePort(currentIsSource ? localPort : remotePort);
        }
        if (fakeTarget) {
            link.setTargetPort(currentIsSource ? remotePort : localPort);
        }
        if ("1Gbps".equals(link.getBandwidth())) {
            link.setBandwidth(null);
        }
        topologyLinkRepository.save(link);
        return true;
    }

    private int purgeSyntheticLinks() {
        try {
            return topologyLinkRepository.deleteSyntheticLinks();
        } catch (Exception e) {
            log.warn("清理合成链路失败: {}", e.getMessage());
            return 0;
        }
    }

    private List<Device> filterDevicesForDiscovery(List<Device> allDevices, Map<String, Object> options) {
        if (options == null || options.get("deviceIds") == null) {
            return allDevices;
        }
        Object raw = options.get("deviceIds");
        if (!(raw instanceof Collection<?> ids) || ids.isEmpty()) {
            return allDevices;
        }
        Set<Long> selected = new HashSet<>();
        for (Object id : ids) {
            if (id instanceof Number number) {
                selected.add(number.longValue());
            } else if (id != null) {
                try {
                    selected.add(Long.parseLong(id.toString()));
                } catch (NumberFormatException ignored) {
                    // skip invalid id
                }
            }
        }
        if (selected.isEmpty()) {
            return allDevices;
        }
        return allDevices.stream()
                .filter(d -> selected.contains(d.getId()))
                .toList();
    }

    private String resolveDiscoveryMethod(Map<String, Object> options) {
        if (options == null || options.get("method") == null) {
            return "both";
        }
        String method = String.valueOf(options.get("method")).trim().toLowerCase();
        return switch (method) {
            case "lldp", "arp", "both" -> method;
            default -> "both";
        };
    }

    private String createLinkKey(Long sourceId, Long targetId) {
        if (sourceId < targetId) {
            return sourceId + "-" + targetId;
        } else {
            return targetId + "-" + sourceId;
        }
    }

    public Map<String, Object> getDiscoveryStatus() {
        Map<String, Object> status = new HashMap<>();
        
        long totalDevices = deviceRepository.count();
        long onlineDevices = deviceRepository.findByStatus("online").size();
        long totalLinks = topologyLinkRepository.count();
        
        status.put("totalDevices", totalDevices);
        status.put("onlineDevices", onlineDevices);
        status.put("totalLinks", totalLinks);
        status.put("status", "ready");
        
        return status;
    }
    
    @Transactional
    public void updateLinkFromLLDP(String sourceDeviceIp, String neighborIp, String localPort, String neighborPort) {
        try {
            Device sourceDevice = deviceRepository.findByIpAddress(sourceDeviceIp).orElse(null);
            Device neighborDevice = deviceRepository.findByIpAddress(neighborIp).orElse(null);
            
            if (sourceDevice == null || neighborDevice == null) {
                log.warn("无法更新LLDP连接: 设备不存在 (源: {}, 邻居: {})", sourceDeviceIp, neighborIp);
                return;
            }
            
            if (sourceDevice.getId().equals(neighborDevice.getId())) {
                log.debug("跳过自身连接");
                return;
            }
            
            if (!linkExistsBetween(sourceDevice.getId(), neighborDevice.getId())) {
                TopologyLink link = new TopologyLink();
                link.setSourceNodeId(sourceDevice.getId());
                link.setTargetNodeId(neighborDevice.getId());
                link.setSourcePort(localPort);
                link.setTargetPort(neighborPort);
                link.setStatus("up");
                topologyLinkRepository.save(link);
                
                log.info("从LLDP Trap更新拓扑连接: {} -> {} (端口: {})", 
                        sourceDevice.getName(), neighborDevice.getName(), localPort);
            }
        } catch (Exception e) {
            log.error("更新LLDP连接失败: {}", e.getMessage(), e);
        }
    }
    
    public Optional<Device> findDeviceByName(String name) {
        List<Device> devices = deviceRepository.findAll();
        for (Device device : devices) {
            if (name.equalsIgnoreCase(device.getName())) {
                return Optional.of(device);
            }
            if (device.getName() != null && device.getName().contains(name)) {
                return Optional.of(device);
            }
        }
        return Optional.empty();
    }
}
