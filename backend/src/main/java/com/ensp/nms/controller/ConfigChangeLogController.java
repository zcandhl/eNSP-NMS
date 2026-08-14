package com.ensp.nms.controller;

import com.ensp.nms.entity.ConfigChangeLog;
import com.ensp.nms.service.ConfigChangeLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config-change-logs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ConfigChangeLogController {

    private final ConfigChangeLogService configChangeLogService;

    @GetMapping
    public ResponseEntity<List<ConfigChangeLog>> getAllLogs() {
        return ResponseEntity.ok(configChangeLogService.getAllLogs());
    }

    @GetMapping("/query")
    public ResponseEntity<Page<ConfigChangeLog>> queryLogs(
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String changeType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(configChangeLogService.queryLogs(
                deviceId, changeType, status, keyword, from, to, pageable));
    }

    @GetMapping("/device/{deviceId}")
    public ResponseEntity<List<ConfigChangeLog>> getLogsByDeviceId(@PathVariable Long deviceId) {
        return ResponseEntity.ok(configChangeLogService.getLogsByDeviceId(deviceId));
    }

    @GetMapping("/{id:\\d+}")
    public ResponseEntity<ConfigChangeLog> getLogById(@PathVariable Long id) {
        return configChangeLogService.getLogById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ConfigChangeLog> createLog(@RequestBody ConfigChangeLog log) {
        return ResponseEntity.ok(configChangeLogService.createLog(log));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ConfigChangeLog> updateLogStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        try {
            String status = request.get("status");
            String result = request.get("result");
            return ResponseEntity.ok(configChangeLogService.updateLogStatus(id, status, result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLog(@PathVariable Long id) {
        configChangeLogService.deleteLog(id);
        return ResponseEntity.ok().build();
    }
}
