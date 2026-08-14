package com.ensp.nms.controller;

import com.ensp.nms.service.llm.LlmAssistantService;
import com.ensp.nms.service.llm.LlmSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmController {

    private final LlmSettingsService settingsService;
    private final LlmAssistantService assistantService;

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public ResponseEntity<Map<String, Object>> getSettings() {
        return ResponseEntity.ok(settingsService.toPublicView());
    }

    @PutMapping("/settings")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('configs:write')")
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(settingsService.update(body));
    }

    @PostMapping("/chat")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
        String question = body.get("question") == null ? "" : String.valueOf(body.get("question"));
        Long deviceId = toLong(body.get("deviceId"));
        Long alarmId = toLong(body.get("alarmId"));
        String pagePath = body.get("pagePath") == null ? null : String.valueOf(body.get("pagePath"));
        List<Map<String, String>> history = parseHistory(body.get("history"));
        boolean assist = true;
        if (body.get("assist") instanceof Boolean b) {
            assist = b;
        } else if (body.get("mode") != null) {
            assist = "assist".equalsIgnoreCase(String.valueOf(body.get("mode")));
        }
        if (assist) {
            return ResponseEntity.ok(assistantService.assist(question, deviceId, alarmId, pagePath, history));
        }
        return ResponseEntity.ok(assistantService.chat(question, deviceId, alarmId, pagePath, history));
    }

    @PostMapping("/assist")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:read')")
    public ResponseEntity<Map<String, Object>> assist(@RequestBody Map<String, Object> body) {
        String question = body.get("question") == null ? "" : String.valueOf(body.get("question"));
        Long deviceId = toLong(body.get("deviceId"));
        Long alarmId = toLong(body.get("alarmId"));
        String pagePath = body.get("pagePath") == null ? null : String.valueOf(body.get("pagePath"));
        List<Map<String, String>> history = parseHistory(body.get("history"));
        return ResponseEntity.ok(assistantService.assist(question, deviceId, alarmId, pagePath, history));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseHistory(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Object role = m.get("role");
            Object content = m.get("content");
            if (content == null || String.valueOf(content).isBlank()) continue;
            Map<String, String> row = new java.util.LinkedHashMap<>();
            row.put("role", role == null ? "user" : String.valueOf(role));
            row.put("content", String.valueOf(content));
            out.add(row);
        }
        return out;
    }

    @PostMapping("/test")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('configs:write')")
    public ResponseEntity<Map<String, Object>> test() {
        return ResponseEntity.ok(assistantService.testConnection());
    }

    @PostMapping("/execute-tool")
    @PreAuthorize("hasAuthority('aiops:read') or hasAuthority('alarms:handle') or hasAuthority('configs:write') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> executeTool(@RequestBody Map<String, Object> body) {
        if (body == null) {
            body = Map.of();
        }
        String name = body.get("name") != null ? String.valueOf(body.get("name"))
                : (body.get("tool") != null ? String.valueOf(body.get("tool")) : "");
        boolean confirmed = false;
        if (body.get("confirmed") instanceof Boolean b) {
            confirmed = b;
        } else if (body.get("confirmed") != null) {
            confirmed = "true".equalsIgnoreCase(String.valueOf(body.get("confirmed")));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> args = body.get("args") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : new java.util.LinkedHashMap<>();
        // 兼容扁平参数
        if (!args.containsKey("deviceId") && body.get("deviceId") != null) {
            args.put("deviceId", body.get("deviceId"));
        }
        if (!args.containsKey("alarmId") && body.get("alarmId") != null) {
            args.put("alarmId", body.get("alarmId"));
        }
        return ResponseEntity.ok(assistantService.executeTool(name, args, confirmed));
    }

    private Long toLong(Object v) {
        if (v == null || "".equals(v)) return null;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
