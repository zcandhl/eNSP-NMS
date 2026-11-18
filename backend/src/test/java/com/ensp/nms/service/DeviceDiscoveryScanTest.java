package com.ensp.nms.service;

import com.ensp.nms.dto.DiscoverCandidate;
import com.ensp.nms.dto.DiscoverJobStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 真实网段扫描验证（默认跳过）：
 * <pre>mvn -Dtest=DeviceDiscoveryScanTest -Dmysql.it=true test</pre>
 * 只调用 startScan（不落库、不导入），用于确认"平台自己的发现流程"能扫到设备，
 * 排除"设备侧 SNMP 已通、平台仍扫不到"的可能。扫描对象是管理段 10.20.10.0/24。
 */
@SpringBootTest
@ActiveProfiles("dev")
class DeviceDiscoveryScanTest {

    @Autowired
    private DeviceDiscoveryService discoveryService;

    @BeforeEach
    void requireOptIn() {
        Assumptions.assumeTrue(Boolean.getBoolean("mysql.it"),
                "跳过：需 -Dmysql.it=true 且本地 MySQL 可用");
    }

    @Test
    @DisplayName("平台扫描 10.20.10.0/24：应发现 11 台（6F 故意未配、AC-1 团体不同）")
    void scanManagementSubnet() throws Exception {
        DiscoverJobStatus job = discoveryService.startScan("10.20.10.", 90, "public", 161);
        String jobId = job.getJobId();

        DiscoverJobStatus latest = job;
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2000);
            latest = discoveryService.getJob(jobId).orElse(latest);
            if (latest.getStatus() != DiscoverJobStatus.Status.RUNNING) {
                break;
            }
        }

        System.out.println("扫描状态=" + latest.getStatus() + " 已扫=" + latest.getScanned()
                + "/" + latest.getTotal() + " 命中=" + latest.getFound());
        for (DiscoverCandidate c : latest.getCandidates()) {
            System.out.printf("  %-14s %-12s %-16s 已存在=%s%n",
                    c.getIpAddress(), c.getName(), c.getModel(), c.isAlreadyExists());
        }
        org.junit.jupiter.api.Assertions.assertTrue(latest.getFound() >= 10,
                "至少应发现 10 台（6F 故意未配）");
    }
}
