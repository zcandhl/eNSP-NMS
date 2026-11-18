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
                    "message", "该设备未配置远程管理账号或密码"
                ));
            }

            // AC 类设备走 telnet（其 SSH 服务在 eNSP 中不可用），其余走 SSH
            String protocol = device.isTelnetManagement()
                    ? SshClient.PROTOCOL_TELNET : SshClient.PROTOCOL_SSH;
            int port = device.getSshPort() != null && device.getSshPort() > 0
                    ? device.getSshPort() : SshClient.defaultPort(protocol);

            String output = sshClient.executeCommand(
                device.getIpAddress(),
                port,
                device.getSshUsername(),
                device.getSshPassword(),
                command,
                protocol
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
