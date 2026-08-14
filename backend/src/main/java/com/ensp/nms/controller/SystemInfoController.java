package com.ensp.nms.controller;

import com.ensp.nms.config.LicenseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemInfoController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final LicenseProperties licenseProperties;

    @Value("${spring.application.name:ensp-nms}")
    private String appName;

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        String expires = licenseProperties.getExpires() == null ? "" : licenseProperties.getExpires().trim();
        Boolean expired = null;
        Integer daysRemaining = null;
        if (!expires.isEmpty()) {
            try {
                LocalDate end = LocalDate.parse(expires, DAY);
                LocalDate today = LocalDate.now();
                expired = today.isAfter(end);
                daysRemaining = (int) (end.toEpochDay() - today.toEpochDay());
            } catch (DateTimeParseException ignored) {
                // 格式异常时仅原样展示，不判定到期
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("product", "eNSP NMS");
        body.put("appName", appName);
        body.put("version", "1.0.0");
        body.put("edition", "lab");
        body.put("ossLicense", "Apache-2.0");
        body.put("sku", licenseProperties.getSku());
        body.put("customer", licenseProperties.getCustomer());
        body.put("expires", expires);
        body.put("expired", expired);
        body.put("daysRemaining", daysRemaining);
        body.put("instanceId", licenseProperties.getInstanceId() == null ? "" : licenseProperties.getInstanceId());
        return ResponseEntity.ok(body);
    }
}
