package com.ensp.nms.scheduler;

import com.ensp.nms.repository.TopologyLinkRepository;
import com.ensp.nms.service.TopologyDiscoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实验室拓扑链路自动扫描：定时对在线设备做 LLDP/CDP/华为LLDP + ARP 发现。
 * 默认开启；可通过 nms.topology.auto-discover-enabled=false 关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopologyDiscoveryScheduler {

    private final TopologyDiscoveryService topologyDiscoveryService;
    private final TopologyLinkRepository topologyLinkRepository;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${nms.topology.auto-discover-enabled:true}")
    private boolean enabled;

    @Value("${nms.topology.auto-discover-only-when-empty:false}")
    private boolean onlyWhenEmpty;

    @Scheduled(fixedDelayString = "${nms.topology.auto-discover-ms:300000}", initialDelay = 90000)
    public void autoDiscover() {
        if (!enabled) return;
        if (!running.compareAndSet(false, true)) {
            log.debug("拓扑自动发现仍在进行，跳过本轮");
            return;
        }
        try {
            if (onlyWhenEmpty && topologyLinkRepository.count() > 0) {
                return;
            }
            log.info("开始定时拓扑链路发现…");
            Map<String, Object> result = topologyDiscoveryService.discoverTopology(
                    Map.of("method", "both"));
            log.info("定时拓扑发现完成: {}", result.get("message"));
        } catch (Exception e) {
            log.warn("定时拓扑发现失败: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
