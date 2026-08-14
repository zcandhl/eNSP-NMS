package com.ensp.nms.controller;

import com.ensp.nms.entity.PerformanceData;
import com.ensp.nms.service.PerformanceMonitorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequestMapping("/api/performance")
@CrossOrigin(origins = "*")
public class PerformanceController {

    @Autowired
    private PerformanceMonitorService performanceMonitorService;

    @GetMapping("/device/{deviceId}/latest")
    public ResponseEntity<PerformanceData> getLatestPerformance(@PathVariable Long deviceId) {
        // 新设备尚未完成首轮采集时返回 null（200），避免前端把 404 当成加载失败
        PerformanceData data = performanceMonitorService.getLatestPerformance(deviceId);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/device/{deviceId}/history")
    public ResponseEntity<List<PerformanceData>> getPerformanceHistory(
            @PathVariable Long deviceId,
            @RequestParam String start,
            @RequestParam String end
    ) {
        LocalDateTime startTime = parseDateTime(start);
        LocalDateTime endTime = parseDateTime(end);
        List<PerformanceData> data = performanceMonitorService.getPerformanceHistory(deviceId, startTime, endTime);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/device/{deviceId}/ports")
    public ResponseEntity<List<PerformanceData>> getLatestPortMetrics(@PathVariable Long deviceId) {
        List<PerformanceData> data = performanceMonitorService.getLatestPortMetrics(deviceId);
        return ResponseEntity.ok(data);
    }

    /** 兼容本地时间与带 Z 的 ISO 字符串，避免历史查询因解析失败返回空 */
    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now();
        }
        String v = value.trim();
        try {
            if (v.endsWith("Z") || v.contains("+") || v.matches(".*[+-]\\d{2}:\\d{2}$")) {
                return LocalDateTime.ofInstant(Instant.parse(v), ZoneId.systemDefault());
            }
            return LocalDateTime.parse(v, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(v.replace(" ", "T"), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("无效时间参数: " + value);
            }
        }
    }
}
