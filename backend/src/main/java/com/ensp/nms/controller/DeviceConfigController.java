package com.ensp.nms.controller;

import com.ensp.nms.dto.DeviceConfigSummary;
import com.ensp.nms.entity.DeviceConfig;
import com.ensp.nms.security.SecurityUtils;
import com.ensp.nms.service.AuditLogService;
import com.ensp.nms.service.ConfigComplianceService;
import com.ensp.nms.service.ConfigTaskService;
import com.ensp.nms.service.ConfigTaskState;
import com.ensp.nms.service.DeviceConfigService;
import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping("/api/configs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DeviceConfigController {

    private final DeviceConfigService deviceConfigService;
    private final ConfigTaskService configTaskService;
    private final AuditLogService auditLogService;
    private final ConfigComplianceService configComplianceService;

    @GetMapping("/health")
    public ResponseEntity<?> getBackupHealth() {
        return ResponseEntity.ok(deviceConfigService.getBackupHealthOverview());
    }

    @GetMapping("/live/{deviceId}")
    public ResponseEntity<?> pullLiveConfig(@PathVariable Long deviceId,
                                            @RequestParam(defaultValue = "running") String type) {
        try {
            String content = deviceConfigService.pullLiveConfig(deviceId, type);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "deviceId", deviceId,
                    "configType", type,
                    "content", content
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 列表不含全文；兼容旧调用 */
    @GetMapping("/device/{deviceId}")
    public ResponseEntity<List<DeviceConfigSummary>> getConfigsByDeviceId(@PathVariable Long deviceId) {
        return ResponseEntity.ok(deviceConfigService.getConfigSummariesByDeviceId(deviceId));
    }

    @GetMapping("/device/{deviceId}/page")
    public ResponseEntity<Page<DeviceConfigSummary>> getConfigsByDeviceIdPage(
            @PathVariable Long deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(deviceConfigService.getConfigSummariesByDeviceId(deviceId, pageable));
    }

    @GetMapping("/{id:\\d+}")
    public ResponseEntity<DeviceConfig> getConfigById(@PathVariable Long id) {
        return deviceConfigService.getConfigById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks")
    public ResponseEntity<List<Map<String, Object>>> listTasks() {
        return ResponseEntity.ok(
                configTaskService.listTasks().stream().map(ConfigTaskState::toMap).toList()
        );
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        return configTaskService.getTask(taskId)
                .<ResponseEntity<?>>map(t -> ResponseEntity.ok(t.toMap()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<?> cancelTask(@PathVariable String taskId) {
        try {
            return ResponseEntity.ok(Map.of("success", true, "task", configTaskService.cancel(taskId).toMap()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/tasks/{taskId}/continue")
    public ResponseEntity<?> continueTask(@PathVariable String taskId, @RequestBody(required = false) Map<String, Object> body) {
        try {
            Integer waveSize = null;
            if (body != null && body.get("waveSize") != null) {
                waveSize = Integer.parseInt(body.get("waveSize").toString());
            }
            return ResponseEntity.ok(Map.of("success", true, "task", configTaskService.continueBatch(taskId, waveSize).toMap()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/tasks/{taskId}/retry-failed")
    public ResponseEntity<?> retryFailedTask(@PathVariable String taskId) {
        try {
            ConfigTaskState task = configTaskService.retryFailed(taskId);
            return ResponseEntity.ok(Map.of("success", true, "taskId", task.getId(), "task", task.toMap()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/compliance/rules")
    public ResponseEntity<?> listComplianceRules() {
        return ResponseEntity.ok(configComplianceService.listRules());
    }

    @GetMapping("/compliance/{deviceId}")
    public ResponseEntity<?> evaluateCompliance(@PathVariable Long deviceId,
                                                @RequestParam(required = false) String source) {
        try {
            return ResponseEntity.ok(configComplianceService.evaluate(deviceId, source));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/batch-apply/preview")
    public ResponseEntity<?> previewBatchApply(@RequestBody Map<String, Object> request) {
        try {
            List<Long> deviceIds = parseDeviceIds(request.get("deviceIds"));
            if (deviceIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请选择设备"));
            }
            String content = request.get("content") != null ? request.get("content").toString() : "";
            boolean enableVariables = request.get("enableVariables") != null && Boolean.parseBoolean(request.get("enableVariables").toString());
            Map<String, Object> preview = deviceConfigService.previewBatchApply(deviceIds, content, enableVariables);
            preview.put("success", true);
            return ResponseEntity.ok(preview);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/backup")
    public ResponseEntity<?> backupConfig(@RequestBody Map<String, Object> request,
                                          Authentication authentication) {
        try {
            if (request == null || !request.containsKey("deviceId") || request.get("deviceId") == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "设备ID不能为空"));
            }

            Long deviceId = Long.valueOf(request.get("deviceId").toString());
            String configType = (String) request.getOrDefault("configType", "running");
            String description = request.get("description") != null ? request.get("description").toString() : null;
            String createdBy = SecurityUtils.resolveOperator(authentication);

            DeviceConfig config = deviceConfigService.backupConfig(deviceId, configType, createdBy, description, true);
            // 响应不回传全文，前端按需拉取
            DeviceConfigSummary summary = new DeviceConfigSummary();
            summary.setId(config.getId());
            summary.setDeviceId(config.getDeviceId());
            summary.setConfigType(config.getConfigType());
            summary.setConfigVersion(config.getConfigVersion());
            summary.setDescription(config.getDescription());
            summary.setCreatedAt(config.getCreatedAt());
            summary.setCreatedBy(config.getCreatedBy());
            return ResponseEntity.ok(Map.of("success", true, "data", summary));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "设备ID格式错误"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable Long id) {
        deviceConfigService.deleteConfig(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/batch-delete")
    public ResponseEntity<Map<String, Object>> batchDeleteConfigs(@RequestBody Map<String, Object> request) {
        try {
            List<Long> ids = parseConfigIds(request.get("ids"));
            if (ids.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请选择配置"));
            }
            Long deviceId = request.get("deviceId") != null
                    ? Long.valueOf(request.get("deviceId").toString()) : null;
            Map<String, Object> result = deviceConfigService.batchDeleteConfigs(ids, deviceId);
            boolean ok = Boolean.TRUE.equals(result.get("success"));
            return ok ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** GET 与单文件导出一致，走 configs:read 鉴权，避免 POST blob 触发 401 */
    @GetMapping("/batch-export")
    public ResponseEntity<?> batchExportConfigs(@RequestParam String ids) {
        try {
            List<Long> idList = parseConfigIdsFromString(ids);
            byte[] zip = deviceConfigService.exportConfigsAsZip(idList);
            String filename = "configs_export_" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".zip";
            org.springframework.core.io.Resource resource = new org.springframework.core.io.ByteArrayResource(zip);
            return ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .contentType(org.springframework.http.MediaType.parseMediaType("application/zip"))
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    private static List<Long> parseDeviceIds(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Long> ids = new java.util.ArrayList<>();
        for (Object id : list) {
            if (id instanceof Number) {
                ids.add(((Number) id).longValue());
            } else if (id instanceof String s && !s.isBlank()) {
                ids.add(Long.parseLong(s.trim()));
            }
        }
        return ids;
    }

    private static List<Long> parseConfigIds(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Long> ids = new java.util.ArrayList<>();
        for (Object id : list) {
            if (id instanceof Number) {
                ids.add(((Number) id).longValue());
            } else if (id instanceof String s && !s.isBlank()) {
                ids.add(Long.parseLong(s.trim()));
            }
        }
        return ids;
    }

    private static List<Long> parseConfigIdsFromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> ids = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                ids.add(Long.parseLong(s));
            }
        }
        return ids;
    }

    @PostMapping("/test-ssh/{deviceId}")
    public ResponseEntity<Map<String, Boolean>> testSshConnection(@PathVariable Long deviceId) {
        boolean success = deviceConfigService.testSshConnection(deviceId);
        return ResponseEntity.ok(Map.of("success", success));
    }

    @PostMapping("/restore/{deviceId}/{configId}")
    public ResponseEntity<Map<String, Object>> restoreConfig(@PathVariable Long deviceId, @PathVariable Long configId) {
        try {
            Map<String, Object> result = deviceConfigService.restoreConfig(deviceId, configId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 异步恢复：立即返回 taskId，前端轮询 /tasks/{id} 获取真实进度 */
    @PostMapping("/tasks/restore")
    public ResponseEntity<Map<String, Object>> startRestoreTask(@RequestBody Map<String, Object> body) {
        try {
            Long deviceId = Long.valueOf(body.get("deviceId").toString());
            Long configId = Long.valueOf(body.get("configId").toString());
            boolean preBackup = body.get("preBackup") == null || Boolean.parseBoolean(body.get("preBackup").toString());
            ConfigTaskState task = configTaskService.submitRestore(deviceId, configId, preBackup);
            return ResponseEntity.ok(Map.of("success", true, "taskId", task.getId(), "task", task.toMap()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 异步批量下发 */
    @PostMapping("/tasks/batch-apply")
    public ResponseEntity<Map<String, Object>> startBatchTask(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<?> rawDeviceIds = (List<?>) request.get("deviceIds");
            List<Long> deviceIds = new java.util.ArrayList<>();
            for (Object id : rawDeviceIds) {
                if (id instanceof Number) {
                    deviceIds.add(((Number) id).longValue());
                } else if (id instanceof String) {
                    deviceIds.add(Long.parseLong((String) id));
                }
            }
            String content = (String) request.get("content");
            boolean enableVariables = request.get("enableVariables") != null && (Boolean) request.get("enableVariables");
            boolean parallel = request.get("parallel") != null && (Boolean) request.get("parallel");
            String reason = request.get("reason") != null ? request.get("reason").toString() : null;
            int waveSize = 0;
            if (request.get("waveSize") != null) {
                waveSize = Integer.parseInt(request.get("waveSize").toString());
            }

            ConfigTaskState task = configTaskService.submitBatchApply(
                    deviceIds, content, enableVariables, parallel, reason, waveSize, null);
            return ResponseEntity.ok(Map.of("success", true, "taskId", task.getId(), "task", task.toMap()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 异步批量备份 */
    @PostMapping("/tasks/batch-backup")
    public ResponseEntity<Map<String, Object>> startBatchBackupTask(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<?> rawDeviceIds = (List<?>) request.get("deviceIds");
            List<Long> deviceIds = new java.util.ArrayList<>();
            if (rawDeviceIds != null) {
                for (Object id : rawDeviceIds) {
                    if (id instanceof Number) {
                        deviceIds.add(((Number) id).longValue());
                    } else if (id instanceof String) {
                        deviceIds.add(Long.parseLong((String) id));
                    }
                }
            }
            if (deviceIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请选择设备"));
            }
            String configType = request.get("configType") != null ? request.get("configType").toString() : "running";
            String description = request.get("description") != null ? request.get("description").toString() : null;
            ConfigTaskState task = configTaskService.submitBatchBackup(deviceIds, configType, description);
            return ResponseEntity.ok(Map.of("success", true, "taskId", task.getId(), "task", task.toMap()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/apply-template/{deviceId}")
    public ResponseEntity<Map<String, Object>> applyConfigTemplate(@PathVariable Long deviceId,
                                                                   @RequestBody Map<String, String> request) {
        try {
            String templateContent = request.get("content");
            String reason = request.get("reason");
            return ResponseEntity.ok(deviceConfigService.applyConfigTemplate(deviceId, templateContent, reason));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/batch-apply")
    public ResponseEntity<Map<String, Object>> batchApplyConfig(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<?> rawDeviceIds = (List<?>) request.get("deviceIds");
            List<Long> deviceIds = new java.util.ArrayList<>();
            for (Object id : rawDeviceIds) {
                if (id instanceof Number) {
                    deviceIds.add(((Number) id).longValue());
                } else if (id instanceof String) {
                    deviceIds.add(Long.parseLong((String) id));
                }
            }
            String content = (String) request.get("content");
            boolean enableVariables = request.get("enableVariables") != null ? (Boolean) request.get("enableVariables") : false;
            boolean parallel = request.get("parallel") != null ? (Boolean) request.get("parallel") : false;
            String reason = request.get("reason") != null ? request.get("reason").toString() : null;
            Map<String, Object> result = deviceConfigService.batchApplyConfig(
                    deviceIds, content, enableVariables, parallel, null, reason);
            long ok = result.get("successCount") instanceof Number n ? n.longValue() : 0;
            long all = result.get("totalCount") instanceof Number n ? n.longValue() : 0;
            String status = ok >= all && all > 0 ? "success" : (ok > 0 ? "partial" : "failed");
            auditConfig("batch_apply", null, null, null, status,
                    "同步批量下发，成功 " + ok + "/" + all, null, null);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/{id:\\d+}/export")
    public ResponseEntity<org.springframework.core.io.Resource> exportConfig(@PathVariable Long id) {
        try {
            DeviceConfig config = deviceConfigService.getConfigById(id)
                    .orElseThrow(() -> new RuntimeException("Config not found"));

            String filename = String.format("config_%s_%s.txt", config.getConfigVersion(), config.getConfigType());
            org.springframework.core.io.Resource resource =
                    new org.springframework.core.io.ByteArrayResource(config.getContent().getBytes());

            return ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    private void auditConfig(String action, Object targetId, String refTargetId, String targetName,
                             String status, String summary, String refType, String refId) {
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("config")
                .action(action)
                .operator(SecurityUtils.currentOperator())
                .targetType("device")
                .targetId(targetId != null ? String.valueOf(targetId) : refTargetId)
                .targetName(targetName)
                .status(status)
                .summary(summary)
                .clientIp(AuditLogService.currentClientIp())
                .refType(refType)
                .refId(refId)
                .build());
    }
}
