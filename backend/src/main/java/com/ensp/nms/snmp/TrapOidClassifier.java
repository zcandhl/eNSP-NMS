package com.ensp.nms.snmp;

import com.ensp.nms.entity.Alarm;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 snmpTrapOID 映射可读告警类型，并生成运维可读描述（Trap 接收与历史纠偏共用）。
 */
public final class TrapOidClassifier {

    private static final Pattern SNMP_TRAP_OID_LINE = Pattern.compile(
            "(?i)(?:1\\.3\\.6\\.1\\.6\\.3\\.1\\.1\\.4\\.1\\.0|snmpTrapOID)\\s*=\\s*([0-9.]+)");
    private static final Pattern ANY_OSPF_TRAP = Pattern.compile(
            "1\\.3\\.6\\.1\\.2\\.1\\.14\\.16\\.2\\.(\\d+)");
    private static final Pattern KV_LINE = Pattern.compile(
            "^\\s*([^=\\n]+?)\\s*=\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern REPEAT_SUFFIX = Pattern.compile(
            "(?s)(\\n?\\[重复 \\d+ 次.*?\\]\\s*)$");

    private TrapOidClassifier() {
    }

    public record Classified(String title, String type, Alarm.Severity severity) {
    }

    public static String extractTrapOid(String rawData) {
        if (rawData == null || rawData.isBlank()) {
            return null;
        }
        Matcher m = SNMP_TRAP_OID_LINE.matcher(rawData);
        if (m.find()) {
            return normalizeOid(m.group(1));
        }
        Matcher ospf = ANY_OSPF_TRAP.matcher(rawData);
        if (ospf.find()) {
            return "1.3.6.1.2.1.14.16.2." + ospf.group(1);
        }
        return null;
    }

    public static Classified classify(String trapOid) {
        String oid = normalizeOid(trapOid);
        if (oid == null || oid.isBlank()) {
            return null;
        }

        if (oidIs(oid, "1.3.6.1.6.3.1.1.5.1")) {
            return new Classified("设备冷启动", "系统告警-冷启动", Alarm.Severity.MAJOR);
        }
        if (oidIs(oid, "1.3.6.1.6.3.1.1.5.2")) {
            return new Classified("设备热启动", "系统告警-热启动", Alarm.Severity.MAJOR);
        }
        if (oidIs(oid, "1.3.6.1.6.3.1.1.5.3")) {
            return new Classified("接口断开告警", "链路告警-接口断开", Alarm.Severity.CRITICAL);
        }
        if (oidIs(oid, "1.3.6.1.6.3.1.1.5.4")) {
            return new Classified("接口恢复通知", "链路告警-接口恢复", Alarm.Severity.INFO);
        }
        if (oidIs(oid, "1.3.6.1.6.3.1.1.5.5")) {
            return new Classified("认证失败告警", "安全告警-认证失败", Alarm.Severity.WARNING);
        }

        if (oid.contains("1.3.6.1.2.1.14.16.2.")) {
            if (oidIs(oid, "1.3.6.1.2.1.14.16.2.2")) {
                return new Classified("OSPF邻居状态变更", "路由告警-OSPF邻居状态变更", Alarm.Severity.WARNING);
            }
            if (oidIs(oid, "1.3.6.1.2.1.14.16.2.4")) {
                return new Classified("OSPF接口配置错误", "路由告警-OSPF接口配置错误", Alarm.Severity.MAJOR);
            }
            if (oidIs(oid, "1.3.6.1.2.1.14.16.2.6")) {
                return new Classified("OSPF接口认证失败", "路由告警-OSPF认证失败", Alarm.Severity.MAJOR);
            }
            if (oidIs(oid, "1.3.6.1.2.1.14.16.2.12")) {
                return new Classified("OSPF产生LSA", "路由告警-OSPF产生LSA", Alarm.Severity.INFO);
            }
            if (oidIs(oid, "1.3.6.1.2.1.14.16.2.13")) {
                return new Classified("OSPF LSA达到MaxAge", "路由告警-OSPF-LSA-MaxAge", Alarm.Severity.WARNING);
            }
            if (oidIs(oid, "1.3.6.1.2.1.14.16.2.14")) {
                return new Classified("OSPF链路状态库溢出", "路由告警-OSPF-LSDB溢出", Alarm.Severity.CRITICAL);
            }
            if (oidIs(oid, "1.3.6.1.2.1.14.16.2.15")) {
                return new Classified("OSPF链路状态库接近溢出", "路由告警-OSPF-LSDB将满", Alarm.Severity.MAJOR);
            }
            if (oidIs(oid, "1.3.6.1.2.1.14.16.2.16")) {
                return new Classified("OSPF接口状态变更", "路由告警-OSPF接口状态变更", Alarm.Severity.WARNING);
            }
            return new Classified("OSPF路由事件", "路由告警-OSPF", Alarm.Severity.WARNING);
        }

        return null;
    }

    public static boolean needsRefine(String trapType) {
        if (trapType == null || trapType.isBlank()) {
            return true;
        }
        String t = trapType.trim();
        return "通用Trap告警".equals(t) || "通用告警".equals(t) || "设备告警".equals(t);
    }

    /** 旧版分号堆砌 / 含错误「v3用户」等，需要重写描述 */
    public static boolean needsDescriptionRebuild(String description) {
        if (description == null || description.isBlank()) {
            return true;
        }
        String d = description;
        return d.contains("通用Trap告警")
                || d.contains("v3用户:")
                || d.contains("v3用户：")
                || (d.contains("; ") && !d.contains("【摘要】"));
    }

    public static String stripRepeatSuffix(String description) {
        if (description == null) {
            return null;
        }
        return REPEAT_SUFFIX.matcher(description).replaceFirst("").trim();
    }

    public static String extractRepeatSuffix(String description) {
        if (description == null) {
            return null;
        }
        Matcher m = REPEAT_SUFFIX.matcher(description);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * 根据 rawData + 已分类结果生成运维可读描述（不含重复计数后缀）。
     */
    public static String buildReadableDescription(String rawData, Classified classified,
                                                  String peerIp, String agentIp,
                                                  String managedIp, String deviceName) {
        Map<String, String> vb = parseRawBindings(rawData);
        String oid = extractTrapOid(rawData);
        Classified c = classified != null ? classified : classify(oid);
        if (c == null && oid != null) {
            c = classify(oid);
        }

        String title = c != null ? c.title() : "设备Trap事件";
        String type = c != null ? c.type() : "通用Trap告警";
        String summary = summaryForType(type, title, vb);

        StringBuilder sb = new StringBuilder();
        sb.append("【摘要】").append(summary).append('\n');
        sb.append('\n');
        sb.append("【事件】\n");
        sb.append("名称: ").append(title).append('\n');
        sb.append("类型: ").append(type).append('\n');
        if (oid != null && !oid.isBlank()) {
            sb.append("Trap OID: ").append(oid).append('\n');
        }

        // OSPF 关键
        String ospfRid = firstVb(vb, "1.3.6.1.2.1.14.1.1.0", "1.3.6.1.2.1.14.1.1");
        String area = firstVbByPrefix(vb, "1.3.6.1.2.1.14.4.1.1.");
        String lsaType = firstVbByPrefix(vb, "1.3.6.1.2.1.14.4.1.2.");
        String lsId = firstVbByPrefix(vb, "1.3.6.1.2.1.14.4.1.3.");
        String adv = firstVbByPrefix(vb, "1.3.6.1.2.1.14.4.1.4.");
        if (ospfRid != null || area != null || lsId != null || type.contains("OSPF")) {
            sb.append('\n').append("【OSPF】\n");
            if (ospfRid != null) {
                sb.append("路由器ID: ").append(ospfRid).append('\n');
            }
            if (area != null) {
                sb.append("区域ID: ").append(area).append('\n');
            }
            if (lsaType != null) {
                sb.append("LSA类型: ").append(formatLsdbType(lsaType)).append('\n');
            }
            if (lsId != null) {
                sb.append("LSA-ID: ").append(lsId).append('\n');
            }
            if (adv != null) {
                sb.append("LSA通告者: ").append(adv).append('\n');
            }
        }

        // 接口
        String ifName = firstVbByPrefix(vb, "1.3.6.1.2.1.31.1.1.1.1.");
        String ifDescr = firstVbByPrefix(vb, "1.3.6.1.2.1.2.2.1.2.");
        String ifIndex = firstVbByPrefix(vb, "1.3.6.1.2.1.2.2.1.1.");
        String ifOper = firstVbByPrefix(vb, "1.3.6.1.2.1.2.2.1.8.");
        if (ifName != null || ifDescr != null || ifIndex != null || type.contains("接口")) {
            sb.append('\n').append("【接口】\n");
            if (ifName != null) {
                sb.append("名称: ").append(ifName).append('\n');
            } else if (ifDescr != null) {
                sb.append("描述: ").append(ifDescr).append('\n');
            }
            if (ifIndex != null) {
                sb.append("索引: ").append(ifIndex).append('\n');
            }
            if (ifOper != null) {
                sb.append("运行状态: ").append(formatIfOper(ifOper)).append('\n');
            }
        }

        sb.append('\n').append("【报文】\n");
        String ver = extractMeta(rawData, "snmpVersion");
        String pdu = extractMeta(rawData, "pduType");
        if (ver != null || pdu != null) {
            sb.append("协议: ").append(formatProtocol(ver, pdu)).append('\n');
        }
        if (peerIp != null && !peerIp.isBlank()) {
            sb.append("Trap源地址: ").append(peerIp).append('\n');
        }
        if (agentIp != null && !agentIp.isBlank()
                && (peerIp == null || !agentIp.equals(peerIp))) {
            sb.append("Agent地址: ").append(agentIp).append('\n');
        }
        String sysUp = firstVb(vb, "1.3.6.1.2.1.1.3.0");
        if (sysUp != null) {
            sb.append("设备运行时长: ").append(formatUptime(sysUp)).append('\n');
        }
        String sec = extractMeta(rawData, "securityName");
        if (sec != null) {
            if (ver != null && ver.toLowerCase(Locale.ROOT).contains("v3")) {
                sb.append("v3用户: ").append(sec).append('\n');
            } else {
                sb.append("Community: ").append(sec).append('\n');
            }
        }
        if (managedIp != null && !managedIp.isBlank()) {
            sb.append("纳管管理IP: ").append(managedIp).append('\n');
        }
        if (deviceName != null && !deviceName.isBlank()) {
            sb.append("纳管设备: ").append(deviceName).append('\n');
        }

        return sb.toString().trim();
    }

    public static String summaryForType(String type, String title, Map<String, String> vb) {
        if (type == null) {
            type = "";
        }
        if (type.contains("OSPF-LSA-MaxAge") || (title != null && title.contains("MaxAge"))) {
            String lsId = firstVbByPrefix(vb, "1.3.6.1.2.1.14.4.1.3.");
            String adv = firstVbByPrefix(vb, "1.3.6.1.2.1.14.4.1.4.");
            StringBuilder s = new StringBuilder("OSPF 链路状态通告(LSA)已老化到 MaxAge，路由器将刷新或清除该 LSA，可能引起短暂路由震荡。");
            if (lsId != null) {
                s.append(" LSA-ID=").append(lsId).append("。");
            }
            if (adv != null) {
                s.append(" 通告者=").append(adv).append("。");
            }
            return s.toString();
        }
        if (type.contains("OSPF产生LSA")) {
            return "设备新产生或刷新了一条 OSPF LSA，属路由信息同步过程中的正常/提示类事件。";
        }
        if (type.contains("OSPF邻居")) {
            return "OSPF 邻居状态发生变化，请核对邻接是否仍为 Full，以及链路/认证是否正常。";
        }
        if (type.contains("接口断开")) {
            return "本端接口运行状态变为断开(down)，请检查线缆、对端电源及接口配置。";
        }
        if (type.contains("接口恢复")) {
            return "本端接口运行状态已恢复为 up。";
        }
        if (type.contains("冷启动") || type.contains("热启动")) {
            return "设备发生重启类事件，请确认是否计划内操作。";
        }
        return (title != null ? title : "Trap事件") + "。详情见下方字段。";
    }

    private static Map<String, String> parseRawBindings(String rawData) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawData == null) {
            return map;
        }
        Matcher m = KV_LINE.matcher(rawData);
        while (m.find()) {
            String k = m.group(1).trim();
            String v = m.group(2).trim();
            if (!k.isEmpty()) {
                map.put(k, v);
            }
        }
        return map;
    }

    private static String extractMeta(String rawData, String key) {
        if (rawData == null || key == null) {
            return null;
        }
        // snmpVersion=v2c pduType=TRAP(v2)
        Pattern p = Pattern.compile("(?:^|[\\s;])" + Pattern.quote(key) + "=([^\\s;\\n]+)");
        Matcher m = p.matcher(rawData);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String firstVb(Map<String, String> vb, String... keys) {
        if (vb == null) {
            return null;
        }
        for (String k : keys) {
            if (vb.containsKey(k) && vb.get(k) != null && !vb.get(k).isBlank()) {
                return vb.get(k).trim();
            }
        }
        return null;
    }

    private static String firstVbByPrefix(Map<String, String> vb, String prefix) {
        if (vb == null || prefix == null) {
            return null;
        }
        for (Map.Entry<String, String> e : vb.entrySet()) {
            if (e.getKey() != null && e.getKey().startsWith(prefix)
                    && e.getValue() != null && !e.getValue().isBlank()) {
                return e.getValue().trim();
            }
        }
        return null;
    }

    private static String formatProtocol(String ver, String pdu) {
        String v = ver != null ? ver : "?";
        String p = pdu != null ? pdu : "Trap";
        if ("v2c".equalsIgnoreCase(v)) {
            return "SNMPv2c " + p;
        }
        if ("v3".equalsIgnoreCase(v)) {
            return "SNMPv3 " + p;
        }
        if ("v1".equalsIgnoreCase(v)) {
            return "SNMPv1 " + p;
        }
        return v + " " + p;
    }

    private static String formatLsdbType(String type) {
        try {
            int t = Integer.parseInt(type.trim());
            return switch (t) {
                case 1 -> "Router-LSA(1)";
                case 2 -> "Network-LSA(2)";
                case 3 -> "Summary-LSA(3)";
                case 4 -> "ASBR-Summary-LSA(4)";
                case 5 -> "AS-External-LSA(5)";
                case 7 -> "NSSA-LSA(7)";
                default -> "类型(" + t + ")";
            };
        } catch (Exception e) {
            return type;
        }
    }

    private static String formatIfOper(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.toUpperCase(Locale.ROOT).startsWith("INTEGER:")) {
            s = s.substring(s.indexOf(':') + 1).trim();
        }
        return switch (s) {
            case "1" -> "up(1)";
            case "2" -> "down(2)";
            default -> s;
        };
    }

    private static String formatUptime(String timeticks) {
        if (timeticks == null) {
            return "-";
        }
        // 已是 2:16:28.33 或 days 格式则直接用
        String t = timeticks.trim();
        if (t.contains(":") || t.toLowerCase(Locale.ROOT).contains("day")) {
            return t;
        }
        try {
            long ticks = Long.parseLong(t.replaceAll("[^0-9]", ""));
            long totalSec = ticks / 100;
            long days = totalSec / 86400;
            long hours = (totalSec % 86400) / 3600;
            long mins = (totalSec % 3600) / 60;
            long secs = totalSec % 60;
            if (days > 0) {
                return days + "天" + hours + "小时" + mins + "分";
            }
            return hours + "小时" + mins + "分" + secs + "秒";
        } catch (Exception e) {
            return t;
        }
    }

    public static String normalizeOid(String oid) {
        if (oid == null) {
            return "";
        }
        String s = oid.trim();
        if (s.regionMatches(true, 0, "oid:", 0, 4)) {
            s = s.substring(4).trim();
        }
        s = s.replace("\"", "").replace("'", "").replace(" ", "");
        return s;
    }

    private static boolean oidIs(String trapOid, String expected) {
        String n = normalizeOid(trapOid);
        String e = normalizeOid(expected);
        return n.equals(e) || n.endsWith("." + e) || n.endsWith(e);
    }
}
