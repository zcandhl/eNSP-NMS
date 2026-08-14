package com.ensp.nms.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtService jwtService;

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        String token = extractToken(request);
        if (token == null || !jwtService.isValid(token)) {
            log.warn("WebSocket handshake rejected: missing or invalid token");
            return false;
        }
        List<String> permissions = jwtService.getPermissions(token);
        if (!permissions.contains("webssh:connect")) {
            log.warn("WebSocket handshake rejected: missing webssh:connect for {}",
                    jwtService.getUsername(token));
            return false;
        }
        attributes.put("username", jwtService.getUsername(token));
        attributes.put("roles", jwtService.getRoles(token));
        attributes.put("permissions", permissions);
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // no-op
    }

    private String extractToken(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String queryToken = servletRequest.getServletRequest().getParameter("token");
            if (queryToken != null && !queryToken.isBlank()) {
                return queryToken.trim();
            }
        }
        List<String> auth = request.getHeaders().get("Authorization");
        if (auth != null && !auth.isEmpty()) {
            String header = auth.get(0);
            if (header != null && header.startsWith("Bearer ")) {
                return header.substring(7).trim();
            }
        }
        return null;
    }
}
