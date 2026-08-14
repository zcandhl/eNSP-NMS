package com.ensp.nms.service;

import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.TopologyLink;
import com.ensp.nms.entity.TopologyNode;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.TopologyLinkRepository;
import com.ensp.nms.repository.TopologyNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TopologyService {

    private final TopologyNodeRepository topologyNodeRepository;
    private final TopologyLinkRepository topologyLinkRepository;
    private final DeviceRepository deviceRepository;
    private final AlarmRepository alarmRepository;
    private final DeviceCapabilityService deviceCapabilityService;

    private static final List<Alarm.Status> ACTIVE_ALARM_STATUSES =
            List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED);

    public Map<String, Object> getFullTopology() {
        Map<String, Object> result = new HashMap<>();
        
        List<Device> devices = deviceRepository.findAll();
        List<TopologyNode> topologyNodes = topologyNodeRepository.findAll();
        List<TopologyLink> topologyLinks = topologyLinkRepository.findAll();
        
        // 同一 device_id 若有多条位置记录（优化误写造成），保留最新一条，避免 toMap 抛错拖垮拓扑接口
        Map<Long, TopologyNode> nodeMap = topologyNodes.stream()
                .collect(Collectors.toMap(
                        TopologyNode::getDeviceId,
                        n -> n,
                        (a, b) -> {
                            if (a.getId() == null) return b;
                            if (b.getId() == null) return a;
                            return a.getId() >= b.getId() ? a : b;
                        }
                ));
        
        List<Map<String, Object>> nodes = devices.stream().map(device -> {
            deviceCapabilityService.enrich(device);
            Map<String, Object> node = new HashMap<>();
            node.put("id", device.getId());
            node.put("name", device.getName());
            node.put("ipAddress", device.getIpAddress());
            node.put("status", device.getStatus());
            node.put("model", device.getModel());
            node.put("vendor", device.getVendor());
            node.put("description", device.getDescription());
            node.put("lastSeen", device.getLastSeen());
            node.put("deviceType", device.getDeviceType());
            node.put("monitorMode", device.getMonitorMode());
            node.put("lastProbeMethod", device.getLastProbeMethod());
            node.put("capabilities", device.getCapabilities());
            
            TopologyNode topoNode = nodeMap.get(device.getId());
            if (topoNode != null) {
                node.put("x", topoNode.getX());
                node.put("y", topoNode.getY());
                node.put("iconType", topoNode.getIconType() != null ? topoNode.getIconType() : device.getDeviceType());
            } else {
                node.put("iconType", device.getDeviceType());
            }
            
            return node;
        }).collect(Collectors.toList());

        Map<String, Object> alertSummary = buildAlertSummary(devices);
        applyAlertSummaryToNodes(nodes, alertSummary);
        
        List<Map<String, Object>> links = topologyLinks.stream().map(link -> {
            Map<String, Object> linkMap = new HashMap<>();
            linkMap.put("id", link.getId());
            linkMap.put("sourceNodeId", link.getSourceNodeId());
            linkMap.put("targetNodeId", link.getTargetNodeId());
            linkMap.put("sourcePort", link.getSourcePort());
            linkMap.put("targetPort", link.getTargetPort());
            linkMap.put("status", link.getStatus());
            linkMap.put("bandwidth", link.getBandwidth());
            return linkMap;
        }).collect(Collectors.toList());
        
        result.put("nodes", nodes);
        result.put("links", links);
        result.put("alertSummary", alertSummary);
        
        return result;
    }

    private Map<String, Object> buildAlertSummary(List<Device> devices) {
        Map<String, Map<String, Object>> byDevice = new HashMap<>();
        Map<String, Map<String, Object>> byIp = new HashMap<>();
        Set<String> severeDeviceIds = new HashSet<>();
        Set<String> severeIps = new HashSet<>();

        for (Object[] row : alarmRepository.countActiveAlarmsGroupByDeviceId(ACTIVE_ALARM_STATUSES)) {
            if (row[0] == null || row[1] == null) continue;
            String deviceId = String.valueOf(row[0]);
            long count = ((Number) row[1]).longValue();
            byDevice.computeIfAbsent(deviceId, k -> new HashMap<>()).put("count", count);
        }
        for (Object[] row : alarmRepository.countSevereActiveAlarmsGroupByDeviceId(ACTIVE_ALARM_STATUSES)) {
            if (row[0] == null) continue;
            severeDeviceIds.add(String.valueOf(row[0]));
        }
        for (Object[] row : alarmRepository.countActiveAlarmsGroupByDeviceIp(ACTIVE_ALARM_STATUSES)) {
            if (row[0] == null || row[1] == null) continue;
            String ip = String.valueOf(row[0]);
            long count = ((Number) row[1]).longValue();
            byIp.computeIfAbsent(ip, k -> new HashMap<>()).put("count", count);
        }
        for (Object[] row : alarmRepository.countSevereActiveAlarmsGroupByDeviceIp(ACTIVE_ALARM_STATUSES)) {
            if (row[0] == null) continue;
            severeIps.add(String.valueOf(row[0]));
        }

        severeDeviceIds.forEach(id ->
                byDevice.computeIfAbsent(id, k -> new HashMap<>()).put("severe", true));
        severeIps.forEach(ip ->
                byIp.computeIfAbsent(ip, k -> new HashMap<>()).put("severe", true));

        long total = 0;
        for (Map<String, Object> entry : byDevice.values()) {
            Object count = entry.get("count");
            if (count instanceof Number) {
                total += ((Number) count).longValue();
            }
        }
        for (Map<String, Object> entry : byIp.values()) {
            Object count = entry.get("count");
            if (count instanceof Number) {
                total += ((Number) count).longValue();
            }
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("total", total);
        summary.put("byDevice", byDevice);
        summary.put("byIp", byIp);
        return summary;
    }

    private void applyAlertSummaryToNodes(List<Map<String, Object>> nodes, Map<String, Object> alertSummary) {
        if (alertSummary == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> byDevice =
                (Map<String, Map<String, Object>>) alertSummary.getOrDefault("byDevice", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> byIp =
                (Map<String, Map<String, Object>>) alertSummary.getOrDefault("byIp", Map.of());

        for (Map<String, Object> node : nodes) {
            String deviceId = String.valueOf(node.get("id"));
            String ip = node.get("ipAddress") != null ? String.valueOf(node.get("ipAddress")) : "";
            Map<String, Object> fromDevice = byDevice.get(deviceId);
            Map<String, Object> fromIp = ip.isEmpty() ? null : byIp.get(ip);

            long count = 0;
            boolean severe = false;
            if (fromDevice != null) {
                Object c = fromDevice.get("count");
                if (c instanceof Number) count += ((Number) c).longValue();
                severe = Boolean.TRUE.equals(fromDevice.get("severe"));
            }
            // byIp 仅含 device_id IS NULL 的孤儿告警；IP 做规范化后再匹配
            if (fromIp == null && !ip.isEmpty()) {
                String nip = AlarmService.normalizeDeviceIp(ip);
                if (nip != null && !nip.isEmpty() && !nip.equals(ip)) {
                    fromIp = byIp.get(nip);
                }
            }
            if (fromIp != null) {
                Object c = fromIp.get("count");
                if (c instanceof Number) count += ((Number) c).longValue();
                severe = severe || Boolean.TRUE.equals(fromIp.get("severe"));
            }
            node.put("alertCount", count);
            node.put("alertSevere", severe);
        }
    }

    @Transactional
    public TopologyNode saveNodePosition(Long deviceId, Integer x, Integer y) {
        // 优化残留可能导致同一 device_id 多条记录，findByDeviceId 会抛 IncorrectResultSize
        List<TopologyNode> existingNodes = topologyNodeRepository.findAllByDeviceId(deviceId);

        TopologyNode node;
        if (!existingNodes.isEmpty()) {
            existingNodes.sort(Comparator.comparing(TopologyNode::getId, Comparator.nullsLast(Long::compareTo)));
            node = existingNodes.get(existingNodes.size() - 1);
            node.setX(x);
            node.setY(y);
            // 清理多余重复行，恢复单条位置记录
            for (int i = 0; i < existingNodes.size() - 1; i++) {
                topologyNodeRepository.delete(existingNodes.get(i));
            }
        } else {
            node = new TopologyNode();
            node.setDeviceId(deviceId);
            node.setX(x);
            node.setY(y);
        }

        return topologyNodeRepository.save(node);
    }

    @Transactional
    public TopologyLink createLink(Long sourceNodeId, Long targetNodeId, 
                                   String sourcePort, String targetPort, String bandwidth) {
        log.debug("createLink source={} target={}", sourceNodeId, targetNodeId);
        
        // 检查源设备和目标设备是否存在
        if (!deviceRepository.existsById(sourceNodeId)) {
            throw new RuntimeException("源设备不存在: " + sourceNodeId);
        }
        if (!deviceRepository.existsById(targetNodeId)) {
            throw new RuntimeException("目标设备不存在: " + targetNodeId);
        }
        
        if (topologyLinkRepository.findBetweenDevices(sourceNodeId, targetNodeId).isPresent()) {
            throw new RuntimeException("连接已存在");
        }
        
        TopologyLink link = new TopologyLink();
        link.setSourceNodeId(sourceNodeId);
        link.setTargetNodeId(targetNodeId);
        link.setSourcePort(sourcePort);
        link.setTargetPort(targetPort);
        link.setBandwidth(bandwidth);
        link.setStatus("up");
        
        TopologyLink savedLink = topologyLinkRepository.save(link);
        log.info("链路创建成功: id={} {} -> {}", savedLink.getId(), sourceNodeId, targetNodeId);
        
        return savedLink;
    }

    @Transactional
    public void deleteLink(Long linkId) {
        topologyLinkRepository.deleteById(linkId);
    }

    @Transactional
    public TopologyLink updateLinkStatus(Long linkId, String status) {
        Optional<TopologyLink> linkOpt = topologyLinkRepository.findById(linkId);
        if (linkOpt.isEmpty()) {
            throw new RuntimeException("连接不存在");
        }

        TopologyLink link = linkOpt.get();
        link.setStatus(status);
        return topologyLinkRepository.save(link);
    }

    public List<TopologyLink> getAllLinks() {
        return topologyLinkRepository.findAll();
    }

    @Transactional
    public TopologyLink updateLink(Long linkId, String sourcePort, String targetPort, String bandwidth, String status) {
        Optional<TopologyLink> linkOpt = topologyLinkRepository.findById(linkId);
        if (linkOpt.isEmpty()) {
            throw new RuntimeException("连接不存在");
        }

        TopologyLink link = linkOpt.get();
        link.setSourcePort(sourcePort);
        link.setTargetPort(targetPort);
        link.setBandwidth(bandwidth);
        link.setStatus(status);
        return topologyLinkRepository.save(link);
    }

    public List<Map<String, Object>> getDeviceNeighbors(Long deviceId) {
        List<TopologyLink> links = topologyLinkRepository.findBySourceNodeId(deviceId);
        links.addAll(topologyLinkRepository.findByTargetNodeId(deviceId));
        
        Set<Long> neighborIds = new HashSet<>();
        for (TopologyLink link : links) {
            if (link.getSourceNodeId().equals(deviceId)) {
                neighborIds.add(link.getTargetNodeId());
            } else {
                neighborIds.add(link.getSourceNodeId());
            }
        }
        
        return deviceRepository.findAllById(neighborIds).stream()
                .map(device -> {
                    Map<String, Object> neighbor = new HashMap<>();
                    neighbor.put("id", device.getId());
                    neighbor.put("name", device.getName());
                    neighbor.put("ip", device.getIpAddress());
                    neighbor.put("status", device.getStatus());
                    return neighbor;
                })
                .collect(Collectors.toList());
    }

    /**
     * BFS 最短路径提示（只读计算，不改拓扑）。
     */
    public Map<String, Object> findShortestPath(Long fromDeviceId, Long toDeviceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fromDeviceId", fromDeviceId);
        out.put("toDeviceId", toDeviceId);
        if (fromDeviceId == null || toDeviceId == null) {
            out.put("ok", false);
            out.put("error", "缺少起点或终点设备");
            return out;
        }
        if (Objects.equals(fromDeviceId, toDeviceId)) {
            out.put("ok", true);
            out.put("hops", 0);
            out.put("pathDeviceIds", List.of(fromDeviceId));
            out.put("message", "起点与终点相同");
            return out;
        }

        Map<Long, List<Long>> adj = new HashMap<>();
        for (TopologyLink link : topologyLinkRepository.findAll()) {
            Long s = link.getSourceNodeId();
            Long t = link.getTargetNodeId();
            if (s == null || t == null) {
                continue;
            }
            adj.computeIfAbsent(s, k -> new ArrayList<>()).add(t);
            adj.computeIfAbsent(t, k -> new ArrayList<>()).add(s);
        }

        Queue<Long> q = new ArrayDeque<>();
        Map<Long, Long> prev = new HashMap<>();
        q.add(fromDeviceId);
        prev.put(fromDeviceId, null);
        boolean found = false;
        while (!q.isEmpty()) {
            Long cur = q.poll();
            if (Objects.equals(cur, toDeviceId)) {
                found = true;
                break;
            }
            for (Long nb : adj.getOrDefault(cur, List.of())) {
                if (!prev.containsKey(nb)) {
                    prev.put(nb, cur);
                    q.add(nb);
                }
            }
        }

        if (!found) {
            out.put("ok", false);
            out.put("error", "拓扑中未找到连通路径");
            out.put("neighborCountFrom", adj.getOrDefault(fromDeviceId, List.of()).size());
            return out;
        }

        List<Long> path = new ArrayList<>();
        for (Long at = toDeviceId; at != null; at = prev.get(at)) {
            path.add(at);
        }
        Collections.reverse(path);

        Map<Long, Device> deviceMap = deviceRepository.findAllById(path).stream()
                .collect(Collectors.toMap(Device::getId, d -> d, (a, b) -> a, LinkedHashMap::new));
        List<Map<String, Object>> ordered = new ArrayList<>();
        for (Long id : path) {
            Device d = deviceMap.get(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            if (d != null) {
                m.put("name", d.getName());
                m.put("ip", d.getIpAddress());
                m.put("status", d.getStatus());
            }
            ordered.add(m);
        }

        out.put("ok", true);
        out.put("hops", Math.max(0, path.size() - 1));
        out.put("pathDeviceIds", path);
        out.put("nodes", ordered);
        out.put("message", "最短路径 " + (path.size() - 1) + " 跳，共 " + path.size() + " 个节点");
        return out;
    }
}
