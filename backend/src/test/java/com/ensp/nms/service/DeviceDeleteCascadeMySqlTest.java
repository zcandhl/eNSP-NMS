package com.ensp.nms.service;

import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.PerformanceAlert;
import com.ensp.nms.entity.PerformanceData;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.PerformanceAlertRepository;
import com.ensp.nms.repository.PerformanceDataRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL 上的删除回归（默认跳过，需显式开启）：
 * <pre>
 * mvn -Dtest=DeviceDeleteCascadeMySqlTest -Dmysql.it=true test
 * </pre>
 * H2 用例证明的是 JPA 删除顺序；本用例证明的是 MySQL 服务器端那三条外键
 * （alarm / performance_alerts / performance_data，均为 NO ACTION）确实不再阻塞删除。
 * 全程只用临时设备 UT-DEL-MYSQL，结束时无论成败都会清理，不触碰真实设备数据。
 */
@SpringBootTest
@ActiveProfiles("dev")
class DeviceDeleteCascadeMySqlTest {

    private static final String TEMP_NAME = "UT-DEL-MYSQL";

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private PerformanceAlertRepository performanceAlertRepository;
    @Autowired
    private PerformanceDataRepository performanceDataRepository;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @BeforeEach
    void requireOptIn() {
        Assumptions.assumeTrue(Boolean.getBoolean("mysql.it"),
                "跳过：需 -Dmysql.it=true 且本地 MySQL 可用");
    }

    @Test
    @DisplayName("真实 MySQL：带性能告警/性能数据的设备可正常删除，不报外键约束")
    void deleteDevice_onRealMySql() {
        Device device = new Device();
        device.setName(TEMP_NAME);
        device.setIpAddress("10.99.99.88");
        device.setDeviceType("router");
        device.setStatus("online");
        Device saved = deviceRepository.saveAndFlush(device);
        Long id = saved.getId();

        try {
            PerformanceAlert alert = new PerformanceAlert();
            alert.setDevice(saved);
            alert.setMetric("cpu");
            alert.setLevel("major");
            alert.setMessage("UT 临时告警");
            alert.setCurrentValue(91.0);
            alert.setThresholdValue(80.0);
            alert.setStatus("active");
            alert.setCreatedAt(LocalDateTime.now());
            performanceAlertRepository.saveAndFlush(alert);

            PerformanceData data = new PerformanceData();
            data.setDevice(saved);
            data.setTimestamp(LocalDateTime.now());
            data.setCpuUsage(12.0);
            performanceDataRepository.saveAndFlush(data);

            // 关键：MySQL 上原实现会抛 "Cannot delete or update a parent row"
            deviceService.deleteDevice(id);

            assertTrue(deviceRepository.findById(id).isEmpty(), "设备应已删除");
            assertEquals(0, performanceAlertRepository.findByDeviceIdAndStatusOrderByCreatedAtDesc(id, "active").size(),
                    "性能告警应被清理");
            assertEquals(0, performanceDataRepository.findByDeviceIdOrderByTimestampDesc(id).size(),
                    "性能数据应被清理");
        } finally {
            // 失败时兜底：先清子表再删父行，避免把垃圾数据留在真实库里。
            // @Modifying 的 bulk delete 必须在事务内执行，测试方法本身无事务，这里显式开一个。
            if (deviceRepository.existsById(id)) {
                transactionTemplate.executeWithoutResult(status -> {
                    performanceAlertRepository.deleteAllByDeviceId(id);
                    performanceDataRepository.deleteByDeviceId(id);
                    deviceRepository.deleteById(id);
                });
            }
        }
    }
}
