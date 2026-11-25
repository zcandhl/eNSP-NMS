package com.ensp.nms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 连通性探测去抖：连续成功才判恢复并关告警，连续失败才判离线。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Data
@Component
@ConfigurationProperties(prefix = "nms.probe")
public class NmsProbeProperties {

    private int onlineAfterSuccesses = 2;

    private int offlineAfterFailures = 2;
}
