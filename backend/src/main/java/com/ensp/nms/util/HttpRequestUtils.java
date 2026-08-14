package com.ensp.nms.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从 HTTP 请求解析客户端 IP（支持反向代理 X-Forwarded-For）。
 */
public final class HttpRequestUtils {

    private HttpRequestUtils() {
    }

    public static String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
