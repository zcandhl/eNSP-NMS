package com.ensp.nms.controller;

import com.ensp.nms.entity.Device;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.ssh.SshClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/webssh")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class WebSshController {

    private final DeviceRepository deviceRepository;
    private final SshClient sshClient;

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> executeCommand(@RequestBody Map<String, Object> request) {
        try {
            Long deviceId = Long.valueOf(request.get("deviceId").toString());
            String command = request.get("command").toString();
            
            Device device = deviceRepository.findById(deviceId)
                    .orElseThrow(() -> new RuntimeException("Device not found"));
            
            if (device.getSshUsername() == null || device.getSshPassword() == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "SSH credentials not configured"
                ));
            }
            
            String output = sshClient.executeCommand(
                device.getIpAddress(),
                device.getSshPort(),
                device.getSshUsername(),
                device.getSshPassword(),
                command
            );
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "output", output
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", e.getMessage()
            ));
        }
    }
}
