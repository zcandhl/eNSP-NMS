package com.ensp.nms.service.acceptance;

import java.util.List;
import java.util.Map;

/**
 * 判定准则的“人话”层（Word 报告用），与前端 judgeRule.js 同口径。
 *
 * 把 assertExpr（SpEL）解析成「指标 + 比较 + 目标」并生成中文句子，
 * 例如 ospf.neighborCount >= expectedNeighbors →
 *   当「OSPF 满邻居数」不少于「期望邻居数（方案填 2）」时，判定通过
 *
 * 解析不了（如带除法的表达式）时返回原表达式，报告不丢信息。
 * 注意：指标中文名/单位/参数标签必须与前端 judgeRule.js、AcceptanceCheckTypes 保持一致，
 * 改一处要同步另一处。
 */
public final class AcceptanceRuleText {

    private AcceptanceRuleText() {
    }

    /** 实测指标中文名与单位（key 与 AcceptanceRunService metrics 一致） */
    private static final Map<String, String[]> METRICS = Map.ofEntries(
            Map.entry("topology.total", new String[]{"实测纳管节点数", "台"}),
            Map.entry("topology.links", new String[]{"拓扑链路数", "条"}),
            Map.entry("topology.matched", new String[]{"已关联设备数", "台"}),
            Map.entry("reachability.up", new String[]{"管理地址可达台数", "台"}),
            Map.entry("reachability.total", new String[]{"纳入检测台数", "台"}),
            Map.entry("ospf.neighborCount", new String[]{"OSPF 满邻居数", "个"}),
            Map.entry("vrrp.masterCount", new String[]{"VRRP 主用组数", "组"}),
            Map.entry("vrrp.backupCount", new String[]{"VRRP 备用组数", "组"}),
            Map.entry("dhcp.poolCount", new String[]{"DHCP 地址池数", "个"}),
            Map.entry("vlan.matched", new String[]{"已配置 VLAN 网关数", "个"}),
            Map.entry("vlan.actualSuffix", new String[]{"网关末位", ""}),
            Map.entry("minComplianceScore", new String[]{"最低合规得分", "分"}),
            Map.entry("config.diffLines", new String[]{"与基线的差异行数", "行"}),
            Map.entry("custom.matched", new String[]{"命令输出是否命中匹配内容", ""}));

    private static final Map<String, String> OPS = Map.of(
            ">=", "不少于", ">", "多于", "==", "等于", "<=", "不超过", "<", "少于", "!=", "不等于");

    /** 运行时指标（裸键）中文名，与前端 judgeRule.js 的 METRIC_KEY_LABEL 同口径 */
    private static final Map<String, String> METRIC_KEY_LABEL = Map.ofEntries(
            Map.entry("masterCount", "主用组数"),
            Map.entry("backupCount", "备用组数"),
            Map.entry("neighborCount", "满邻居数"),
            Map.entry("scopedDevices", "检测范围内设备数"),
            Map.entry("excludedDevices", "范围外排除设备数"),
            Map.entry("totalDevices", "纳管设备总数"),
            Map.entry("matchedDevices", "匹配设备数"),
            Map.entry("up", "可达台数"),
            Map.entry("total", "总数"),
            Map.entry("matched", "匹配数"),
            Map.entry("links", "链路数"),
            Map.entry("devices", "设备数"),
            Map.entry("poolCount", "地址池数"),
            Map.entry("actualSuffix", "实测网关末位"),
            Map.entry("diffLines", "差异行数"),
            Map.entry("lowestScore", "最低得分"),
            Map.entry("minComplianceScore", "最低合规得分"),
            Map.entry("command", "检测命令"),
            Map.entry("expected", "期望值"),
            Map.entry("failed", "未通过项"),
            Map.entry("switchoverMs", "倒换耗时(ms)"),
            Map.entry("maxUsage", "最高使用率(%)"),
            Map.entry("noBaseline", "未找到基线"));

    /**
     * 关键实测值摘要：metrics 逐项转「中文名=值」，与前端 metricsText 同口径。
     * 过滤内部 detail 字段；键名映射不到时保留原键，报告不丢信息。
     */
    public static String metricsBrief(Object metricsObj) {
        if (!(metricsObj instanceof Map<?, ?> m) || m.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            if ("detail".equals(k)) continue;
            Object v = e.getValue();
            if (v == null || String.valueOf(v).isEmpty()) continue;
            if (sb.length() > 0) sb.append("；");
            String bare = k.contains(".") ? k.substring(k.lastIndexOf('.') + 1) : k;
            sb.append(METRIC_KEY_LABEL.getOrDefault(bare, k)).append('=').append(v);
        }
        return sb.length() > 0 ? sb.toString() : "—";
    }

    @SuppressWarnings("unchecked")
    public static String describe(Map<String, Object> check) {
        if (check == null) return "";
        String expr = String.valueOf(check.getOrDefault("assertExpr", ""));
        if (expr.isBlank()) return "";
        String type = String.valueOf(check.getOrDefault("type", ""));
        Map<String, Object> params = check.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();

        StringBuilder sentence = new StringBuilder("当");
        String[] parts = expr.split("\\s+and\\s+");
        for (int i = 0; i < parts.length; i++) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^([\\w.]+)\\s*(>=|<=|==|!=|>|<)\\s*(.+)$").matcher(parts[i].trim());
            if (!m.matches()) return expr; // 复杂表达式（如除法）解析不了，原样输出
            String metric = m.group(1), op = m.group(2), right = m.group(3).trim();
            String[] metricDef = METRICS.get(metric);
            if (metricDef == null) return expr;

            if ("custom.matched".equals(metric)) {
                boolean hit = "true".equalsIgnoreCase(right);
                sentence.append(hit ? "命令输出中出现匹配内容" : "命令输出中不出现匹配内容");
            } else {
                sentence.append("「").append(metricDef[0]).append("」")
                        .append(OPS.getOrDefault(op, op)).append("「").append(targetText(type, params, metricDef, right)).append("」");
            }
            if (i < parts.length - 1) sentence.append("，并且");
        }
        return sentence.append("时，判定通过").toString();
    }

    /** 目标侧：优先方案参数中文名（附当前值），其次是指标，最后是字面量 */
    private static String targetText(String type, Map<String, Object> params, String[] metricDef, String right) {
        String paramKey = stripQuotes(right);
        String paramLabel = paramLabel(type, paramKey);
        if (paramLabel != null) {
            Object val = params.get(paramKey);
            return val == null ? paramLabel : paramLabel + "（方案填 " + val + "）";
        }
        if (METRICS.containsKey(right)) {
            return METRICS.get(right)[0];
        }
        if (right.matches("-?\\d+(\\.\\d+)?")) {
            return right + (metricDef[1].isEmpty() ? "" : metricDef[1]);
        }
        return stripQuotes(right);
    }

    private static String stripQuotes(String s) {
        return s.replaceAll("^'|'$", "");
    }

    /** 参数中文名：从 AcceptanceCheckTypes 的 fields 里取 */
    static String paramLabel(String type, String key) {
        for (Map<String, Object> ct : AcceptanceCheckTypes.ALL) {
            if (!type.equals(ct.get("type"))) continue;
            Object fields = ct.get("fields");
            if (!(fields instanceof List<?> list)) continue;
            for (Object o : list) {
                if (o instanceof Map<?, ?> f && key.equals(String.valueOf(f.get("key")))) {
                    return String.valueOf(f.get("label"));
                }
            }
        }
        return null;
    }
}
