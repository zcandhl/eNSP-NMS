package com.ensp.nms.controller;

import com.ensp.nms.entity.BackupSchedule;
import com.ensp.nms.security.SecurityUtils;
import com.ensp.nms.service.AuditLogService;
import com.ensp.nms.service.BackupScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/backup-schedules")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BackupScheduleController {

    private final BackupScheduleService backupScheduleService;
    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<List<BackupSchedule>> getAllSchedules() {
        return ResponseEntity.ok(backupScheduleService.getAllSchedules());
    }

    @GetMapping("/device/{deviceId}")
    public ResponseEntity<List<BackupSchedule>> getSchedulesByDeviceId(@PathVariable Long deviceId) {
        return ResponseEntity.ok(backupScheduleService.getSchedulesByDeviceId(deviceId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BackupSchedule> getScheduleById(@PathVariable Long id) {
        return backupScheduleService.getScheduleById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<BackupSchedule> createSchedule(@RequestBody BackupSchedule schedule,
                                                         Authentication authentication) {
        BackupSchedule saved = backupScheduleService.createSchedule(schedule);
        auditSchedule("create", saved, "success", "创建备份计划", authentication);
        return ResponseEntity.ok(saved);
    }

    /** 备份策略：为多台设备批量创建相同周期的定时备份 */
    @PostMapping("/policy")
    public ResponseEntity<?> createPolicy(@RequestBody Map<String, Object> body) {
        try {
            BackupSchedule template = new BackupSchedule();
            template.setScheduleType(body.get("scheduleType") != null ? body.get("scheduleType").toString() : "daily");
            template.setScheduleTime(body.get("scheduleTime") != null ? body.get("scheduleTime").toString() : "02:00");
            template.setConfigType(body.get("configType") != null ? body.get("configType").toString() : "running");
            template.setIsActive(body.get("isActive") == null || Boolean.parseBoolean(body.get("isActive").toString()));
            if (body.get("dayOfWeek") != null) {
                template.setDayOfWeek(Integer.valueOf(body.get("dayOfWeek").toString()));
            }
            if (body.get("dayOfMonth") != null) {
                template.setDayOfMonth(Integer.valueOf(body.get("dayOfMonth").toString()));
            }

            if (body.get("groupId") != null) {
                Long groupId = Long.valueOf(body.get("groupId").toString());
                return ResponseEntity.ok(backupScheduleService.createPolicyForGroup(groupId, template));
            }

            @SuppressWarnings("unchecked")
            List<?> raw = (List<?>) body.get("deviceIds");
            List<Long> deviceIds = new java.util.ArrayList<>();
            if (raw != null) {
                for (Object o : raw) {
                    if (o instanceof Number n) {
                        deviceIds.add(n.longValue());
                    } else if (o != null) {
                        deviceIds.add(Long.parseLong(o.toString()));
                    }
                }
            }
            return ResponseEntity.ok(backupScheduleService.createPolicy(deviceIds, template));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 同步分组备份计划：按当前成员补齐/更新，可选清理离组成员计划 */
    @PostMapping("/sync-group")
    public ResponseEntity<?> syncGroupPolicy(@RequestBody Map<String, Object> body) {
        try {
            if (body.get("groupId") == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "groupId 不能为空"));
            }
            Long groupId = Long.valueOf(body.get("groupId").toString());
            BackupSchedule template = new BackupSchedule();
            template.setScheduleType(body.get("scheduleType") != null ? body.get("scheduleType").toString() : "daily");
            template.setScheduleTime(body.get("scheduleTime") != null ? body.get("scheduleTime").toString() : "02:00");
            template.setConfigType(body.get("configType") != null ? body.get("configType").toString() : "running");
            template.setIsActive(body.get("isActive") == null || Boolean.parseBoolean(body.get("isActive").toString()));
            if (body.get("dayOfWeek") != null) {
                template.setDayOfWeek(Integer.valueOf(body.get("dayOfWeek").toString()));
            }
            if (body.get("dayOfMonth") != null) {
                template.setDayOfMonth(Integer.valueOf(body.get("dayOfMonth").toString()));
            }
            boolean removeOrphans = body.get("removeOrphans") == null
                    || Boolean.parseBoolean(body.get("removeOrphans").toString());
            return ResponseEntity.ok(backupScheduleService.syncGroupPolicy(groupId, template, removeOrphans));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<BackupSchedule> updateSchedule(@PathVariable Long id, @RequestBody BackupSchedule schedule,
                                                         Authentication authentication) {
        BackupSchedule saved = backupScheduleService.updateSchedule(id, schedule);
        auditSchedule("update", saved, "success", "更新备份计划", authentication);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable Long id, Authentication authentication) {
        backupScheduleService.getScheduleById(id).ifPresent(s ->
                auditSchedule("delete", s, "success", "删除备份计划", authentication));
        backupScheduleService.deleteSchedule(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<Map<String, Object>> executeSchedule(@PathVariable Long id,
                                                               Authentication authentication) {
        BackupSchedule schedule = backupScheduleService.getScheduleById(id)
                .orElseThrow(() -> new RuntimeException("Schedule not found"));
        Map<String, Object> result = backupScheduleService.executeSchedule(schedule);
        boolean ok = Boolean.TRUE.equals(result.get("success"));
        auditSchedule("execute", schedule, ok ? "success" : "failed",
                ok ? "手动执行备份计划成功" : "手动执行备份计划失败", authentication);
        result.put("ok", ok);
        return ok ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    private void auditSchedule(String action, BackupSchedule schedule, String status, String summary,
                               Authentication authentication) {
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("config")
                .action("schedule_" + action)
                .operator(SecurityUtils.resolveOperator(authentication))
                .targetType("backup_schedule")
                .targetId(schedule.getId() != null ? String.valueOf(schedule.getId()) : null)
                .targetName("设备 " + schedule.getDeviceId())
                .status(status)
                .summary(summary + "（设备 " + schedule.getDeviceId() + "）")
                .clientIp(AuditLogService.currentClientIp())
                .build());
    }
}
