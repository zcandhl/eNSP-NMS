package com.ensp.nms.scheduler;

import com.ensp.nms.service.aiops.AlarmEscalationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每 5 分钟扫描超时待处理告警并按策略升级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlarmEscalationScheduler {

    private final AlarmEscalationService alarmEscalationService;

    @Scheduled(fixedDelayString = "${nms.aiops.escalation-scan-ms:300000}", initialDelay = 60000)
    public void scan() {
        try {
            alarmEscalationService.runOnce();
        } catch (Exception e) {
            log.warn("告警超时升级扫描失败: {}", e.getMessage());
        }
    }
}
