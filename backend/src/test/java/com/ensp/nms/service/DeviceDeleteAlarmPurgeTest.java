package com.ensp.nms.service;

import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.Device;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试（用户实测反馈的三类「删不干净 / 删了又回来」问题）：
 *
 * <ol>
 *   <li>删除设备后，该设备的告警必须一并删除，否则告警中心与 AIOps 的
 *       「活动告警 / 今日告警 / 按设备 TopN」永远不归零；</li>
 *   <li>采集线程持有的 detached 实体不能把已删除设备 merge 回来
 *       （原实现 {@code applyProbeResult(Device,...)} 里的 {@code save()} 会复活设备，
 *       表现为「删一台要删好几次」）；</li>
 *   <li>同理，用旧快照 merge 会覆盖刚改过的设备信息，表现为「改很多次都不生效」。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class DeviceDeleteAlarmPurgeTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private ConnectivityRecoveryService connectivityRecoveryService;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private AlarmRepository alarmRepository;

    private Device newDevice(String name, String ip) {
        Device device = new Device();
        device.setName(name);
        device.setIpAddress(ip);
        device.setDeviceType("switch");
        device.setStatus("online");
        return deviceRepository.saveAndFlush(device);
    }

    private Alarm newAlarm(Device device) {
        Alarm alarm = new Alarm();
        alarm.setDevice(device);
        alarm.setDeviceIp(device.getIpAddress());
        alarm.setTitle("接口断开告警 · GigabitEthernet0/0/9");
        alarm.setSeverity(Alarm.Severity.MAJOR);
        alarm.setStatus(Alarm.Status.ACTIVE);
        alarm.setTrapType("接口告警-接口状态变化");
        alarm.setOccurredAt(LocalDateTime.now());
        return alarmRepository.saveAndFlush(alarm);
    }

    @Test
    @DisplayName("删除设备时，该设备的告警一并删除（统计归零）")
    void deleteDevice_shouldPurgeAlarms() {
        Device device = newDevice("UT-ALARM-1", "10.98.0.11");
        Alarm alarm = newAlarm(device);
        Long deviceId = device.getId();
        Long alarmId = alarm.getId();

        assertTrue(alarmRepository.findById(alarmId).isPresent(), "告警应已入库");

        deviceService.deleteDevice(deviceId);

        assertTrue(deviceRepository.findById(deviceId).isEmpty(), "设备应已删除");
        assertTrue(alarmRepository.findById(alarmId).isEmpty(), "设备告警应随设备一起删除");
        assertEquals(0L, alarmRepository.findByDeviceIdOrDeviceIpOrderByOccurredAtDesc(
                deviceId, "10.98.0.11").size(), "不应残留该设备的告警");
    }

    @Test
    @DisplayName("设备删除后，采集线程的探测结果不会把设备「复活」")
    void probeAfterDelete_mustNotResurrectDevice() {
        Device device = newDevice("UT-ALARM-2", "10.98.0.12");
        Long deviceId = device.getId();

        deviceService.deleteDevice(deviceId);
        assertTrue(deviceRepository.findById(deviceId).isEmpty(), "前置条件：设备已删除");

        // 模拟采集线程在本轮开始时拿到了设备快照，删除后才回写探测结果
        ConnectivityRecoveryService.ProbeApplyResult result =
                connectivityRecoveryService.applyProbeResult(deviceId, true, "snmp");

        assertTrue(result.isDeleted(), "应按「设备已删除」处理");
        assertTrue(deviceRepository.findById(deviceId).isEmpty(), "已删除的设备不能被探测结果写回");
        assertEquals(0L, alarmRepository.findByDeviceIdOrDeviceIpOrderByOccurredAtDesc(
                deviceId, "10.98.0.12").size(), "也不应因此产生新告警");
    }

    @Test
    @DisplayName("探测结果按 id 回写，不会用旧快照覆盖刚编辑过的字段")
    void probeMustNotOverwriteEditedFields() {
        Device device = newDevice("UT-ALARM-3", "10.98.0.13");
        Long deviceId = device.getId();

        // 采集线程持有的旧快照（名称为旧值）
        Device stale = new Device();
        stale.setId(deviceId);
        stale.setName("UT-ALARM-3-OLD");
        stale.setIpAddress("10.98.0.13");

        // 用户刚改过名称
        Device edit = new Device();
        edit.setName("UT-ALARM-3-NEW");
        deviceService.updateDevice(deviceId, edit);

        connectivityRecoveryService.applyProbeResult(deviceId, false, "snmp");

        Device after = deviceRepository.findById(deviceId).orElseThrow();
        assertEquals("UT-ALARM-3-NEW", after.getName(), "编辑后的名称不应被采集旧快照覆盖");
        assertEquals("10.98.0.13", after.getIpAddress(), "IP 不应被改写");
    }
}
