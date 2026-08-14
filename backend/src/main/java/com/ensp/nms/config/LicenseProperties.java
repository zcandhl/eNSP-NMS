package com.ensp.nms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 学校交付授权信息（展示与到期提示，不硬锁课堂）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nms.license")
public class LicenseProperties {

    /** 授权学校 / 客户名称 */
    private String customer = "未配置授权客户";

    /** 授权到期日，建议 yyyy-MM-dd；空表示未配置 */
    private String expires = "";

    /** 实例标识（每校一份） */
    private String instanceId = "";

    /** SKU：教学标准版 / 镜像版 等 */
    private String sku = "教学标准版";
}
