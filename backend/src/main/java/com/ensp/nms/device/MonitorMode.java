package com.ensp.nms.device;

import java.util.Locale;

public enum MonitorMode {
    SNMP("snmp"),
    ICMP("icmp"),
    AUTO("auto");

    private final String code;

    MonitorMode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static MonitorMode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return AUTO;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (MonitorMode mode : values()) {
            if (mode.code.equals(normalized)) {
                return mode;
            }
        }
        return AUTO;
    }
}
