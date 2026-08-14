package com.ensp.nms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 告警策略：阅知类 trapType 确认即办结（ACK 后直接 CLEARED）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nms.alarm")
public class NmsAlarmProperties {

    /**
     * 确认即关闭的 trapType 白名单（精确匹配，忽略首尾空白）。
     * 默认不含「路由告警-OSPF邻居状态变更」。
     */
    private List<String> ackClosesTypes = new ArrayList<>(List.of(
            "用户管理-用户登录",
            "用户管理-用户退出",
            "用户管理-登录退出",
            "路由告警-OSPF产生LSA",
            "路由告警-OSPF重传",
            "路由告警-OSPF触发",
            "路由告警-OSPF恢复",
            "路由告警-OSPF-LSA-MaxAge",
            "路由告警-OSPF状态变更",
            "路由告警-OSPF邻居状态变更",
            "交换告警-STP拓扑变更",
            "接口告警-华为接口变更",
            "安全告警-802.1X认证",
            "系统告警-热启动",
            "厂商事件-华为",
            "链路告警-接口恢复",
            "链路告警-BFD恢复",
            "硬件告警-电源恢复",
            "硬件告警-风扇恢复",
            "性能告警-负载恢复",
            "系统告警-启动完成",
            "配置告警-配置保存"
    ));

    public boolean isAckClosesType(String trapType) {
        if (trapType == null || trapType.isBlank() || ackClosesTypes == null || ackClosesTypes.isEmpty()) {
            return false;
        }
        String t = trapType.trim();
        for (String s : ackClosesTypes) {
            if (s != null && t.equals(s.trim())) {
                return true;
            }
        }
        return false;
    }
}
