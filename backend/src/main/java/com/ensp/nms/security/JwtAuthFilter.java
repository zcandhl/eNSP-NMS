package com.ensp.nms.security;

import com.ensp.nms.service.AuthorityVersionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AuthorityVersionService authorityVersionService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            if (jwtService.isValid(token) && SecurityContextHolder.getContext().getAuthentication() == null) {
                // /auth/me 允许用旧版本换发新 JWT；其余接口拒绝过期权限版本
                boolean refreshEndpoint = isAuthorityRefreshPath(request);
                long tokenVer = jwtService.getAuthorityVersion(token);
                long serverVer = authorityVersionService.current();
                if (!refreshEndpoint && tokenVer != serverVer) {
                    writeUnauthorized(response, "AUTHORITY_CHANGED", "权限已变更，请重新登录");
                    return;
                }
                String username = jwtService.getUsername(token);
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                for (String role : jwtService.getRoles(token)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                }
                for (String perm : jwtService.getPermissions(token)) {
                    authorities.add(new SimpleGrantedAuthority(perm));
                }
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(username, null, authorities);
                Map<String, Object> details = new HashMap<>();
                String realName = jwtService.getRealName(token);
                if (realName != null && !realName.isBlank()) {
                    details.put("realName", realName);
                }
                auth.setDetails(details);
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAuthorityRefreshPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return false;
        // 兼容 context-path
        return path.endsWith("/api/auth/me") || path.endsWith("/auth/me");
    }

    private void writeUnauthorized(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
        response.getWriter().write(body);
    }
}
