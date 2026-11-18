package com.ensp.nms.service;

import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.Device;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.service.aiops.AiopsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL 验证（默认跳过，需 {@code -Dmysql.it=true}）：
 * <ol>
 *   <li>删除设备 → 该设备的告警在 MySQL 上被真正删除（统计才能归零）；</li>
 *   <li>智能大屏数据看板接口在真实库上可正常聚合（native 查询 / 关联抓取不报错）。</li>
 * </ol>
 * 只使用临时设备 UT-AIOPS-MYSQL 与其临时告警，结束后清理。
 */
@SpringBootTest
@ActiveProfiles("dev")
class AiopsDashboardMySqlTest {

    private static final String TEMP_NAME = "UT-AIOPS-MYSQL";
    private static final String TEMP_IP = "10.99.99.77";

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private AlarmRepository alarmRepository;
    @Autowired
    private AiopsService aiopsService;

    private Long deviceId;

    @BeforeEach
    void requireOptIn() {
        Assumptions.assumeTrue(Boolean.getBoolean("mysql.it"),
                "跳过：需 -Dmysql.it=true 且本地 MySQL 可用");
    }

    @AfterEach
    void cleanup() {
        if (deviceId != null && deviceRepository.existsById(deviceId)) {
            deviceService.deleteDevice(deviceId);
        }
    }

    @Test
    @DisplayName("真实 MySQL：删除设备时告警一并删除，且大屏看板可正常聚合")
    void deleteDevice_purgesAlarms_andDashboardAggregates() {
        Device device = new Device();
        device.setName(TEMP_NAME);
        device.setIpAddress(TEMP_IP);
        device.setDeviceType("switch");
        device.setStatus("online");
        device = deviceRepository.saveAndFlush(device);
        deviceId = device.getId();

        Alarm alarm = new Alarm();
        alarm.setDevice(device);
        alarm.setDeviceIp(TEMP_IP);
        alarm.setTitle("UT 接口断开告警");
        alarm.setSeverity(Alarm.Severity.MAJOR);
        alarm.setStatus(Alarm.Status.ACTIVE);
        alarm.setTrapType("接口告警-接口状态变化");
        alarm.setOccurredAt(LocalDateTime.now());
        alarm = alarmRepository.saveAndFlush(alarm);
        Long alarmId = alarm.getId();

        assertTrue(alarmRepository.findById(alarmId).isPresent(), "临时告警应已入库");

        // 大屏看板必须在真实库上可聚合（含 native 最新指标查询、lazy 关联）
        Map<String, Object> dashboard = aiopsService.dashboard(true);
        assertTrue(dashboard.containsKey("summary"), "看板应含 summary");
        assertTrue(dashboard.containsKey("alarmTrend"), "看板应含 24h 趋势");
        assertTrue(dashboard.containsKey("severityDist"), "看板应含级别分布");
        assertTrue(dashboard.containsKey("capacityTop"), "看板应含资源 Top");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) dashboard.get("summary");
        assertTrue(((Number) summary.get("deviceTotal")).longValue() >= 1, "设备总数应大于 0");
        @SuppressWarnings("unchecked")
        Map<String, Object> trend = (Map<String, Object>) dashboard.get("alarmTrend");
        assertTrue(((List<?>) trend.get("labels")).size() == 24, "趋势应为 24 个小时点");

        // 删除设备：告警应一并删除（而不是置空外键）
        deviceService.deleteDevice(deviceId);
        deviceId = null;

        assertFalse(deviceRepository.findById(device.getId()).isPresent(), "设备应已删除");
        assertFalse(alarmRepository.findById(alarmId).isPresent(), "设备告警应一并删除");
    }
}
