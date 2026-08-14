package com.ensp.nms.controller;

import com.ensp.nms.entity.AiopsFeedback;
import com.ensp.nms.service.aiops.AiopsService;
import com.ensp.nms.service.aiops.AlarmEscalationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/aiops")
@RequiredArgsConstructor
public class AiopsController {

    private final AiopsService aiopsService;
    private final AlarmEscalationService alarmEscalationService;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public Map<String, Object> overview(@RequestParam(value = "force", required = false) Boolean force) {
        return aiopsService.overview(Boolean.TRUE.equals(force));
    }

    @GetMapping("/health")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public Map<String, Object> health() {
        return aiopsService.health();
    }

    @GetMapping("/report/health")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public Map<String, Object> healthReport() {
        return aiopsService.healthReport();
    }

    @PostMapping("/escalation/run")
    @PreAuthorize("hasAuthority('aiops:write') or hasAuthority('alarms:handle') or hasAuthority('aiops:read')")
    public Map<String, Object> runEscalation() {
        return alarmEscalationService.runOnce();
    }

    @GetMapping("/incidents")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public List<Map<String, Object>> incidents() {
        return aiopsService.incidents();
    }

    @GetMapping("/rca")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('topology:read')")
    public Map<String, Object> rca() {
        return aiopsService.rca();
    }

    @PostMapping("/anomaly/detect")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('performance:read')")
    public Map<String, Object> detectAnomaly() {
        return aiopsService.runAnomalyDetect();
    }

    /** 一键智能巡检：立即关联 + 基线，返回可读报告 */
    @PostMapping("/inspect")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public Map<String, Object> inspect() {
        return aiopsService.inspectNow();
    }

    @GetMapping("/screen-brief")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public Map<String, Object> screenBrief() {
        return aiopsService.screenBrief();
    }

    @PostMapping("/unattended/run")
    @PreAuthorize("hasAuthority('aiops:write') or hasAuthority('alarms:handle') or hasAuthority('aiops:read')")
    public Map<String, Object> runUnattended(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            body = Map.of();
        }
        Long alarmId = parseLong(body.get("alarmId"));
        Long deviceId = parseLong(body.get("deviceId"));
        if (alarmId != null) {
            return aiopsService.runUnattendedForAlarm(alarmId, deviceId);
        }
        return aiopsService.runUnattendedCycle();
    }

    @GetMapping("/unattended/status")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public Map<String, Object> unattendedStatus() {
        return aiopsService.unattendedStatus();
    }

    @PostMapping("/unattended/pause")
    @PreAuthorize("hasAuthority('aiops:write') or hasAuthority('alarms:handle') or hasRole('ADMIN')")
    public Map<String, Object> pauseUnattended(@RequestBody(required = false) Map<String, Object> body) {
        boolean paused = true;
        if (body != null && body.containsKey("paused")) {
            Object raw = body.get("paused");
            if (raw instanceof Boolean b) {
                paused = b;
            } else {
                paused = !"false".equalsIgnoreCase(String.valueOf(raw))
                        && !"0".equals(String.valueOf(raw));
            }
        }
        return aiopsService.setUnattendedPaused(paused);
    }

    @GetMapping("/unattended/runs")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public Map<String, Object> listUnattendedRuns(
            @RequestParam(required = false) String planSource,
            @RequestParam(required = false) Long alarmId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return aiopsService.listUnattendedRuns(planSource, alarmId, page, size);
    }

    @GetMapping("/unattended/runs/{id}")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public Map<String, Object> getUnattendedRun(@PathVariable Long id) {
        return aiopsService.getUnattendedRun(id);
    }

    @PostMapping("/unattended/runs/{id}/retry")
    @PreAuthorize("hasAuthority('aiops:write') or hasAuthority('alarms:handle')")
    public Map<String, Object> retryUnattendedRun(@PathVariable Long id) {
        return aiopsService.retryUnattendedRun(id);
    }

    @GetMapping("/playbook")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public Map<String, Object> playbook(@RequestParam(required = false) Long deviceId,
                                        @RequestParam(required = false) Long alarmId) {
        return aiopsService.playbook(deviceId, alarmId);
    }

    @GetMapping("/timeline")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public Map<String, Object> timeline(@RequestParam(required = false) Long alarmId,
                                        @RequestParam(required = false) Long deviceId) {
        return aiopsService.timeline(alarmId, deviceId);
    }

    @PostMapping("/playbook/execute")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:handle') or hasAuthority('configs:write')")
    public Map<String, Object> executePlaybook(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            body = Map.of();
        }
        String action = body.get("action") != null ? String.valueOf(body.get("action")) : "";
        Long deviceId = parseLong(body.get("deviceId"));
        Long alarmId = parseLong(body.get("alarmId"));
        boolean confirmed = false;
        if (body.get("confirmed") instanceof Boolean b) {
            confirmed = b;
        } else if (body.get("confirmed") != null) {
            confirmed = "true".equalsIgnoreCase(String.valueOf(body.get("confirmed")));
        }
        String question = body.get("question") != null ? String.valueOf(body.get("question")) : null;
        return aiopsService.runPlaybookStep(action, deviceId, alarmId, confirmed, question);
    }

    @GetMapping("/trends")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('performance:read')")
    public List<Map<String, Object>> trends() {
        return aiopsService.capacityTrends();
    }

    @GetMapping("/alarms/{id}/context")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public Map<String, Object> alarmContext(@PathVariable Long id) {
        return aiopsService.alarmContext(id);
    }

    @GetMapping("/workbench/focus")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public Map<String, Object> workbenchFocus(@RequestParam(required = false) Long alarmId,
                                              @RequestParam(required = false) Long deviceId) {
        return aiopsService.workbenchFocus(alarmId, deviceId);
    }

    @PostMapping("/assistant")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public Map<String, Object> assistant(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            body = Map.of();
        }
        String question = body.get("question") != null ? String.valueOf(body.get("question")) : "";
        Long deviceId = parseLong(body.get("deviceId"));
        Long alarmId = parseLong(body.get("alarmId"));
        return aiopsService.ask(question, deviceId, alarmId);
    }

    @PostMapping("/feedback")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:handle')")
    public ResponseEntity<AiopsFeedback> feedback(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            body = Map.of();
        }
        String targetType = body.get("targetType") != null ? String.valueOf(body.get("targetType")) : "suggestion";
        String targetId = body.get("targetId") != null ? String.valueOf(body.get("targetId")) : null;
        Boolean useful = parseUseful(body.get("useful"));
        String comment = body.get("comment") != null ? String.valueOf(body.get("comment")) : null;
        return ResponseEntity.ok(aiopsService.saveFeedback(targetType, targetId, useful, comment));
    }

    @GetMapping("/feedback")
    @PreAuthorize("hasAuthority('aiops:read') or hasRole('ADMIN')")
    public List<AiopsFeedback> listFeedback() {
        return aiopsService.recentFeedback();
    }

    @GetMapping("/feedback/stats")
    @PreAuthorize("hasAuthority('aiops:read') or hasRole('ADMIN')")
    public Map<String, Object> feedbackStats() {
        return aiopsService.feedbackStats();
    }

    @GetMapping("/policy")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('performance:read')")
    public Map<String, Object> getPolicy() {
        return aiopsService.getPolicy();
    }

    @PutMapping("/policy")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('configs:write')")
    public Map<String, Object> updatePolicy(@RequestBody(required = false) Map<String, Object> body) {
        return aiopsService.updatePolicy(body);
    }

    @PostMapping("/webhook/test")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('configs:write')")
    public Map<String, Object> testWebhook() {
        return aiopsService.testWebhook();
    }

    @PostMapping("/actions/ack-secondary")
    @PreAuthorize("hasAuthority('alarms:handle') or hasAuthority('aiops:read') or hasRole('ADMIN')")
    public Map<String, Object> ackSecondary(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            body = Map.of();
        }
        Long deviceId = parseLong(body.get("deviceId"));
        Long alarmId = parseLong(body.get("alarmId"));
        boolean confirmed = false;
        if (body.get("confirmed") instanceof Boolean b) {
            confirmed = b;
        } else if (body.get("confirmed") != null) {
            confirmed = "true".equalsIgnoreCase(String.valueOf(body.get("confirmed")));
        }
        return aiopsService.ackSecondaryAction(deviceId, alarmId, confirmed);
    }

    @PostMapping("/actions/dispose-incident")
    @PreAuthorize("hasAuthority('alarms:handle') or hasAuthority('aiops:read') or hasRole('ADMIN')")
    public Map<String, Object> disposeIncident(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            body = Map.of();
        }
        Long deviceId = parseLong(body.get("deviceId"));
        Long alarmId = parseLong(body.get("alarmId"));
        boolean confirmed = false;
        if (body.get("confirmed") instanceof Boolean b) {
            confirmed = b;
        } else if (body.get("confirmed") != null) {
            confirmed = "true".equalsIgnoreCase(String.valueOf(body.get("confirmed")));
        }
        return aiopsService.disposeIncidentAction(alarmId, deviceId, confirmed);
    }

    @PostMapping("/actions/refresh-offline")
    @PreAuthorize("hasAuthority('devices:write') or hasAuthority('aiops:read') or hasRole('ADMIN')")
    public Map<String, Object> refreshOffline(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            body = Map.of();
        }
        boolean confirmed = false;
        if (body.get("confirmed") instanceof Boolean b) {
            confirmed = b;
        } else if (body.get("confirmed") != null) {
            confirmed = "true".equalsIgnoreCase(String.valueOf(body.get("confirmed")));
        }
        return aiopsService.refreshOfflineAction(confirmed);
    }

    @PostMapping("/actions/backup")
    @PreAuthorize("hasAuthority('configs:write')")
    public Map<String, Object> backupAction(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            body = Map.of();
        }
        Long deviceId = parseLong(body.get("deviceId"));
        boolean confirmed = Boolean.TRUE.equals(parseUseful(body.get("confirmed")));
        // parseUseful treats null as true — override for confirm flag
        if (body.get("confirmed") == null) {
            confirmed = false;
        } else if (body.get("confirmed") instanceof Boolean b) {
            confirmed = b;
        } else {
            confirmed = "true".equalsIgnoreCase(String.valueOf(body.get("confirmed")));
        }
        String reason = body.get("reason") != null ? String.valueOf(body.get("reason")) : null;
        return aiopsService.backupAction(deviceId, confirmed, reason);
    }

    @PostMapping("/actions/restore")
    @PreAuthorize("hasAuthority('configs:write')")
    public Map<String, Object> restoreAction(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            body = Map.of();
        }
        Long deviceId = parseLong(body.get("deviceId"));
        boolean confirmed = false;
        if (body.get("confirmed") instanceof Boolean b) {
            confirmed = b;
        } else if (body.get("confirmed") != null) {
            confirmed = "true".equalsIgnoreCase(String.valueOf(body.get("confirmed")));
        }
        String reason = body.get("reason") != null ? String.valueOf(body.get("reason")) : null;
        return aiopsService.restoreAction(deviceId, confirmed, reason);
    }

    private static Long parseLong(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s) || "undefined".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean parseUseful(Object raw) {
        if (raw == null) {
            return Boolean.TRUE;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(raw).trim();
        if ("false".equalsIgnoreCase(s) || "0".equals(s) || "no".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }
}
