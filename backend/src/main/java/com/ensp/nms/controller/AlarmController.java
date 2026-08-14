package com.ensp.nms.controller;

import com.ensp.nms.entity.Alarm;
import com.ensp.nms.service.AlarmService;
import com.ensp.nms.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private static final int EXPORT_MAX = 2000;

    @Autowired
    private AlarmService alarmService;

    @Autowired
    private AuditLogService auditLogService;

    /**
     * @deprecated 请使用 {@code GET /api/alarms/query} 分页接口。
     */
    @Deprecated
    @GetMapping
    public ResponseEntity<List<Alarm>> getAllAlarms() {
        log.warn("GET /api/alarms 无分页已废弃，建议改用 /api/alarms/query；本次最多返回 {} 条",
                AlarmService.UNBOUNDED_LIST_LIMIT);
        return ResponseEntity.ok(alarmService.getAllAlarms());
    }

    @GetMapping("/query")
    public ResponseEntity<Page<Alarm>> queryAlarms(
            @RequestParam(required = false) List<String> status,
            @RequestParam(name = "status[]", required = false) List<String> statusBracket,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String timeRange,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false, defaultValue = "false") boolean overdueOnly,
            @RequestParam(required = false, defaultValue = "30") int overdueMinutes,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "occurredAt,desc") String sort
    ) {
        List<Alarm.Status> statuses = parseStatuses(coalesceStatusParams(status, statusBracket));
        Alarm.Severity sev = parseSeverity(severity);
        LocalDateTime fromTime = from;
        if (fromTime == null && !overdueOnly) {
            fromTime = AlarmService.resolveTimeRangeStart(timeRange);
        }
        Pageable pageable = buildPageable(page, size, sort);
        return ResponseEntity.ok(alarmService.queryAlarms(
                statuses, sev, keyword, fromTime, to, overdueOnly, overdueMinutes, deviceId, pageable));
    }

    /** 按当前筛选导出 CSV（最多 2000 条） */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAlarms(
            @RequestParam(required = false) List<String> status,
            @RequestParam(name = "status[]", required = false) List<String> statusBracket,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String timeRange,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false, defaultValue = "false") boolean overdueOnly,
            @RequestParam(required = false, defaultValue = "30") int overdueMinutes,
            @RequestParam(required = false) Long deviceId
    ) {
        List<Alarm.Status> statuses = parseStatuses(coalesceStatusParams(status, statusBracket));
        Alarm.Severity sev = parseSeverity(severity);
        LocalDateTime fromTime = from;
        if (fromTime == null && !overdueOnly) {
            fromTime = AlarmService.resolveTimeRangeStart(timeRange);
        }
        Pageable pageable = PageRequest.of(0, EXPORT_MAX, Sort.by(Sort.Direction.DESC, "occurredAt"));
        List<Alarm> rows = alarmService.queryAlarms(
                statuses, sev, keyword, fromTime, to, overdueOnly, overdueMinutes, deviceId, pageable
        ).getContent();

        String csv = toCsv(rows);
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        // Excel 友好 BOM
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] out = new byte[bom.length + bytes.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(bytes, 0, out, bom.length, bytes.length);

        String filename = "alarms-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(out);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<Alarm>> getAlarmsByStatus(@PathVariable Alarm.Status status) {
        return ResponseEntity.ok(alarmService.getAlarmsByStatus(status));
    }

    @GetMapping("/status/{status}/page")
    public ResponseEntity<Page<Alarm>> getAlarmsByStatusPage(
            @PathVariable Alarm.Status status,
            Pageable pageable
    ) {
        return ResponseEntity.ok(alarmService.getAlarmsByStatusPage(status, pageable));
    }

    @GetMapping("/device/{deviceId}")
    public ResponseEntity<List<Alarm>> getAlarmsByDeviceId(@PathVariable Long deviceId) {
        return ResponseEntity.ok(alarmService.getAlarmsByDeviceId(deviceId));
    }

    @GetMapping("/device/{deviceId}/page")
    public ResponseEntity<Page<Alarm>> getAlarmsByDeviceIdPage(
            @PathVariable Long deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "occurredAt"));
        return ResponseEntity.ok(alarmService.getAlarmsByDeviceIdPage(deviceId, pageable));
    }

    @GetMapping("/severity/{severity}")
    public ResponseEntity<List<Alarm>> getAlarmsBySeverity(@PathVariable Alarm.Severity severity) {
        return ResponseEntity.ok(alarmService.getAlarmsBySeverity(severity));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Alarm> getAlarmById(@PathVariable Long id) {
        Optional<Alarm> alarm = alarmService.getAlarmById(id);
        return alarm.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getAlarmStats() {
        return ResponseEntity.ok(alarmService.getAlarmStats());
    }

    @PutMapping("/{id}/acknowledge")
    public ResponseEntity<Alarm> acknowledgeAlarm(
            @PathVariable Long id,
            @RequestParam(required = false) String note,
            Authentication authentication
    ) {
        String operator = resolveOperator(authentication);
        Optional<Alarm> alarm = alarmService.acknowledgeAlarm(id, operator, note);
        if (alarm.isPresent()) {
            Alarm a = alarm.get();
            boolean ackClosed = a.getStatus() == Alarm.Status.CLEARED
                    && a.getClearNote() != null
                    && a.getClearNote().startsWith("阅知关闭");
            if (ackClosed) {
                auditAlarm("ack_close", a, operator, "success", "阅知关闭: " + a.getTitle());
            } else {
                auditAlarm("ack", a, operator, "success", "确认告警: " + a.getTitle());
            }
        }
        return alarm.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/clear")
    public ResponseEntity<Alarm> clearAlarm(@PathVariable Long id, Authentication authentication) {
        String operator = resolveOperator(authentication);
        Optional<Alarm> alarm = alarmService.clearAlarm(id);
        if (alarm.isPresent()) {
            Alarm a = alarm.get();
            auditAlarm("clear", a, operator, "success", "清除告警: " + a.getTitle());
        }
        return alarm.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAlarm(@PathVariable Long id, Authentication authentication) {
        String operator = resolveOperator(authentication);
        Optional<Alarm> alarm = alarmService.getAlarmById(id);
        if (alarmService.deleteAlarm(id)) {
            alarm.ifPresent(a -> auditAlarm("delete", a, operator, "success", "删除告警: " + a.getTitle()));
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/batch-acknowledge")
    public ResponseEntity<Map<String, Object>> batchAcknowledge(
            @RequestBody Map<String, Object> body,
            Authentication authentication
    ) {
        List<Long> ids = extractIds(body);
        String by = resolveOperator(authentication);
        String note = body.get("note") != null ? body.get("note").toString() : null;
        String detail = buildAlarmBatchDetail("批量确认", ids);
        int n = alarmService.batchAcknowledge(ids, by, note);
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("alarm")
                .action("batch_ack")
                .operator(by)
                .targetType("alarm")
                .status(n > 0 ? "success" : "failed")
                .summary("批量确认告警 " + n + " 条")
                .clientIp(AuditLogService.currentClientIp())
                .detail(detail)
                .build());
        Map<String, Object> res = new HashMap<>();
        res.put("count", n);
        res.put("acknowledgedBy", by);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/batch-clear")
    public ResponseEntity<Map<String, Object>> batchClear(@RequestBody Map<String, Object> body,
                                                         Authentication authentication) {
        List<Long> ids = extractIds(body);
        String operator = resolveOperator(authentication);
        String detail = buildAlarmBatchDetail("批量清除", ids);
        int n = alarmService.batchClear(ids);
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("alarm")
                .action("batch_clear")
                .operator(operator)
                .targetType("alarm")
                .status(n > 0 ? "success" : "failed")
                .summary("批量清除告警 " + n + " 条")
                .clientIp(AuditLogService.currentClientIp())
                .detail(detail)
                .build());
        Map<String, Object> res = new HashMap<>();
        res.put("count", n);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/batch-delete")
    public ResponseEntity<Map<String, Object>> batchDelete(@RequestBody Map<String, Object> body,
                                                           Authentication authentication) {
        List<Long> ids = extractIds(body);
        String operator = resolveOperator(authentication);
        String detail = buildAlarmBatchDetail("批量删除", ids);
        int n = alarmService.batchDelete(ids);
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("alarm")
                .action("batch_delete")
                .operator(operator)
                .targetType("alarm")
                .status(n > 0 ? "success" : "failed")
                .summary("批量删除告警 " + n + " 条")
                .clientIp(AuditLogService.currentClientIp())
                .detail(detail)
                .build());
        Map<String, Object> res = new HashMap<>();
        res.put("count", n);
        return ResponseEntity.ok(res);
    }

    /**
     * 批量告警操作审计正文：写明「告警 ID」，并尽量带上标题/设备，避免用户只看到裸数字。
     * 须在执行清除/删除前调用，以便仍能读到标题。
     */
    private String buildAlarmBatchDetail(String opLabel, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return opLabel + "：未指定告警 ID";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(opLabel).append("，涉及告警共 ").append(ids.size()).append(" 条。\n");
        sb.append("说明：下列编号为告警 ID（可在「告警管理」中对照）。\n");
        int shown = 0;
        for (Long id : ids) {
            if (id == null) continue;
            if (shown >= 40) {
                sb.append("… 其余 ").append(ids.size() - shown).append(" 条仅记 ID：");
                for (int i = shown; i < ids.size(); i++) {
                    if (ids.get(i) != null) {
                        sb.append(ids.get(i));
                        if (i < ids.size() - 1) sb.append(',');
                    }
                }
                sb.append('\n');
                break;
            }
            Optional<Alarm> opt = alarmService.getAlarmById(id);
            if (opt.isPresent()) {
                Alarm a = opt.get();
                sb.append("- 告警#").append(id);
                if (a.getSeverity() != null) sb.append(" [").append(a.getSeverity()).append(']');
                if (a.getTitle() != null && !a.getTitle().isBlank()) sb.append(' ').append(a.getTitle());
                if (a.getDeviceName() != null && !a.getDeviceName().isBlank()) {
                    sb.append(" @ ").append(a.getDeviceName());
                } else if (a.getDeviceIp() != null && !a.getDeviceIp().isBlank()) {
                    sb.append(" @ ").append(a.getDeviceIp());
                }
                sb.append('\n');
            } else {
                sb.append("- 告警#").append(id).append("（当时未读到标题）\n");
            }
            shown++;
        }
        return sb.toString().trim();
    }

    private String toCsv(List<Alarm> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("ID,级别,状态,标题,设备,管理IP,类型,发生时间,重复次数,确认人,确认备注\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (Alarm a : rows) {
            sb.append(csv(a.getId())).append(',')
                    .append(csv(a.getSeverity())).append(',')
                    .append(csv(a.getStatus())).append(',')
                    .append(csv(a.getTitle())).append(',')
                    .append(csv(a.getDeviceName())).append(',')
                    .append(csv(a.getDeviceIp())).append(',')
                    .append(csv(a.getTrapType())).append(',')
                    .append(csv(a.getOccurredAt() != null ? a.getOccurredAt().format(fmt) : "")).append(',')
                    .append(csv(a.getRepeatCount())).append(',')
                    .append(csv(a.getAcknowledgedBy())).append(',')
                    .append(csv(a.getAcknowledgeNote()))
                    .append('\n');
        }
        return sb.toString();
    }

    private String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v).replace("\"", "\"\"");
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s + "\"";
        }
        return s;
    }

    /** 兼容 status=A&status=B、status=A,B、以及 axios 默认的 status[]=A */
    private List<String> coalesceStatusParams(List<String> status, List<String> statusBracket) {
        if (status != null && !status.isEmpty()) {
            return status;
        }
        if (statusBracket != null && !statusBracket.isEmpty()) {
            return statusBracket;
        }
        return null;
    }

    private List<Alarm.Status> parseStatuses(List<String> status) {
        if (status == null || status.isEmpty()) {
            return null;
        }
        List<Alarm.Status> list = new ArrayList<>();
        for (String s : status) {
            if (s == null || s.isBlank()) continue;
            for (String part : s.split(",")) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                try {
                    list.add(Alarm.Status.valueOf(t.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return list.isEmpty() ? null : list;
    }

    private Alarm.Severity parseSeverity(String severity) {
        if (severity == null || severity.isBlank()) return null;
        try {
            return Alarm.Severity.valueOf(severity.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Pageable buildPageable(int page, int size, String sort) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 200);
        if (sort == null || sort.isBlank()) {
            return PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "occurredAt"));
        }
        String[] parts = sort.split(",");
        String prop = parts[0].trim();
        Sort.Direction dir = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(p, s, Sort.by(dir, prop));
    }

    @SuppressWarnings("unchecked")
    private List<Long> extractIds(Map<String, Object> body) {
        List<Long> ids = new ArrayList<>();
        if (body == null) return ids;
        Object raw = body.get("ids");
        if (!(raw instanceof List<?> list)) return ids;
        for (Object o : list) {
            if (o == null) continue;
            if (o instanceof Number n) {
                ids.add(n.longValue());
            } else {
                try {
                    ids.add(Long.parseLong(o.toString()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return ids;
    }

    /** 确认人强制取登录用户，禁止客户端冒名 */
    @SuppressWarnings("unchecked")
    private String resolveOperator(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return "system";
        }
        Object details = authentication.getDetails();
        if (details instanceof Map<?, ?> map) {
            Object realName = map.get("realName");
            if (realName != null && !String.valueOf(realName).isBlank()) {
                return String.valueOf(realName).trim();
            }
        }
        String name = authentication.getName();
        return name != null && !name.isBlank() ? name : "system";
    }

    private void auditAlarm(String action, Alarm alarm, String operator, String status, String summary) {
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("alarm")
                .action(action)
                .operator(operator)
                .targetType("alarm")
                .targetId(alarm.getId() != null ? String.valueOf(alarm.getId()) : null)
                .targetName(alarm.getTitle())
                .status(status)
                .summary(summary)
                .clientIp(AuditLogService.currentClientIp())
                .build());
    }
}
