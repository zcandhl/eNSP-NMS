package com.ensp.nms.service;

import com.ensp.nms.entity.TopologyLink;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.TopologyLinkRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

/**
 * 真实拓扑发现验证（默认跳过）：
 * <pre>mvn -Dtest=TopologyDiscoverSshTest -Dmysql.it=true test</pre>
 * clearLinks=true 清掉 ARP 兜底产生的垃圾链路后，走 SSH 采集 display lldp neighbor brief 重建链路。
 * 期望：链路两端端口均为真实接口名（GE0/0/x），无 "unknown"/"Port-n" 假链路；
 * 核心互联 1 条 + 核心-接入 20 条 + 核心-AR 2 条 + 核心-AC 2 条 ≈ 25 条（以实际为准）。
 */
@SpringBootTest
@ActiveProfiles("dev")
class TopologyDiscoverSshTest {

    @Autowired
    private TopologyDiscoveryService topologyDiscoveryService;

    @Autowired
    private TopologyLinkRepository topologyLinkRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @BeforeEach
    void requireOptIn() {
        Assumptions.assumeTrue(Boolean.getBoolean("mysql.it"),
                "跳过：需 -Dmysql.it=true 且本地 MySQL 可用");
    }

    @Test
    @DisplayName("clearLinks 后 SSH LLDP 重建拓扑：链路端口应为真实接口名")
    void rediscoverTopologyViaSsh() {
        Map<String, Object> result = topologyDiscoveryService.discoverTopology(Map.of(
                "clearLinks", true,
                "method", "lldp"));

        System.out.println("发现方式=" + result.get("discoveryMethod") + " 主方式=" + result.get("primaryMethod"));
        System.out.println("新发现链路=" + result.get("discoveredLinks")
                + " 清理旧链路=" + result.get("clearedLinks")
                + " 未匹配邻居=" + (result.get("unmatchedNeighbors") == null ? 0 : ((List<?>) result.get("unmatchedNeighbors")).size()));
        System.out.println("message=" + result.get("message"));

        List<TopologyLink> links = topologyLinkRepository.findAll();
        System.out.println("库内链路总数=" + links.size());
        int bad = 0;
        for (TopologyLink link : links) {
            String src = deviceRepository.findById(link.getSourceNodeId()).map(d -> d.getName()).orElse("?" + link.getSourceNodeId());
            String dst = deviceRepository.findById(link.getTargetNodeId()).map(d -> d.getName()).orElse("?" + link.getTargetNodeId());
            String sp = link.getSourcePort();
            String tp = link.getTargetPort();
            System.out.printf("  %-12s [%s] <-> %-12s [%s] (%s)%n",
                    src, sp == null ? "?" : sp, dst, tp == null ? "?" : tp, link.getBandwidth());
            boolean badPort = (sp == null || sp.isBlank() || sp.startsWith("Port-") || sp.equals("unknown"))
                    || (tp == null || tp.isBlank() || tp.startsWith("Port-") || tp.equals("unknown"));
            if (badPort) {
                bad++;
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, bad, "不应残留 unknown/Port-n 假端口链路");
    }
}
