package com.ensp.nms.device;

import java.util.Locale;

/**
 * 设备类型：用于图标、能力矩阵与差异化监控。
 */
public enum DeviceType {
    ROUTER("router"),
    SWITCH("switch"),
    FIREWALL("firewall"),
    AC("ac"),
    AP("ap"),
    PC("pc"),
    SERVER("server"),
    OTHER("other");

    private final String code;

    DeviceType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static DeviceType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return OTHER;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (DeviceType type : values()) {
            if (type.code.equals(normalized)) {
                return type;
            }
        }
        return OTHER;
    }
}
