package com.ensp.nms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 连通性探测去抖：连续成功才判恢复/关告警，连续失败才判离线。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nms.probe")
public class NmsProbeProperties {

    /** 连续探测成功次数达到后，才标记 online 并清除连通性告警（实验室默认 2） */
    private int onlineAfterSuccesses = 2;

    /** 连续探测失败次数达到后，才标记 offline（与历史行为一致，默认 2） */
    private int offlineAfterFailures = 2;

    public void applyFrom(NmsProbeProperties other) {
        if (other == null) {
            return;
        }
        if (other.onlineAfterSuccesses > 0) {
            this.onlineAfterSuccesses = other.onlineAfterSuccesses;
        }
        if (other.offlineAfterFailures > 0) {
            this.offlineAfterFailures = other.offlineAfterFailures;
        }
    }
}
