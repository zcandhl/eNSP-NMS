package com.ensp.nms.service.acceptance;

import java.util.List;
import java.util.Map;

/**
 * 验收检查项类型定义与取值约束。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

public final class AcceptanceCheckTypes {

    private AcceptanceCheckTypes() {
    }

    private static final Map<String, Object> FIELD_DEVICE_TYPES = Map.of(
            "key", "deviceTypes", "label", "设备类型范围（空=全部，逗号分隔：router/switch/ac/other）",
            "kind", "text", "default", "", "placeholder", "如 router 或 router,switch");
    private static final Map<String, Object> FIELD_DEVICE_NAMES = Map.of(
            "key", "deviceNames", "label", "设备名单（空=不限，逗号分隔设备名）",
            "kind", "text", "default", "", "placeholder", "如 核心路由器-01, 接入交换机-01");

    public static final List<Map<String, Object>> ALL = List.of(
            Map.of(
                    "type", "TOPOLOGY_MATCH",
                    "label", "拓扑符合性",
                    "description", "比对当前纳管拓扑节点数与设计方案期望值",
                    "fields", List.of(
                            Map.of("key", "expectedNodes", "label", "期望节点数", "kind", "number", "default", 17, "min", 1),
                            Map.of("key", "tolerance", "label", "允许偏差（台）", "kind", "number", "default", 0, "min", 0)
                    ),
                    "assertHint", "topology.matched == topology.total"
            ),
            Map.of(
                    "type", "REACHABILITY",
                    "label", "连通与可达",
                    "description", "逐节点探测管理地址（SSH 端口）可达性，统计在线比例",
                    "fields", List.of(
                            Map.of("key", "minUpRatio", "label", "最低在线比例", "kind", "number", "default", 1, "min", 0, "max", 1, "step", 0.01),
                            Map.of("key", "timeoutMs", "label", "单点超时（ms）", "kind", "number", "default", 2000, "min", 500),
                            FIELD_DEVICE_TYPES, FIELD_DEVICE_NAMES
                    ),
                    "assertHint", "reachability.up / reachability.total >= minUpRatio"
            ),
            Map.of(
                    "type", "OSPF_NEIGHBOR",
                    "label", "OSPF 邻居",
                    "description", "采集各路由设备 OSPF 邻居状态（display ospf peer brief），统计 Full 数量。建议范围 router，二层接入交换机没有路由功能",
                    "fields", List.of(
                            Map.of("key", "expectedNeighbors", "label", "期望 Full 邻居数", "kind", "number", "default", 11, "min", 0),
                            Map.of("key", "areaId", "label", "区域号", "kind", "text", "default", "0.0.0.0"),
                            FIELD_DEVICE_TYPES, FIELD_DEVICE_NAMES
                    ),
                    "assertHint", "ospf.neighborCount >= expectedNeighbors"
            ),
            Map.of(
                    "type", "VRRP_ROLE",
                    "label", "VRRP 主备分布",
                    "description", "汇总网关设备 VRRP 角色（display vrrp brief），统计主备组数。建议范围 router,switch",
                    "fields", List.of(
                            Map.of("key", "expectMaster", "label", "期望主用组数", "kind", "number", "default", 12, "min", 0),
                            Map.of("key", "expectBackup", "label", "期望备用组数", "kind", "number", "default", 10, "min", 0),
                            FIELD_DEVICE_TYPES, FIELD_DEVICE_NAMES
                    ),
                    "assertHint", "vrrp.masterCount == expectMaster and vrrp.backupCount == expectBackup"
            ),
            Map.of(
                    "type", "DHCP_POOL",
                    "label", "DHCP 地址池",
                    "description", "从运行配置统计地址池数量（ip pool）",
                    "fields", List.of(
                            Map.of("key", "expectedPools", "label", "期望地址池数", "kind", "number", "default", 20, "min", 0),
                            FIELD_DEVICE_TYPES, FIELD_DEVICE_NAMES
                    ),
                    "assertHint", "dhcp.poolCount == expectedPools"
            ),
            Map.of(
                    "type", "VLAN_MATCH",
                    "label", "VLAN 与网关",
                    "description", "统计 Vlanif 数量，并校验网关末位（VRRP 虚地址后缀）是否统一",
                    "fields", List.of(
                            Map.of("key", "expectedVlans", "label", "期望 VLAN 数", "kind", "number", "default", 22, "min", 1),
                            Map.of("key", "gatewaySuffix", "label", "网关末位", "kind", "text", "default", "254"),
                            FIELD_DEVICE_TYPES, FIELD_DEVICE_NAMES
                    ),
                    "assertHint", "vlan.matched == expectedVlans and vlan.actualSuffix == gatewaySuffix"
            ),
            Map.of(
                    "type", "COMPLIANCE_SCORE",
                    "label", "配置合规评分",
                    "description", "按合规规则对每台设备配置打分，取最低分判定",
                    "fields", List.of(
                            Map.of("key", "minScore", "label", "最低合规分", "kind", "number", "default", 90, "min", 0, "max", 100),
                            FIELD_DEVICE_TYPES, FIELD_DEVICE_NAMES
                    ),
                    "assertHint", "minComplianceScore >= minScore"
            ),
            Map.of(
                    "type", "CONFIG_DIFF",
                    "label", "配置基线比对",
                    "description", "将当前运行配置与最新备份基线逐行比对，统计差异行数",
                    "fields", List.of(
                            Map.of("key", "baselineName", "label", "基线名称", "kind", "text", "default", "交付基线"),
                            Map.of("key", "allowDiffLines", "label", "允许差异行数", "kind", "number", "default", 0, "min", 0),
                            FIELD_DEVICE_TYPES, FIELD_DEVICE_NAMES
                    ),
                    "assertHint", "config.diffLines <= allowDiffLines"
            ),
            Map.of(
                    "type", "CUSTOM_COMMAND",
                    "label", "自定义命令检查",
                    "description", "下发一条 display 只读命令，按匹配规则判定。信息中心老师可用它自行扩展检查项",
                    "fields", List.of(
                            Map.of("key", "command", "label", "只读命令", "kind", "text", "default", "display ntp-service status", "placeholder", "必须以 display 开头"),
                            Map.of("key", "matchMode", "label", "匹配方式", "kind", "select", "default", "contains",
                                    "options", List.of(
                                            Map.of("label", "包含", "value", "contains"),
                                            Map.of("label", "不包含", "value", "notContains"),
                                            Map.of("label", "正则匹配", "value", "regex"),
                                            Map.of("label", "数值大于", "value", "gt"),
                                            Map.of("label", "数值小于", "value", "lt")
                                    )),
                            Map.of("key", "matchValue", "label", "匹配内容", "kind", "text", "default", "synchronized"),
                            Map.of("key", "timeoutMs", "label", "超时（ms）", "kind", "number", "default", 30000, "min", 1000)
                    ),
                    "assertHint", "custom.matched == true"
            )
    );
}
