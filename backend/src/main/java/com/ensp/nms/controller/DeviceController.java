package com.ensp.nms.controller;

import com.ensp.nms.dto.DiscoverCandidate;
import com.ensp.nms.dto.DiscoverJobStatus;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.DevicePort;
import com.ensp.nms.security.SecurityUtils;
import com.ensp.nms.service.AuditLogService;
import com.ensp.nms.service.DeviceDiscoveryService;
import com.ensp.nms.service.DeviceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DeviceController {

    private static final int DEVICE_LIST_WARN_THRESHOLD = 500;
    private static final int MAX_PAGE_SIZE = 200;

    private final DeviceService deviceService;
    private final DeviceDiscoveryService deviceDiscoveryService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ResponseEntity<List<Device>> getAllDevices() {
        List<Device> devices = deviceService.getAllDevices();
        if (devices.size() > DEVICE_LIST_WARN_THRESHOLD) {
            log.warn("GET /api/devices 返回 {} 台设备（超过 {}），考虑使用 /api/devices/query",
                    devices.size(), DEVICE_LIST_WARN_THRESHOLD);
        }
        return ResponseEntity.ok(devices);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> deviceStats() {
        return ResponseEntity.ok(deviceService.getDeviceStats());
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportDevices(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String monitorMode,
            @RequestParam(required = false) String vendor) {
        List<Device> list = deviceService.listForExport(
                keyword, status, groupId, deviceType, monitorMode, vendor);
        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff');
        sb.append("ID,名称,IP,类型,状态,型号,厂商,位置,联系人,序列号,分组ID,监控方式,探测方式,最后在线,描述\n");
        for (Device d : list) {
            sb.append(csv(d.getId())).append(',')
                    .append(csv(d.getName())).append(',')
                    .append(csv(d.getIpAddress())).append(',')
                    .append(csv(d.getDeviceType())).append(',')
                    .append(csv(d.getStatus())).append(',')
                    .append(csv(d.getModel())).append(',')
                    .append(csv(d.getVendor())).append(',')
                    .append(csv(d.getLocation())).append(',')
                    .append(csv(d.getContact())).append(',')
                    .append(csv(d.getSerialNumber())).append(',')
                    .append(csv(d.getGroupId())).append(',')
                    .append(csv(d.getMonitorMode())).append(',')
                    .append(csv(d.getLastProbeMethod())).append(',')
                    .append(csv(d.getLastSeen())).append(',')
                    .append(csv(d.getDescription()))
                    .append('\n');
        }
        byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=devices-export.csv")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(bytes);
    }

    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    @PostMapping("/{id}/connectivity-test")
    public ResponseEntity<?> connectivityTest(@PathVariable Long id, Authentication authentication) {
        try {
            Map<String, Object> result = deviceService.testConnectivity(id);
            auditDevice("connectivity_test", id,
                    String.valueOf(result.getOrDefault("deviceName", id)),
                    "success",
                    "连通性三联测 " + result.getOrDefault("summary", ""),
                    authentication);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/query")
    public ResponseEntity<Page<Device>> queryDevices(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String monitorMode,
            @RequestParam(required = false) String vendor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id,asc") String sort) {
        Pageable pageable = buildPageable(page, size, sort);
        return ResponseEntity.ok(deviceService.queryDevices(
                keyword, status, groupId, deviceType, monitorMode, vendor, pageable));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/batch/refresh")
    public ResponseEntity<?> batchRefresh(@RequestBody Map<String, Object> body, Authentication authentication) {
        List<Long> ids = parseIdList(body != null ? body.get("ids") : null);
        Map<String, Object> result = deviceService.batchRefresh(ids);
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("device")
                .action("batch_refresh")
                .operator(SecurityUtils.resolveOperator(authentication))
                .targetType("device")
                .status("success")
                .summary("批量刷新设备 " + result.get("success") + "/" + result.get("requested"))
                .clientIp(AuditLogService.currentClientIp())
                .build());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/batch/delete")
    public ResponseEntity<?> batchDelete(@RequestBody Map<String, Object> body, Authentication authentication) {
        List<Long> ids = parseIdList(body != null ? body.get("ids") : null);
        Map<String, Object> result = deviceService.batchDelete(ids);
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("device")
                .action("batch_delete")
                .operator(SecurityUtils.resolveOperator(authentication))
                .targetType("device")
                .status("success")
                .summary("批量删除设备 " + result.get("success") + "/" + result.get("requested"))
                .clientIp(AuditLogService.currentClientIp())
                .build());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/batch/group")
    public ResponseEntity<?> batchGroup(@RequestBody Map<String, Object> body, Authentication authentication) {
        List<Long> ids = parseIdList(body != null ? body.get("ids") : null);
        Long groupId = null;
        if (body != null && body.get("groupId") != null && !"".equals(String.valueOf(body.get("groupId")))) {
            groupId = Long.parseLong(String.valueOf(body.get("groupId")));
            if (groupId < 0) groupId = null;
        }
        Map<String, Object> result = deviceService.batchUpdateGroup(ids, groupId);
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("device")
                .action("batch_group")
                .operator(SecurityUtils.resolveOperator(authentication))
                .targetType("device")
                .status("success")
                .summary("批量调整分组 " + result.get("updated") + " 台")
                .clientIp(AuditLogService.currentClientIp())
                .build());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/batch/credentials")
    public ResponseEntity<?> batchCredentials(@RequestBody Map<String, Object> body, Authentication authentication) {
        List<Long> ids = parseIdList(body != null ? body.get("ids") : null);
        @SuppressWarnings("unchecked")
        Map<String, Object> cred = body != null && body.get("credentials") instanceof Map
                ? (Map<String, Object>) body.get("credentials")
                : body;
        try {
            Map<String, Object> result = deviceService.batchUpdateCredentials(ids, cred);
            auditLogService.record(AuditLogService.AuditRecord.builder()
                    .module("device")
                    .action("batch_credentials")
                    .operator(SecurityUtils.resolveOperator(authentication))
                    .targetType("device")
                    .status("success")
                    .summary("批量更新凭证 " + result.get("updated") + " 台")
                    .clientIp(AuditLogService.currentClientIp())
                    .build());
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private List<Long> parseIdList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Long> ids = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o == null) continue;
            try {
                ids.add(Long.parseLong(String.valueOf(o)));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Device> getDeviceById(@PathVariable Long id) {
        return deviceService.getDeviceById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> createDevice(@RequestBody Device device, Authentication authentication) {
        try {
            Device saved = deviceService.createDevice(device);
            auditDevice("create", saved, "success", "新增设备 " + saved.getName(), authentication);
            return ResponseEntity.ok(saved);
        } catch (RuntimeException e) {
            auditDevice("create", device, "failed", "新增设备失败: " + e.getMessage(), authentication);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateDevice(@PathVariable Long id, @RequestBody Device device,
                                          Authentication authentication) {
        try {
            Device saved = deviceService.updateDevice(id, device);
            auditDevice("update", saved, "success", "编辑设备 " + saved.getName(), authentication);
            return ResponseEntity.ok(saved);
        } catch (RuntimeException e) {
            auditDevice("update", id, device.getName(), "failed", "编辑设备失败: " + e.getMessage(), authentication);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDevice(@PathVariable Long id, Authentication authentication) {
        try {
            Device device = deviceService.getDeviceById(id).orElse(null);
            String name = device != null ? device.getName() : String.valueOf(id);
            deviceService.deleteDevice(id);
            auditDevice("delete", id, name, "success", "删除设备 " + name, authentication);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            auditDevice("delete", id, null, "failed", "删除设备失败: " + e.getMessage(), authentication);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/refresh")
    public ResponseEntity<?> refreshDeviceStatus(@PathVariable Long id, Authentication authentication) {
        try {
            Device saved = deviceService.refreshDeviceStatus(id);
            auditDevice("refresh", saved, "success", "刷新设备状态 " + saved.getName(), authentication);
            Map<String, Object> body = objectMapper.convertValue(saved, new TypeReference<LinkedHashMap<String, Object>>() {});
            if (body == null) {
                body = new LinkedHashMap<>();
            }
            body.put("probe", deviceService.getProbeSnapshot(id));
            return ResponseEntity.ok(body);
        } catch (RuntimeException e) {
            auditDevice("refresh", id, null, "failed", "刷新设备状态失败: " + e.getMessage(), authentication);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 兼容旧客户端：同步扫描并自动入库新设备。
     */
    @PostMapping("/discover")
    public ResponseEntity<?> discoverDevices(@RequestBody Map<String, Object> request,
                                             Authentication authentication) {
        try {
            String network = String.valueOf(request.getOrDefault("network", "192.168.56.0"));
            int timeout = parseInt(request.get("timeout"), 10);
            String community = String.valueOf(request.getOrDefault("community", "public"));
            int snmpPort = parseInt(request.get("snmpPort"), 161);
            var result = deviceDiscoveryService.discoverDevices(network, timeout, community, snmpPort);
            auditLogService.record(AuditLogService.AuditRecord.builder()
                    .module("device")
                    .action("discover")
                    .operator(SecurityUtils.resolveOperator(authentication))
                    .targetType("network")
                    .targetId(network)
                    .targetName(network)
                    .status("success")
                    .summary("设备发现扫描 " + network)
                    .clientIp(AuditLogService.currentClientIp())
                    .build());
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            auditLogService.record(AuditLogService.AuditRecord.builder()
                    .module("device")
                    .action("discover")
                    .operator(SecurityUtils.resolveOperator(authentication))
                    .status("failed")
                    .summary("设备发现失败: " + e.getMessage())
                    .clientIp(AuditLogService.currentClientIp())
                    .build());
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 启动异步扫描（不入库） */
    @PostMapping("/discover/scan")
    public ResponseEntity<?> startDiscoverScan(@RequestBody Map<String, Object> request,
                                               Authentication authentication) {
        try {
            String network = String.valueOf(request.getOrDefault("network", "192.168.56.0"));
            int timeout = parseInt(request.get("timeout"), 30);
            String community = String.valueOf(request.getOrDefault("community", "public"));
            int snmpPort = parseInt(request.get("snmpPort"), 161);
            var result = deviceDiscoveryService.startScan(network, timeout, community, snmpPort);
            auditLogService.record(AuditLogService.AuditRecord.builder()
                    .module("device")
                    .action("discover_scan")
                    .operator(SecurityUtils.resolveOperator(authentication))
                    .targetType("network")
                    .targetId(network)
                    .targetName(network)
                    .status("success")
                    .summary("启动设备发现扫描 " + network)
                    .clientIp(AuditLogService.currentClientIp())
                    .build());
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/discover/jobs/{jobId}")
    public ResponseEntity<?> getDiscoverJob(@PathVariable String jobId) {
        return deviceDiscoveryService.getJob(jobId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 从已纳管交换机/路由器 ARP 表发现终端（虚拟 PC）候选，不自动入库。
     */
    @PostMapping("/discover/endpoints")
    public ResponseEntity<?> discoverEndpoints(Authentication authentication) {
        try {
            List<DiscoverCandidate> candidates = deviceDiscoveryService.discoverEndpointsFromArp();
            auditLogService.record(AuditLogService.AuditRecord.builder()
                    .module("device")
                    .action("discover_endpoints")
                    .operator(SecurityUtils.resolveOperator(authentication))
                    .targetType("arp")
                    .status("success")
                    .summary("ARP 终端发现 " + candidates.size() + " 条")
                    .clientIp(AuditLogService.currentClientIp())
                    .build());
            return ResponseEntity.ok(Map.of(
                    "candidates", candidates,
                    "total", candidates.size(),
                    "newCount", candidates.stream().filter(c -> !c.isAlreadyExists()).count()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 勾选候选设备入库 */
    @PostMapping("/discover/import")
    public ResponseEntity<?> importDiscovered(@RequestBody Map<String, Object> request,
                                              Authentication authentication) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawList = (List<Map<String, Object>>) request.get("candidates");
            if (rawList == null) {
                rawList = (List<Map<String, Object>>) request.get("devices");
            }
            List<DiscoverCandidate> candidates = new java.util.ArrayList<>();
            if (rawList != null) {
                for (Map<String, Object> m : rawList) {
                    DiscoverCandidate c = new DiscoverCandidate();
                    c.setIpAddress(str(m.get("ipAddress")));
                    c.setName(str(m.get("name")));
                    c.setDescription(str(m.get("description")));
                    c.setVendor(str(m.get("vendor")));
                    c.setModel(str(m.get("model")));
                    c.setDeviceType(str(m.get("deviceType")));
                    c.setMonitorMode(str(m.get("monitorMode")));
                    c.setSnmpVersion(str(m.get("snmpVersion")));
                    c.setSnmpCommunity(str(m.get("snmpCommunity")));
                    c.setSnmpPort(parseInt(m.get("snmpPort"), 161));
                    c.setDiscoverSource(str(m.get("discoverSource")));
                    c.setMacAddress(str(m.get("macAddress")));
                    candidates.add(c);
                }
            }
            List<Device> saved = deviceDiscoveryService.importCandidates(candidates);
            auditLogService.record(AuditLogService.AuditRecord.builder()
                    .module("device")
                    .action("discover_import")
                    .operator(SecurityUtils.resolveOperator(authentication))
                    .targetType("device")
                    .status(saved.isEmpty() ? "failed" : "success")
                    .summary("发现设备入库 " + saved.size() + " 台")
                    .clientIp(AuditLogService.currentClientIp())
                    .build());
            return ResponseEntity.ok(Map.of(
                    "imported", saved.size(),
                    "devices", saved
            ));
        } catch (RuntimeException e) {
            auditLogService.record(AuditLogService.AuditRecord.builder()
                    .module("device")
                    .action("discover_import")
                    .operator(SecurityUtils.resolveOperator(authentication))
                    .status("failed")
                    .summary("发现设备入库失败: " + e.getMessage())
                    .clientIp(AuditLogService.currentClientIp())
                    .build());
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/{id}/ports")
    public ResponseEntity<List<DevicePort>> getDevicePorts(@PathVariable Long id) {
        return ResponseEntity.ok(deviceService.getDevicePorts(id));
    }

    private Pageable buildPageable(int page, int size, String sort) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        if (sort == null || sort.isBlank()) {
            return PageRequest.of(p, s, Sort.by(Sort.Direction.ASC, "id"));
        }
        String[] parts = sort.split(",");
        String prop = parts[0].trim();
        Sort.Direction dir = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageRequest.of(p, s, Sort.by(dir, prop));
    }

    private int parseInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void auditDevice(String action, Device device, String status, String summary,
                             Authentication authentication) {
        if (device == null) return;
        auditDevice(action, device.getId(), device.getName(), status, summary, authentication);
    }

    private void auditDevice(String action, Long id, String name, String status, String summary,
                             Authentication authentication) {
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("device")
                .action(action)
                .operator(SecurityUtils.resolveOperator(authentication))
                .targetType("device")
                .targetId(id != null ? String.valueOf(id) : null)
                .targetName(name)
                .status(status)
                .summary(summary)
                .clientIp(AuditLogService.currentClientIp())
                .build());
    }
}
