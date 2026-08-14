package com.ensp.nms.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

/**
 * 从当前登录上下文解析操作人展示名（优先 realName）。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static String currentOperator() {
        return resolveOperator(SecurityContextHolder.getContext().getAuthentication());
    }

    public static String resolveOperator(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return "system";
        }
        Object details = authentication.getDetails();
        if (details instanceof Map<?, ?> map) {
            Object realName = map.get("realName");
            if (realName != null && !String.valueOf(realName).isBlank()) {
                return String.valueOf(realName).trim();
            }
        }
        String name = authentication.getName();
        return name != null && !name.isBlank() ? name : "system";
    }
}
