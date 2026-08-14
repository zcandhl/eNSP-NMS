package com.ensp.nms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 性能阈值（全局默认），可通过 application.yml / 环境变量覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nms.performance.thresholds")
public class PerformanceThresholdProperties {

    private MetricThreshold cpu = new MetricThreshold(70.0, 85.0);
    private MetricThreshold memory = new MetricThreshold(75.0, 90.0);

    @Data
    public static class MetricThreshold {
        /** warning 级别下限 */
        private double warning = 70.0;
        /** danger 级别下限 */
        private double danger = 85.0;

        public MetricThreshold() {
        }

        public MetricThreshold(double warning, double danger) {
            this.warning = warning;
            this.danger = danger;
        }
    }

    public MetricThreshold forMetric(String metric) {
        if ("memory".equalsIgnoreCase(metric)) {
            return memory;
        }
        return cpu;
    }
}
