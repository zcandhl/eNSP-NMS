package com.ensp.nms.event;

/**
 * 新告警入库后发布，供智能运维侧异步关联/刷新。
 */
public record AlarmCreatedEvent(Long alarmId, Long deviceId, boolean mergedRepeat) {
}
