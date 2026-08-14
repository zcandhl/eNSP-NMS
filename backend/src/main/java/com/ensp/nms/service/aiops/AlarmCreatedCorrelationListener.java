package com.ensp.nms.service.aiops;

import com.ensp.nms.event.AlarmCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 新告警提交后尽快重算关联，缩短「告警已出、事件列表未更新」的空窗。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlarmCreatedCorrelationListener {

    private final AlarmCorrelationService alarmCorrelationService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAlarmCreated(AlarmCreatedEvent event) {
        if (event == null) {
            return;
        }
        // 合并重复只更新计数，列表已有该事件，可不重跑全量关联
        if (event.mergedRepeat()) {
            return;
        }
        try {
            alarmCorrelationService.correlateOpenAlarms();
            log.debug("新告警后已触发关联 alarmId={} deviceId={}", event.alarmId(), event.deviceId());
        } catch (Exception e) {
            log.warn("新告警后关联失败: {}", e.getMessage());
        }
    }
}
