package com.ensp.nms.snmp;

import lombok.extern.slf4j.Slf4j;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SnmpClient {

    private static final int DEFAULT_TIMEOUT = 2000;
    private static final int DEFAULT_RETRIES = 1;

    public Map<String, String> get(String ip, int port, String community, String oid) {
        Map<String, String> result = new HashMap<>();
        Snmp snmp = null;
        try {
            log.debug("创建 UDP 传输层...");
            DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping();
            snmp = new Snmp(transport);
            log.debug("启动传输层监听...");
            transport.listen();
            
            log.debug("创建 SNMP Target: {}:{}/{}", ip, port, community);
            CommunityTarget<UdpAddress> target = createTarget(ip, port, community);
            
            log.debug("创建 PDU，请求 OID: {}", oid);
            PDU pdu = new PDU();
            pdu.add(new VariableBinding(new OID(oid)));
            pdu.setType(PDU.GET);
            
            log.debug("发送 SNMP GET 请求...");
            ResponseEvent<UdpAddress> response = snmp.send(pdu, target);
            
            if (response.getResponse() != null) {
                log.debug("收到响应！PDU: {}", response.getResponse());
                for (VariableBinding vb : response.getResponse().getVariableBindings()) {
                    log.debug("  OID: {} = {}", vb.getOid(), vb.getVariable());
                    result.put(vb.getOid().toString(), vb.getVariable().toString());
                }
            } else {
                log.debug("响应为空！请求超时或设备未响应: {}:{}", ip, oid);
            }
        } catch (IOException e) {
            log.error("SNMP GET 异常: {}", e.getMessage(), e);
        } finally {
            if (snmp != null) {
                try {
                    snmp.close();
                } catch (IOException e) {
                    log.error("关闭 SNMP 时出错: {}", e.getMessage());
                }
            }
        }
        return result;
    }

    public List<Map<String, String>> walk(String ip, int port, String community, String oid) {
        List<Map<String, String>> results = new ArrayList<>();
        Snmp snmp = null;
        try {
            DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping();
            snmp = new Snmp(transport);
            transport.listen();

            CommunityTarget<UdpAddress> target = createTarget(ip, port, community);

            OID targetOid = new OID(oid);
            OID currentOid = new OID(targetOid);

            while (currentOid != null && currentOid.startsWith(targetOid)) {
                PDU pdu = new PDU();
                pdu.add(new VariableBinding(currentOid));
                pdu.setType(PDU.GETNEXT);

                ResponseEvent<UdpAddress> response = snmp.send(pdu, target);
                if (response.getResponse() == null || response.getResponse().getVariableBindings().isEmpty()) {
                    break;
                }

                VariableBinding vb = response.getResponse().get(0);
                currentOid = vb.getOid();

                if (!currentOid.startsWith(targetOid)) {
                    break;
                }

                Map<String, String> item = new HashMap<>();
                item.put(vb.getOid().toString(), vb.getVariable().toString());
                results.add(item);
            }
        } catch (IOException e) {
            log.error("SNMP WALK failed for {}: {}", ip, e.getMessage());
        } finally {
            if (snmp != null) {
                try {
                    snmp.close();
                } catch (IOException e) {
                    log.error("Error closing SNMP", e);
                }
            }
        }
        return results;
    }

    public boolean isReachable(String ip, int port, String community) {
        log.debug("SNMP 探测: {}:{}/{}", ip, port, community);
        try {
            Map<String, String> result = get(ip, port, community, "1.3.6.1.2.1.1.3.0");
            return !result.isEmpty();
        } catch (Exception e) {
            log.debug("SNMP 连接失败: {} - {}", e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    private CommunityTarget<UdpAddress> createTarget(String ip, int port, String community) {
        CommunityTarget<UdpAddress> target = new CommunityTarget<>();
        target.setCommunity(new OctetString(community));
        target.setAddress(new UdpAddress(ip + "/" + port));
        target.setRetries(DEFAULT_RETRIES);
        target.setTimeout(DEFAULT_TIMEOUT);
        target.setVersion(SnmpConstants.version2c);
        return target;
    }

    public Map<String, String> getDeviceInfo(String ip, int port, String community) {
        Map<String, String> info = new HashMap<>();
        
        String sysDescOid = "1.3.6.1.2.1.1.1.0";
        String sysNameOid = "1.3.6.1.2.1.1.5.0";
        String sysObjectIdOid = "1.3.6.1.2.1.1.2.0";
        
        Map<String, String> sysDesc = get(ip, port, community, sysDescOid);
        Map<String, String> sysName = get(ip, port, community, sysNameOid);
        Map<String, String> sysObjectId = get(ip, port, community, sysObjectIdOid);
        
        if (!sysDesc.isEmpty()) {
            info.put("description", sysDesc.values().iterator().next());
        }
        if (!sysName.isEmpty()) {
            info.put("name", sysName.values().iterator().next());
        }
        if (!sysObjectId.isEmpty()) {
            info.put("sysObjectId", sysObjectId.values().iterator().next());
        }
        
        return info;
    }

    public Double getCpuUsage(String ip, int port, String community) {
        return getCpuUsage(ip, port, community, null);
    }

    /** preferredCpuOid 优先尝试（来自 OID 模板），再回退内置链 */
    public Double getCpuUsage(String ip, int port, String community, String preferredCpuOid) {
        java.util.LinkedHashSet<String> cpuOids = new java.util.LinkedHashSet<>();
        if (preferredCpuOid != null && !preferredCpuOid.isBlank()) {
            cpuOids.add(preferredCpuOid.trim());
        }
        cpuOids.add("1.3.6.1.4.1.2011.5.25.31.1.1.1.1.1.5");
        cpuOids.add("1.3.6.1.4.1.2011.5.25.31.1.1.1.5");
        cpuOids.add("1.3.6.1.4.1.2011.5.25.31.1.1.1.6");
        cpuOids.add("1.3.6.1.2.1.25.3.3.1.2");

        for (String cpuOid : cpuOids) {
            try {
                Map<String, String> result = get(ip, port, community, cpuOid + ".0");
                Double parsed = parseCpuValue(result);
                if (parsed != null) {
                    return parsed;
                }

                List<Map<String, String>> results = walk(ip, port, community, cpuOid);
                for (Map<String, String> r : results) {
                    parsed = parseCpuValue(r);
                    if (parsed != null) {
                        return parsed;
                    }
                }
            } catch (Exception e) {
                log.debug("尝试 CPU OID {} 失败: {}", cpuOid, e.getMessage());
            }
        }
        log.warn("CPU OID 未获取到有效数据: {}", ip);
        return null;
    }

    private Double parseCpuValue(Map<String, String> result) {
        if (result == null || result.isEmpty()) {
            return null;
        }
        String value = result.values().iterator().next();
        if ("noSuchObject".equals(value) || "noSuchInstance".equals(value) || value == null) {
            return null;
        }
        try {
            double cpuVal = Double.parseDouble(value.trim());
            if (cpuVal < 0) {
                return null;
            }
            if (cpuVal <= 100) {
                return cpuVal;
            }
            return cpuVal / 10;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Map<String, Long> getMemoryUsage(String ip, int port, String community) {
        return getMemoryUsage(ip, port, community, null, null);
    }

    public Map<String, Long> getMemoryUsage(String ip, int port, String community,
                                           String preferredFreeOid, String preferredTotalOid) {
        Map<String, Long> result = new HashMap<>();

        java.util.List<String[]> memOids = new java.util.ArrayList<>();
        if (preferredFreeOid != null && !preferredFreeOid.isBlank()
                && preferredTotalOid != null && !preferredTotalOid.isBlank()) {
            memOids.add(new String[]{preferredFreeOid.trim(), preferredTotalOid.trim()});
        }
        memOids.add(new String[]{"1.3.6.1.4.1.2011.5.25.31.1.1.1.1.1.7", "1.3.6.1.4.1.2011.5.25.31.1.1.1.1.1.8"});
        memOids.add(new String[]{"1.3.6.1.4.1.2011.5.25.31.1.1.1.7", "1.3.6.1.4.1.2011.5.25.31.1.1.1.8"});
        memOids.add(new String[]{"1.3.6.1.2.1.25.2.3.1.5", "1.3.6.1.2.1.25.2.3.1.6"});

        for (String[] oids : memOids) {
            try {
                Map<String, String> memFree = get(ip, port, community, oids[0] + ".0");
                Map<String, String> memTotal = get(ip, port, community, oids[1] + ".0");

                if (tryParseMemoryPair(memFree, memTotal, result)) {
                    return result;
                }

                List<Map<String, String>> freeResults = walk(ip, port, community, oids[0]);
                List<Map<String, String>> totalResults = walk(ip, port, community, oids[1]);
                if (!freeResults.isEmpty() && !totalResults.isEmpty()
                        && tryParseMemoryPair(freeResults.get(0), totalResults.get(0), result)) {
                    return result;
                }
            } catch (Exception e) {
                log.debug("尝试内存 OID 对 {} 失败: {}", oids[0], e.getMessage());
            }
        }

        log.warn("内存 OID 未获取到有效数据: {}", ip);
        return result;
    }

    private boolean tryParseMemoryPair(Map<String, String> freeMap, Map<String, String> totalMap, Map<String, Long> out) {
        if (freeMap == null || freeMap.isEmpty() || totalMap == null || totalMap.isEmpty()) {
            return false;
        }
        String freeValue = freeMap.values().iterator().next();
        String totalValue = totalMap.values().iterator().next();
        if ("noSuchObject".equals(freeValue) || "noSuchInstance".equals(freeValue)
                || "noSuchObject".equals(totalValue) || "noSuchInstance".equals(totalValue)) {
            return false;
        }
        try {
            out.put("free", Long.parseLong(freeValue.trim()));
            out.put("total", Long.parseLong(totalValue.trim()));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public Map<Integer, PortMetrics> getPortMetrics(String ip, int port, String community) {
        Map<Integer, PortMetrics> result = new HashMap<>();
        try {
            String ifIndexOid = "1.3.6.1.2.1.2.2.1.1";
            String ifDescrOid = "1.3.6.1.2.1.2.2.1.2";
            String ifOperStatusOid = "1.3.6.1.2.1.2.2.1.8";
            String ifInOctetsOid = "1.3.6.1.2.1.2.2.1.10";
            String ifOutOctetsOid = "1.3.6.1.2.1.2.2.1.16";

            List<Map<String, String>> ifIndexes = walk(ip, port, community, ifIndexOid);
            List<Map<String, String>> ifDescrs = walk(ip, port, community, ifDescrOid);
            List<Map<String, String>> ifOperStatuses = walk(ip, port, community, ifOperStatusOid);
            List<Map<String, String>> ifInOctets = walk(ip, port, community, ifInOctetsOid);
            List<Map<String, String>> ifOutOctets = walk(ip, port, community, ifOutOctetsOid);

            Map<Integer, String> descrMap = oidIndexMap(ifDescrs);
            Map<Integer, String> operMap = oidIndexMap(ifOperStatuses);
            Map<Integer, Long> inOctetsMap = oidIndexLongMap(ifInOctets);
            Map<Integer, Long> outOctetsMap = oidIndexLongMap(ifOutOctets);

            for (Map<String, String> m : ifIndexes) {
                for (String idxStr : m.values()) {
                    try {
                        int idx = Integer.parseInt(idxStr);
                        PortMetrics pm = new PortMetrics();
                        pm.index = idx;
                        pm.name = descrMap.get(idx);
                        pm.inOctets = inOctetsMap.get(idx);
                        pm.outOctets = outOctetsMap.get(idx);
                        pm.operStatus = mapOperStatus(operMap.get(idx));
                        result.put(idx, pm);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("获取端口流量失败: {}", e.getMessage());
        }
        return result;
    }

    private Map<Integer, String> oidIndexMap(List<Map<String, String>> rows) {
        Map<Integer, String> map = new HashMap<>();
        for (Map<String, String> m : rows) {
            for (Map.Entry<String, String> e : m.entrySet()) {
                try {
                    int idx = Integer.parseInt(e.getKey().substring(e.getKey().lastIndexOf('.') + 1));
                    map.put(idx, e.getValue());
                } catch (Exception ignored) {}
            }
        }
        return map;
    }

    private Map<Integer, Long> oidIndexLongMap(List<Map<String, String>> rows) {
        Map<Integer, Long> map = new HashMap<>();
        for (Map<String, String> m : rows) {
            for (Map.Entry<String, String> e : m.entrySet()) {
                try {
                    int idx = Integer.parseInt(e.getKey().substring(e.getKey().lastIndexOf('.') + 1));
                    map.put(idx, Long.parseLong(e.getValue()));
                } catch (Exception ignored) {}
            }
        }
        return map;
    }

    private String mapOperStatus(String raw) {
        if (raw == null) {
            return "unknown";
        }
        return switch (raw.trim()) {
            case "1" -> "up";
            case "2" -> "down";
            case "3" -> "testing";
            case "4" -> "unknown";
            case "5" -> "dormant";
            case "6" -> "notPresent";
            case "7" -> "lowerLayerDown";
            default -> raw;
        };
    }

    public static class PortMetrics {
        public int index;
        public String name;
        public Long inOctets;
        public Long outOctets;
        public String operStatus;
    }

    public List<Map<String, String>> discoverLLDPNeighbors(String ip, int port, String community) {
        List<Map<String, String>> neighbors = new ArrayList<>();
        try {
            String lldpRemTableOid = "1.0.8802.1.1.2.1.4.1.1";
            List<Map<String, String>> results = walk(ip, port, community, lldpRemTableOid);
            
            log.info("LLDP WALK返回 {} 个结果", results.size());
            
            int rawResultCount = 0;
            for (Map<String, String> result : results) {
                for (Map.Entry<String, String> entry : result.entrySet()) {
                    if (rawResultCount < 10) {
                        log.info("  LLDP原始数据 [{}]: {} = {}", rawResultCount, entry.getKey(), entry.getValue());
                    }
                    rawResultCount++;
                }
            }
            
            Map<String, Map<String, String>> neighborMap = new HashMap<>();
            
            for (Map<String, String> result : results) {
                for (Map.Entry<String, String> entry : result.entrySet()) {
                    String oid = entry.getKey();
                    String value = entry.getValue();
                    
                    if ("noSuchObject".equals(value) || "noSuchInstance".equals(value)) {
                        continue;
                    }
                    
                    String[] oidParts = oid.split("\\.");
                    
                    String index = "";
                    if (oidParts.length >= 7) {
                        index = oidParts[oidParts.length - 7];
                    } else if (oidParts.length >= 4) {
                        index = oidParts[oidParts.length - 4];
                    } else {
                        continue;
                    }
                    
                    neighborMap.computeIfAbsent(index, k -> new HashMap<>());
                    
                    if (oid.contains("1.0.8802.1.1.2.1.4.1.1.4")) {
                        neighborMap.get(index).put("chassisId", value);
                    } else if (oid.contains("1.0.8802.1.1.2.1.4.1.1.5")) {
                        neighborMap.get(index).put("portId", value);
                    } else if (oid.contains("1.0.8802.1.1.2.1.4.1.1.6")) {
                        neighborMap.get(index).put("portDescription", value);
                    } else if (oid.contains("1.0.8802.1.1.2.1.4.1.1.7")) {
                        neighborMap.get(index).put("sysName", value);
                    } else if (oid.contains("1.0.8802.1.1.2.1.4.1.1.9")) {
                        neighborMap.get(index).put("portIdSubtype", value);
                    } else if (oid.contains("1.0.8802.1.1.2.1.4.1.1.12")) {
                        String parsedIp = parseManagementAddress(value);
                        if (parsedIp != null) {
                            neighborMap.get(index).put("managementAddress", parsedIp);
                            log.info("  解析ManagementAddress成功: {} -> {}", value, parsedIp);
                        } else {
                            neighborMap.get(index).put("managementAddress", value);
                            log.warn("  ManagementAddress无法解析IP: {}", value);
                        }
                    }
                }
            }
            
            neighbors.addAll(neighborMap.values());
            
            log.info("LLDP发现完成: 从 {} 发现了 {} 个邻居", ip, neighbors.size());
            
            for (int i = 0; i < neighbors.size(); i++) {
                log.info("  邻居 [{}]: {}", i, neighbors.get(i));
            }
            
        } catch (Exception e) {
            log.error("LLDP发现失败 {}: {}", ip, e.getMessage());
        }
        return neighbors;
    }

    public List<Map<String, String>> discoverCDPNeighbors(String ip, int port, String community) {
        List<Map<String, String>> neighbors = new ArrayList<>();
        try {
            String cdpCacheTableOid = "1.3.6.1.4.1.9.9.23.1.2.1.1";
            List<Map<String, String>> results = walk(ip, port, community, cdpCacheTableOid);
            
            Map<String, Map<String, String>> neighborMap = new HashMap<>();
            
            for (Map<String, String> result : results) {
                for (Map.Entry<String, String> entry : result.entrySet()) {
                    String oid = entry.getKey();
                    String value = entry.getValue();
                    
                    if ("noSuchObject".equals(value) || "noSuchInstance".equals(value)) {
                        continue;
                    }
                    
                    String[] oidParts = oid.split("\\.");
                    if (oidParts.length < 13) continue;
                    
                    String index = oidParts[oidParts.length - 4] + "." + oidParts[oidParts.length - 3] + "." + 
                                  oidParts[oidParts.length - 2] + "." + oidParts[oidParts.length - 1];
                    
                    neighborMap.computeIfAbsent(index, k -> new HashMap<>());
                    
                    if (oid.contains("1.3.6.1.4.1.9.9.23.1.2.1.1.6")) {
                        neighborMap.get(index).put("deviceId", value);
                    } else if (oid.contains("1.3.6.1.4.1.9.9.23.1.2.1.1.4")) {
                        neighborMap.get(index).put("portId", value);
                    } else if (oid.contains("1.3.6.1.4.1.9.9.23.1.2.1.1.7")) {
                        neighborMap.get(index).put("portDescription", value);
                    } else if (oid.contains("1.3.6.1.4.1.9.9.23.1.2.1.1.8")) {
                        neighborMap.get(index).put("sysName", value);
                    } else if (oid.contains("1.3.6.1.4.1.9.9.23.1.2.1.1.13")) {
                        neighborMap.get(index).put("managementAddress", value);
                    }
                }
            }
            
            neighbors.addAll(neighborMap.values());
            
            log.info("CDP发现完成: 从 {} 发现了 {} 个邻居", ip, neighbors.size());
            
        } catch (Exception e) {
            log.error("CDP发现失败 {}: {}", ip, e.getMessage());
        }
        return neighbors;
    }

    public List<Map<String, String>> discoverHuaweiLLDPNeighbors(String ip, int port, String community) {
        List<Map<String, String>> neighbors = new ArrayList<>();
        try {
            log.info("开始华为私有LLDP MIB查询: {}", ip);
            
            String hwLldpBaseOid = "1.3.6.1.4.1.2011.5.25.134.1.2.5.1";
            String hwLldpNeighborNameOid = hwLldpBaseOid + ".3";
            String hwLldpLocalPortOid = hwLldpBaseOid + ".4";
            String hwLldpRemotePortOid = hwLldpBaseOid + ".5";
            String hwLldpNeighborIPOid = hwLldpBaseOid + ".7";
            
            List<Map<String, String>> neighborNames = walk(ip, port, community, hwLldpNeighborNameOid);
            List<Map<String, String>> localPorts = walk(ip, port, community, hwLldpLocalPortOid);
            List<Map<String, String>> remotePorts = walk(ip, port, community, hwLldpRemotePortOid);
            List<Map<String, String>> neighborIPs = walk(ip, port, community, hwLldpNeighborIPOid);
            
            log.info("华为LLDP邻居名称查询返回 {} 条", neighborNames.size());
            log.info("华为LLDP本地端口查询返回 {} 条", localPorts.size());
            log.info("华为LLDP对端端口查询返回 {} 条", remotePorts.size());
            log.info("华为LLDP邻居IP查询返回 {} 条", neighborIPs.size());
            
            int rawCount = 0;
            for (Map<String, String> r : neighborNames) {
                for (Map.Entry<String, String> e : r.entrySet()) {
                    if (rawCount < 5) {
                        log.info("  华为LLDP邻居名称原始: {} = {}", e.getKey(), e.getValue());
                    }
                    rawCount++;
                }
            }
            
            for (Map<String, String> r : neighborIPs) {
                for (Map.Entry<String, String> e : r.entrySet()) {
                    if (rawCount < 10) {
                        log.info("  华为LLDP邻居IP原始: {} = {}", e.getKey(), e.getValue());
                    }
                    rawCount++;
                }
            }
            
            Map<String, Map<String, String>> neighborMap = new HashMap<>();
            
            for (Map<String, String> result : neighborNames) {
                for (Map.Entry<String, String> entry : result.entrySet()) {
                    String oid = entry.getKey();
                    String value = entry.getValue();
                    
                    if ("noSuchObject".equals(value) || "noSuchInstance".equals(value) || 
                        value == null || value.trim().isEmpty()) {
                        continue;
                    }
                    
                    String index = extractHwIndex(oid);
                    if (index == null || index.isEmpty()) continue;
                    
                    neighborMap.computeIfAbsent(index, k -> new HashMap<>());
                    neighborMap.get(index).put("sysName", value);
                    
                    if (rawCount <= 5) {
                        log.info("  邻居[{}] 名称: {}", index, value);
                    }
                }
            }
            
            for (Map<String, String> result : localPorts) {
                for (Map.Entry<String, String> entry : result.entrySet()) {
                    String oid = entry.getKey();
                    String value = entry.getValue();
                    
                    if ("noSuchObject".equals(value) || "noSuchInstance".equals(value)) continue;
                    
                    String index = extractHwIndex(oid);
                    if (index == null || index.isEmpty()) continue;
                    
                    neighborMap.computeIfAbsent(index, k -> new HashMap<>());
                    neighborMap.get(index).put("portId", value);
                    neighborMap.get(index).put("localPort", value);
                    
                    log.debug("  邻居[{}] 本地端口: {}", index, value);
                }
            }
            
            for (Map<String, String> result : remotePorts) {
                for (Map.Entry<String, String> entry : result.entrySet()) {
                    String oid = entry.getKey();
                    String value = entry.getValue();
                    
                    if ("noSuchObject".equals(value) || "noSuchInstance".equals(value)) continue;
                    
                    String index = extractHwIndex(oid);
                    if (index == null || index.isEmpty()) continue;
                    
                    neighborMap.computeIfAbsent(index, k -> new HashMap<>());
                    neighborMap.get(index).put("remotePort", value);
                    
                    log.debug("  邻居[{}] 对端端口: {}", index, value);
                }
            }
            
            for (Map<String, String> result : neighborIPs) {
                for (Map.Entry<String, String> entry : result.entrySet()) {
                    String oid = entry.getKey();
                    String value = entry.getValue();
                    
                    if ("noSuchObject".equals(value) || "noSuchInstance".equals(value)) continue;
                    
                    String index = extractHwIndex(oid);
                    if (index == null || index.isEmpty()) continue;
                    
                    neighborMap.computeIfAbsent(index, k -> new HashMap<>());
                    
                    String parsedIp = parseManagementAddress(value);
                    if (parsedIp != null) {
                        neighborMap.get(index).put("managementAddress", parsedIp);
                        log.info("  邻居[{}] IP地址: {}", index, parsedIp);
                    } else {
                        neighborMap.get(index).put("managementAddress", value);
                        log.info("  邻居[{}] IP地址(原始): {}", index, value);
                    }
                }
            }
            
            neighbors.addAll(neighborMap.values());
            
            log.info("华为LLDP发现完成: 从 {} 发现了 {} 个邻居", ip, neighbors.size());
            
            for (int i = 0; i < Math.min(5, neighbors.size()); i++) {
                log.info("  华为邻居[{}]: {}", i, neighbors.get(i));
            }
            
        } catch (Exception e) {
            log.error("华为LLDP发现失败 {}: {}", ip, e.getMessage(), e);
        }
        return neighbors;
    }
    
    private String extractHwIndex(String oid) {
        try {
            String[] parts = oid.split("\\.");
            if (parts.length >= 2) {
                return parts[parts.length - 1];
            }
        } catch (Exception e) {
            log.debug("提取华为LLDP索引失败: {}", oid);
        }
        return null;
    }

    private String parseManagementAddress(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        
        log.info("  开始解析ManagementAddress: {}", value);
        
        if (value.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            log.info("  直接匹配IPv4: {}", value);
            return value;
        }
        
        java.util.regex.Pattern ipPattern = java.util.regex.Pattern.compile("\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b");
        java.util.regex.Matcher matcher = ipPattern.matcher(value);
        if (matcher.find()) {
            String foundIp = matcher.group();
            log.info("  从字符串中提取IPv4: {}", foundIp);
            return foundIp;
        }
        
        if (value.contains(":")) {
            return parseHexOrOctets(value);
        }
        
        try {
            String[] octets = value.split("\\.");
            if (octets.length >= 4) {
                try {
                    int a = Integer.parseInt(octets[octets.length - 4].trim());
                    int b = Integer.parseInt(octets[octets.length - 3].trim());
                    int c = Integer.parseInt(octets[octets.length - 2].trim());
                    int d = Integer.parseInt(octets[octets.length - 1].trim());
                    if (a >= 0 && a <= 255 && b >= 0 && b <= 255 && c >= 0 && c <= 255 && d >= 0 && d <= 255) {
                        String ip = a + "." + b + "." + c + "." + d;
                        log.info("  从末尾提取IPv4: {}", ip);
                        return ip;
                    }
                } catch (NumberFormatException e) {
                }
            }
        } catch (Exception e) {
            log.debug("  解析失败: {}", e.getMessage());
        }
        
        return null;
    }
    
    private String parseHexOrOctets(String value) {
        try {
            String clean = value.replaceAll("[^0-9a-fA-F:]", "");
            String[] parts = clean.split(":");
            if (parts.length >= 4) {
                try {
                    int[] octets = new int[4];
                    int start = Math.max(0, parts.length - 4);
                    for (int i = 0; i < 4; i++) {
                        String part = parts[start + i];
                        octets[i] = Integer.parseInt(part, 16);
                    }
                    if (octets[0] >= 0 && octets[0] <= 255 && octets[1] >= 0 && octets[1] <= 255 && 
                        octets[2] >= 0 && octets[2] <= 255 && octets[3] >= 0 && octets[3] <= 255) {
                        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
                    }
                } catch (NumberFormatException e) {
                }
            }
        } catch (Exception e) {
            log.debug("  解析十六进制失败: {}", e.getMessage());
        }
        return null;
    }

    public Map<String, Object> getInterfaceStatus(String ip, int port, String community) {
        Map<String, Object> interfaces = new HashMap<>();
        try {
            String ifIndexOid = "1.3.6.1.2.1.2.2.1.1";
            String ifDescrOid = "1.3.6.1.2.1.2.2.1.2";
            String ifOperStatusOid = "1.3.6.1.2.1.2.2.1.8";
            
            List<Map<String, String>> ifIndexes = walk(ip, port, community, ifIndexOid);
            List<Map<String, String>> ifDescrs = walk(ip, port, community, ifDescrOid);
            List<Map<String, String>> ifOperStatuses = walk(ip, port, community, ifOperStatusOid);
            
            Map<Integer, String> descrMap = new HashMap<>();
            for (Map<String, String> m : ifDescrs) {
                for (Map.Entry<String, String> e : m.entrySet()) {
                    String oid = e.getKey();
                    int idx = Integer.parseInt(oid.substring(oid.lastIndexOf('.') + 1));
                    descrMap.put(idx, e.getValue());
                }
            }
            
            Map<Integer, String> statusMap = new HashMap<>();
            for (Map<String, String> m : ifOperStatuses) {
                for (Map.Entry<String, String> e : m.entrySet()) {
                    String oid = e.getKey();
                    int idx = Integer.parseInt(oid.substring(oid.lastIndexOf('.') + 1));
                    statusMap.put(idx, e.getValue());
                }
            }
            
            for (Map<String, String> m : ifIndexes) {
                for (Map.Entry<String, String> e : m.entrySet()) {
                    try {
                        int idx = Integer.parseInt(e.getValue());
                        if (idx > 0 && descrMap.containsKey(idx)) {
                            Map<String, Object> iface = new HashMap<>();
                            iface.put("index", idx);
                            iface.put("description", descrMap.get(idx));
                            iface.put("status", statusMap.getOrDefault(idx, "unknown"));
                            interfaces.put(idx + "", iface);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            
        } catch (Exception e) {
            log.error("获取接口状态失败 {}: {}", ip, e.getMessage());
        }
        return interfaces;
    }

    public Map<String, String> getArpTable(String ip, int port, String community) {
        Map<String, String> arpTable = new HashMap<>();
        try {
            String arpOid = "1.3.6.1.2.1.3.1";
            List<Map<String, String>> results = walk(ip, port, community, arpOid);
            
            for (Map<String, String> result : results) {
                for (Map.Entry<String, String> entry : result.entrySet()) {
                    String oid = entry.getKey();
                    String value = entry.getValue();
                    
                    if ("noSuchObject".equals(value) || "noSuchInstance".equals(value)) {
                        continue;
                    }
                    
                    String[] oidParts = oid.split("\\.");
                    if (oidParts.length >= 7) {
                        String ipAddress = oidParts[oidParts.length - 4] + "." + 
                                          oidParts[oidParts.length - 3] + "." + 
                                          oidParts[oidParts.length - 2] + "." + 
                                          oidParts[oidParts.length - 1];
                        
                        String macAddress = parseMacAddress(value);
                        if (macAddress != null && ipAddress != null) {
                            arpTable.put(ipAddress, macAddress);
                            log.debug("ARP条目: IP={}, MAC={}", ipAddress, macAddress);
                        }
                    }
                }
            }
            
            log.info("ARP表查询完成: {} 个条目", arpTable.size());
        } catch (Exception e) {
            log.error("获取ARP表失败 {}: {}", ip, e.getMessage());
        }
        return arpTable;
    }

    /**
     * 采集设备本机全部 IP（ipAddrTable），用于把 Trap 接口地址映射到纳管管理 IP。
     * OID: 1.3.6.1.2.1.4.20.1.1 (ipAdEntAddr)
     */
    public List<String> getIpAddrTable(String ip, int port, String community) {
        List<String> ips = new ArrayList<>();
        try {
            String oid = "1.3.6.1.2.1.4.20.1.1";
            List<Map<String, String>> results = walk(ip, port, community, oid);
            for (Map<String, String> result : results) {
                for (Map.Entry<String, String> entry : result.entrySet()) {
                    String value = entry.getValue();
                    if (value == null || "noSuchObject".equals(value) || "noSuchInstance".equals(value)) {
                        continue;
                    }
                    String cleaned = value.trim();
                    // 有的实现返回 IpAddress 文本，有的把地址编进 OID 末尾
                    if (!isLikelyIpv4(cleaned)) {
                        String[] oidParts = entry.getKey().split("\\.");
                        if (oidParts.length >= 4) {
                            cleaned = oidParts[oidParts.length - 4] + "." +
                                    oidParts[oidParts.length - 3] + "." +
                                    oidParts[oidParts.length - 2] + "." +
                                    oidParts[oidParts.length - 1];
                        }
                    }
                    if (isLikelyIpv4(cleaned) && !ips.contains(cleaned)
                            && !"127.0.0.1".equals(cleaned) && !"0.0.0.0".equals(cleaned)) {
                        ips.add(cleaned);
                    }
                }
            }
            log.info("设备 {} ipAddrTable: {} 个地址 {}", ip, ips.size(), ips);
        } catch (Exception e) {
            log.error("获取 ipAddrTable 失败 {}: {}", ip, e.getMessage());
        }
        return ips;
    }

    private boolean isLikelyIpv4(String s) {
        if (s == null || s.isBlank()) return false;
        String[] p = s.split("\\.");
        if (p.length != 4) return false;
        try {
            for (String part : p) {
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public Map<String, Integer> getMacAddressTable(String ip, int port, String community) {
        Map<String, Integer> macTable = new HashMap<>();
        try {
            String[] macOids = {
                "1.3.6.1.2.1.17.4.3.1.1",
                "1.3.6.1.2.1.17.4.3.1.2",
                "1.3.6.1.2.1.17.7.1.2.2.1.2"
            };
            
            for (String macOid : macOids) {
                try {
                    List<Map<String, String>> results = walk(ip, port, community, macOid);
                    
                    if (!results.isEmpty()) {
                        for (Map<String, String> result : results) {
                            for (Map.Entry<String, String> entry : result.entrySet()) {
                                String oid = entry.getKey();
                                String value = entry.getValue();
                                
                                if ("noSuchObject".equals(value) || "noSuchInstance".equals(value)) {
                                    continue;
                                }
                                
                                String macAddress = parseMacAddress(oid);
                                if (macAddress != null) {
                                    String portIndex = extractPortIndex(oid, macOid);
                                    if (portIndex != null) {
                                        try {
                                            int portNum = Integer.parseInt(portIndex);
                                            macTable.put(macAddress, portNum);
                                            log.debug("MAC表条目: MAC={}, Port={}", macAddress, portNum);
                                        } catch (NumberFormatException e) {
                                            log.debug("端口索引解析失败: {}", portIndex);
                                        }
                                    }
                                }
                            }
                        }
                        
                        if (!macTable.isEmpty()) {
                            log.info("MAC地址表查询成功(OID: {}): {} 个条目", macOid, macTable.size());
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.debug("尝试MAC OID {} 失败: {}", macOid, e.getMessage());
                }
            }
            
            if (macTable.isEmpty()) {
                log.warn("所有MAC地址表查询均失败");
            }
        } catch (Exception e) {
            log.error("获取MAC地址表失败 {}: {}", ip, e.getMessage());
        }
        return macTable;
    }

    public Map<Integer, String> getInterfaceMacTable(String ip, int port, String community) {
        Map<Integer, String> interfaceMacs = new HashMap<>();
        try {
            String[] macPortOids = {
                "1.3.6.1.2.1.17.4.3.1.2",
                "1.3.6.1.2.1.17.7.1.2.2.1.2"
            };
            
            for (String oid : macPortOids) {
                try {
                    List<Map<String, String>> results = walk(ip, port, community, oid);
                    
                    for (Map<String, String> result : results) {
                        for (Map.Entry<String, String> entry : result.entrySet()) {
                            String fullOid = entry.getKey();
                            String value = entry.getValue();
                            
                            if ("noSuchObject".equals(value) || "noSuchInstance".equals(value)) {
                                continue;
                            }
                            
                            try {
                                int portIndex = Integer.parseInt(value);
                                String macAddress = parseMacFromOid(fullOid);
                                
                                if (macAddress != null && portIndex > 0) {
                                    interfaceMacs.put(portIndex, macAddress);
                                    log.debug("接口MAC: Port={}, MAC={}", portIndex, macAddress);
                                }
                            } catch (NumberFormatException e) {
                                continue;
                            }
                        }
                    }
                    
                    if (!interfaceMacs.isEmpty()) {
                        log.info("接口MAC表查询成功: {} 个条目", interfaceMacs.size());
                        break;
                    }
                } catch (Exception e) {
                    log.debug("接口MAC表查询失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("获取接口MAC表失败 {}: {}", ip, e.getMessage());
        }
        return interfaceMacs;
    }

    private String parseMacAddress(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        
        if (value.contains("-") && value.length() == 17) {
            return value.toUpperCase();
        }
        
        if (value.contains(":") && value.length() == 17) {
            return value.toUpperCase();
        }
        
        String[] parts = value.split("[.\\s]");
        if (parts.length == 6) {
            try {
                StringBuilder mac = new StringBuilder();
                for (int i = 0; i < 6; i++) {
                    String hex = Integer.toHexString(Integer.parseInt(parts[i].trim()) & 0xFF);
                    if (hex.length() == 1) mac.append("0");
                    mac.append(hex.toUpperCase());
                    if (i < 5) mac.append("-");
                }
                return mac.toString();
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        return null;
    }

    private String parseMacFromOid(String oid) {
        try {
            String[] parts = oid.split("\\.");
            if (parts.length >= 7) {
                StringBuilder mac = new StringBuilder();
                int start = parts.length - 7;
                for (int i = start; i < parts.length; i++) {
                    String hex = Integer.toHexString(Integer.parseInt(parts[i]) & 0xFF);
                    if (hex.length() == 1) mac.append("0");
                    mac.append(hex.toUpperCase());
                    if (i < parts.length - 1) mac.append("-");
                }
                return mac.toString();
            }
        } catch (Exception e) {
            log.debug("解析MAC地址失败: {}", oid);
        }
        return null;
    }

    private String extractPortIndex(String oid, String baseOid) {
        try {
            String oidPart = oid.replace(baseOid + ".", "");
            String[] parts = oidPart.split("\\.");
            if (parts.length > 6) {
                StringBuilder mac = new StringBuilder();
                for (int i = 0; i < 6; i++) {
                    if (i > 0) mac.append(".");
                    mac.append(parts[i]);
                }
                return parts.length > 6 ? parts[6] : null;
            }
        } catch (Exception e) {
            log.debug("提取端口索引失败: {}", oid);
        }
        return null;
    }
}

