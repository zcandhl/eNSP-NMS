package com.ensp.nms.controller;

import com.ensp.nms.snmp.SnmpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/snmp")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SnmpController {

    private final SnmpClient snmpClient;

    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> testSnmp(
            @RequestParam String ip,
            @RequestParam(defaultValue = "161") int port,
            @RequestParam(defaultValue = "public") String community) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            Map<String, String> sysDesc = snmpClient.get(ip, port, community, "1.3.6.1.2.1.1.1.0");
            Map<String, String> sysName = snmpClient.get(ip, port, community, "1.3.6.1.2.1.1.5.0");
            Map<String, String> sysUptime = snmpClient.get(ip, port, community, "1.3.6.1.2.1.1.3.0");
            
            Map<String, Object> data = new HashMap<>();
            data.put("description", sysDesc.values().iterator().hasNext() ? sysDesc.values().iterator().next() : "N/A");
            data.put("name", sysName.values().iterator().hasNext() ? sysName.values().iterator().next() : "N/A");
            data.put("uptime", sysUptime.values().iterator().hasNext() ? sysUptime.values().iterator().next() : "N/A");
            data.put("reachable", !sysDesc.isEmpty());
            
            response.put("success", true);
            response.put("data", data);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }
}

