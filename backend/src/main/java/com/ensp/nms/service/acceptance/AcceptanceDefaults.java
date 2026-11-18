package com.ensp.nms.service.acceptance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新建验收方案时的标准六项检查模板。
 *
 * 与示例种子共用同一份定义：种子只是"用模板造了一套示例数据"，
 * 模板本身属于产品能力——用户新建方案时应能一键带出这六项，再按现场增删。
 */
public final class AcceptanceDefaults {

    private AcceptanceDefaults() {
    }

    /**
     * 标准六项（A-07 NTP 默认停用，共 7 条、6 条启用）。
     *
     * @param defaultOwner 检查项负责人：新建方案时绑定当前登录账号；
     *                     种子场景传 "player1" 等，由调用方决定
     */
    public static List<Map<String, Object>> standardChecks(String defaultOwner) {
        String o = (defaultOwner == null || defaultOwner.isBlank()) ? "player4" : defaultOwner;
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(check(1, 1, "A-01", "拓扑符合性", "TOPOLOGY_MATCH", o, "high", false, false,
                Map.of("expectedNodes", 17, "tolerance", 0), "topology.matched == topology.total"));
        list.add(check(2, 2, "A-02", "连通与路由", "OSPF_NEIGHBOR", o, "critical", false, false,
                Map.of("expectedNeighbors", 11, "areaId", "0.0.0.0", "deviceTypes", "router"),
                "ospf.neighborCount >= expectedNeighbors"));
        list.add(check(3, 3, "A-03", "冗余可靠性", "VRRP_ROLE", o, "critical", false, false,
                Map.of("expectMaster", 12, "expectBackup", 10, "deviceTypes", "router,switch"),
                "vrrp.masterCount == expectMaster and vrrp.backupCount == expectBackup"));
        list.add(check(4, 4, "A-04", "安全基线", "COMPLIANCE_SCORE", o, "high", true, false,
                Map.of("minScore", 90), "minComplianceScore >= minScore"));
        list.add(check(5, 5, "A-05", "故障预演", "CUSTOM_COMMAND", o, "medium", true, true,
                Map.of("command", "display interface brief | include down",
                        "matchMode", "contains", "matchValue", "DOWN"),
                "custom.matched == false"));
        list.add(check(6, 6, "A-06", "配置基线", "CONFIG_DIFF", o, "medium", false, false,
                Map.of("baselineName", "交付基线", "allowDiffLines", 0),
                "config.diffLines <= allowDiffLines"));
        Map<String, Object> ntp = check(7, 7, "A-07", "NTP 同步状态", "CUSTOM_COMMAND", o, "low", true, false,
                Map.of("command", "display ntp-service status",
                        "matchMode", "contains", "matchValue", "synchronized"),
                "custom.matched == true");
        ntp.put("enabled", false);
        list.add(ntp);
        return list;
    }

    /** 检查项构造器：字段多，统一在这里拼，保证键顺序稳定 */
    private static Map<String, Object> check(int id, int seq, String code, String title, String type,
                                             String owner, String severity, boolean allowFinding, boolean needConfirm,
                                             Map<String, Object> params, String assertExpr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("seq", seq);
        m.put("code", code);
        m.put("title", title);
        m.put("type", type);
        m.put("owner", owner);
        m.put("severity", severity);
        m.put("enabled", true);
        m.put("allowFinding", allowFinding);
        m.put("needConfirm", needConfirm);
        m.put("params", params);
        m.put("assertExpr", assertExpr);
        return m;
    }
}
