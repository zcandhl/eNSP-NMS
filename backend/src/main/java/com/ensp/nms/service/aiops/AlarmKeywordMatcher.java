package com.ensp.nms.service.aiops;

import com.ensp.nms.entity.Alarm;

import java.util.Locale;

/**
 * 关联与 RCA 共用的离线/链路告警关键词（避免两边规则不一致）。
 */
public final class AlarmKeywordMatcher {

    private AlarmKeywordMatcher() {
    }

    public static boolean isLinkOrDown(Alarm a) {
        if (a == null) {
            return false;
        }
        return isLinkOrDownBlob(nullToEmpty(a.getTitle()) + " " + nullToEmpty(a.getDescription())
                + " " + nullToEmpty(a.getTrapType()));
    }

    public static boolean isLinkOrDownBlob(String raw) {
        String blob = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        // 勿匹配过宽词，避免性能告警被误标
        return blob.contains("offline") || blob.contains("离线")
                || blob.contains("unreachable") || blob.contains("不可达")
                || blob.contains("link down") || blob.contains("linkdown")
                || blob.contains("链路中断") || blob.contains("链路 down")
                || blob.contains("接口断开") || blob.contains("接口down") || blob.contains("端口down")
                || blob.contains("链路告警") || blob.contains("接口恢复")
                || blob.contains("device down") || blob.contains("设备宕")
                || (blob.contains("down") && (blob.contains("link") || blob.contains("接口")
                || blob.contains("端口") || blob.contains("链路") || blob.contains("设备")));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
