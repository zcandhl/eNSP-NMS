package com.ensp.nms.controller;

import com.ensp.nms.entity.TopologyLink;
import com.ensp.nms.entity.TopologyNode;
import com.ensp.nms.service.TopologyDiscoveryService;
import com.ensp.nms.service.TopologyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/topology")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TopologyController {

    private final TopologyService topologyService;
    private final TopologyDiscoveryService topologyDiscoveryService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getFullTopology() {
        return ResponseEntity.ok(topologyService.getFullTopology());
    }

    @PostMapping("/nodes/{deviceId}/position")
    public ResponseEntity<TopologyNode> saveNodePosition(
            @PathVariable Long deviceId,
            @RequestBody Map<String, Integer> position) {
        return ResponseEntity.ok(
                topologyService.saveNodePosition(deviceId, position.get("x"), position.get("y"))
        );
    }

    @PostMapping("/links")
    public ResponseEntity<TopologyLink> createLink(@RequestBody Map<String, Object> linkData) {
        Object sourceIdObj = linkData.get("sourceNodeId");
        Object targetIdObj = linkData.get("targetNodeId");

        if (sourceIdObj == null || targetIdObj == null) {
            throw new RuntimeException("源设备ID或目标设备ID不能为空");
        }

        Long sourceNodeId = Long.valueOf(sourceIdObj.toString());
        Long targetNodeId = Long.valueOf(targetIdObj.toString());
        String sourcePort = (String) linkData.getOrDefault("sourcePort", "");
        String targetPort = (String) linkData.getOrDefault("targetPort", "");
        String bandwidth = (String) linkData.getOrDefault("bandwidth", "");

        return ResponseEntity.ok(
                topologyService.createLink(sourceNodeId, targetNodeId, sourcePort, targetPort, bandwidth)
        );
    }

    @DeleteMapping("/links/{linkId}")
    public ResponseEntity<Void> deleteLink(@PathVariable Long linkId) {
        topologyService.deleteLink(linkId);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/links/{linkId}/status")
    public ResponseEntity<TopologyLink> updateLinkStatus(
            @PathVariable Long linkId,
            @RequestBody Map<String, String> statusData) {
        return ResponseEntity.ok(
                topologyService.updateLinkStatus(linkId, statusData.get("status"))
        );
    }

    @GetMapping("/links")
    public ResponseEntity<List<TopologyLink>> getAllLinks() {
        return ResponseEntity.ok(topologyService.getAllLinks());
    }

    @PutMapping("/links/{linkId}")
    public ResponseEntity<TopologyLink> updateLink(
            @PathVariable Long linkId,
            @RequestBody Map<String, Object> linkData) {
        try {
            String sourcePort = (String) linkData.getOrDefault("sourcePort", "");
            String targetPort = (String) linkData.getOrDefault("targetPort", "");
            String bandwidth = (String) linkData.getOrDefault("bandwidth", "");
            String status = (String) linkData.getOrDefault("status", "up");

            return ResponseEntity.ok(
                    topologyService.updateLink(linkId, sourcePort, targetPort, bandwidth, status)
            );
        } catch (Exception e) {
            throw new RuntimeException("更新连接失败: " + e.getMessage());
        }
    }

    @GetMapping("/devices/{deviceId}/neighbors")
    public ResponseEntity<List<Map<String, Object>>> getDeviceNeighbors(@PathVariable Long deviceId) {
        return ResponseEntity.ok(topologyService.getDeviceNeighbors(deviceId));
    }

    @PostMapping("/discover")
    public ResponseEntity<Map<String, Object>> discoverTopology(
            @RequestBody(required = false) Map<String, Object> options) {
        log.info("开始自动发现网络拓扑...");
        Map<String, Object> result = topologyDiscoveryService.discoverTopology(options);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/discover/{deviceId}")
    public ResponseEntity<Map<String, Object>> discoverDeviceNeighbors(@PathVariable Long deviceId) {
        return ResponseEntity.ok(topologyDiscoveryService.discoverDeviceNeighbors(deviceId));
    }

    @GetMapping("/discover/status")
    public ResponseEntity<Map<String, Object>> getDiscoveryStatus() {
        return ResponseEntity.ok(topologyDiscoveryService.getDiscoveryStatus());
    }
}
