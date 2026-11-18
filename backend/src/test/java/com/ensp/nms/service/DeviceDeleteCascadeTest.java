package com.ensp.nms.service;

import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.DevicePort;
import com.ensp.nms.entity.PerformanceAlert;
import com.ensp.nms.entity.PerformanceData;
import com.ensp.nms.entity.TopologyLink;
import com.ensp.nms.entity.TopologyNode;
import com.ensp.nms.repository.DevicePortRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.PerformanceAlertRepository;
import com.ensp.nms.repository.PerformanceDataRepository;
import com.ensp.nms.repository.TopologyLinkRepository;
import com.ensp.nms.repository.TopologyNodeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：删除设备时必须先清干净所有引用 device 的子表，
 * 否则会报 "Cannot delete or update a parent row: a foreign key constraint fails
 * (`ensp_nms`.`performance_alerts`, CONSTRAINT ... FOREIGN KEY (`device_id`))"。
 *
 * H2（MODE=MySQL + ddl-auto=create-drop）会按实体关系生成真实外键，
 * 因此这里的用例能在不连 MySQL 的情况下复现/防住该问题。
 */
@SpringBootTest
@ActiveProfiles("test")
class DeviceDeleteCascadeTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private PerformanceAlertRepository performanceAlertRepository;
    @Autowired
    private PerformanceDataRepository performanceDataRepository;
    @Autowired
    private DevicePortRepository devicePortRepository;
    @Autowired
    private TopologyNodeRepository topologyNodeRepository;
    @Autowired
    private TopologyLinkRepository topologyLinkRepository;

    @Test
    @DisplayName("删除设备：带性能告警/性能数据/端口/拓扑节点的设备应被级联清理且不触发外键约束")
    void deleteDevice_shouldCascadeChildRows() {
        Device device = new Device();
        device.setName("UT-DEL-AR-EXPORT");
        device.setIpAddress("10.99.99.21");
        device.setDeviceType("router");
        device.setStatus("online");
        device = deviceRepository.saveAndFlush(device);

        Long deviceId = device.getId();

        PerformanceAlert alert = new PerformanceAlert();
        alert.setDevice(device);
        alert.setMetric("cpu");
        alert.setLevel("major");
        alert.setMessage("CPU 使用率超阈值");
        alert.setCurrentValue(91.0);
        alert.setThresholdValue(80.0);
        alert.setStatus("active");
        alert.setCreatedAt(LocalDateTime.now());
        performanceAlertRepository.saveAndFlush(alert);

        PerformanceData data = new PerformanceData();
        data.setDevice(device);
        data.setTimestamp(LocalDateTime.now());
        data.setCpuUsage(35.5);
        performanceDataRepository.saveAndFlush(data);

        DevicePort port = new DevicePort();
        port.setDeviceId(deviceId);
        port.setPortName("GigabitEthernet0/0/1");
        devicePortRepository.saveAndFlush(port);

        TopologyNode node = new TopologyNode();
        node.setDeviceId(deviceId);
        topologyNodeRepository.saveAndFlush(node);

        TopologyLink link = new TopologyLink();
        link.setSourceNodeId(deviceId);
        link.setTargetNodeId(deviceId + 1000);
        topologyLinkRepository.saveAndFlush(link);

        // 关键调用：原实现会在这里抛 DataIntegrityViolationException（performance_alerts 外键）
        deviceService.deleteDevice(deviceId);

        assertTrue(deviceRepository.findById(deviceId).isEmpty(), "设备应已删除");
        assertEquals(0, performanceAlertRepository.findByDeviceIdAndStatusOrderByCreatedAtDesc(deviceId, "active").size(),
                "性能告警应被清理");
        assertEquals(0, performanceDataRepository.findByDeviceIdOrderByTimestampDesc(deviceId).size(),
                "性能数据应被清理");
        assertTrue(devicePortRepository.findByDeviceId(deviceId).isEmpty(), "端口记录应被清理");
        assertTrue(topologyNodeRepository.findAllByDeviceId(deviceId).isEmpty(), "拓扑节点应被清理");
        assertTrue(topologyLinkRepository.findBySourceNodeIdOrTargetNodeId(deviceId, deviceId).isEmpty(),
                "拓扑链路应被清理");
    }
}
