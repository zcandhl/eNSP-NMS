package com.ensp.nms.event;

/**
 * 告警创建领域事件，发布后供关联与监听逻辑订阅。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

public record AlarmCreatedEvent(Long alarmId, Long deviceId, boolean mergedRepeat) {
}
