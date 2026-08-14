package com.ensp.nms.controller;

import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.DevicePort;
import com.ensp.nms.entity.PerformanceData;
import com.ensp.nms.repository.DevicePortRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.PerformanceDataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 教学/联调：写入模拟性能点。
 */
@Slf4j
@RestController
@RequestMapping("/api/mock")
public class MockDataController {

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private DevicePortRepository devicePortRepository;

    @Autowired
    private PerformanceDataRepository performanceDataRepository;

    private final Random random = new Random();

    @PostMapping("/performance/{deviceId}")
    public ResponseEntity<Map<String, Object>> generateMockPerformance(@PathVariable Long deviceId) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "设备不存在"));
        }

        PerformanceData systemData = new PerformanceData(device, LocalDateTime.now());
        double cpuUsage = 20 + random.nextDouble() * 60;
        double memoryUsage = 30 + random.nextDouble() * 50;
        long memoryTotal = 4L * 1024 * 1024;
        long memoryUsed = (long) (memoryTotal * memoryUsage / 100);

        systemData.setCpuUsage(Math.round(cpuUsage * 100.0) / 100.0);
        systemData.setMemoryUsage(Math.round(memoryUsage * 100.0) / 100.0);
        systemData.setMemoryTotal(memoryTotal);
        systemData.setMemoryUsed(memoryUsed);
        systemData.setCpuSource(PerformanceData.SOURCE_SIMULATED);
        systemData.setMemorySource(PerformanceData.SOURCE_SIMULATED);
        performanceDataRepository.save(systemData);

        List<DevicePort> devicePorts = devicePortRepository.findByDeviceId(deviceId);
        List<PerformanceData> portDataList = new ArrayList<>();

        if (devicePorts.isEmpty()) {
            log.info("设备 {} 无端口记录，跳过生成端口性能数据", deviceId);
        } else {
            int portIndex = 0;
            for (DevicePort devicePort : devicePorts) {
                portIndex++;
                String portName = devicePort.getPortName();
                if (portName == null || portName.isEmpty()) {
                    portName = "Port-" + portIndex;
                }

                PerformanceData portData = new PerformanceData(device, LocalDateTime.now());
                portData.setPortIndex(portIndex);
                portData.setPortName(portName);
                long inBytes = (long) (random.nextDouble() * 1000000000);
                long outBytes = (long) (random.nextDouble() * 800000000);
                portData.setIfInOctets(inBytes);
                portData.setIfOutOctets(outBytes);
                portData.setIfInRate(1000000 + random.nextDouble() * 5000000);
                portData.setIfOutRate(800000 + random.nextDouble() * 4000000);
                performanceDataRepository.save(portData);
                portDataList.add(portData);
            }
        }

        log.info("为设备 {} 生成了模拟性能数据", deviceId);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "模拟数据生成成功");
        result.put("systemData", systemData);
        result.put("portData", portDataList);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/performance")
    public ResponseEntity<?> generateMockPerformanceForAll() {
        List<Device> devices = deviceRepository.findAll();
        int count = 0;
        for (Device device : devices) {
            try {
                generateMockPerformance(device.getId());
                count++;
            } catch (Exception e) {
                log.error("为设备 {} 生成模拟数据失败", device.getId(), e);
            }
        }
        return ResponseEntity.ok(Map.of("message", "为 " + count + " 台设备生成了模拟性能数据"));
    }
}
