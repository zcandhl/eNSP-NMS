package com.ensp.nms.controller;

import com.ensp.nms.entity.PerformanceAlert;
import com.ensp.nms.service.PerformanceAlertService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/performance-alerts")
public class PerformanceAlertController {

    @Autowired
    private PerformanceAlertService alertService;

    @GetMapping("/active")
    @PreAuthorize("hasAuthority('performance:read') or hasAuthority('alarms:read')")
    public ResponseEntity<List<PerformanceAlert>> getActiveAlerts() {
        return ResponseEntity.ok(alertService.getActiveAlerts());
    }

    @GetMapping("/device/{deviceId}")
    @PreAuthorize("hasAuthority('performance:read') or hasAuthority('alarms:read')")
    public ResponseEntity<List<PerformanceAlert>> getDeviceAlerts(
            @PathVariable Long deviceId,
            @RequestParam(defaultValue = "active") String status) {
        return ResponseEntity.ok(alertService.getDeviceAlerts(deviceId, status));
    }

    @GetMapping("/recent")
    @PreAuthorize("hasAuthority('performance:read') or hasAuthority('alarms:read')")
    public ResponseEntity<List<PerformanceAlert>> getRecentAlerts(
            @RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(alertService.getRecentAlerts(hours));
    }

    @PostMapping("/{alertId}/acknowledge")
    @PreAuthorize("hasAuthority('alarms:handle')")
    public ResponseEntity<PerformanceAlert> acknowledgeAlert(@PathVariable Long alertId) {
        PerformanceAlert alert = alertService.acknowledgeAlert(alertId);
        if (alert == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(alert);
    }

    @PostMapping("/{alertId}/resolve")
    @PreAuthorize("hasAuthority('alarms:handle')")
    public ResponseEntity<PerformanceAlert> resolveAlert(@PathVariable Long alertId) {
        PerformanceAlert alert = alertService.resolveAlert(alertId);
        if (alert == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(alert);
    }
}
