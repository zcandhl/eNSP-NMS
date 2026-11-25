package com.ensp.nms.config;

import com.ensp.nms.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Web 安全过滤链、密码编码器、跨域与接口授权规则配置。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Value("${auth.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/system/info").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/auth/change-password").authenticated()
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // native单端口部署时由后端托管前端构建目录，仅静态资源可匿名访问
                .requestMatchers("/", "/index.html", "/favicon.ico", "/assets/**").permitAll()

                .requestMatchers("/api/users/**").hasAuthority("users:manage")
                .requestMatchers("/api/roles/**", "/api/permissions/**").hasAnyAuthority("roles:manage", "users:manage")

                .requestMatchers(HttpMethod.GET, "/api/audit-logs", "/api/audit-logs/**")
                    .hasAuthority("audit:read")

                .requestMatchers("/api/snmp/**").hasAuthority("system:test")

                .requestMatchers(HttpMethod.GET, "/api/devices/discover/**").hasAuthority("devices:discover")
                .requestMatchers(HttpMethod.GET, "/api/devices", "/api/devices/**").hasAuthority("devices:read")
                .requestMatchers(HttpMethod.POST, "/api/devices/discover", "/api/devices/discover/**").hasAuthority("devices:discover")
                .requestMatchers(HttpMethod.POST, "/api/devices/**").hasAuthority("devices:write")
                .requestMatchers(HttpMethod.PUT, "/api/devices/**").hasAuthority("devices:write")
                .requestMatchers(HttpMethod.DELETE, "/api/devices/**").hasAuthority("devices:write")
                .requestMatchers(HttpMethod.GET, "/api/device-groups/**")
                    .hasAnyAuthority("devices:read", "configs:read")
                .requestMatchers("/api/device-groups/**")
                    .hasAnyAuthority("devices:write", "configs:write")

                .requestMatchers(HttpMethod.GET, "/api/alarms", "/api/alarms/**").hasAuthority("alarms:read")
                .requestMatchers(HttpMethod.PUT, "/api/alarms/**").hasAnyAuthority("alarms:handle", "alarms:write")
                .requestMatchers(HttpMethod.POST, "/api/alarms/batch-delete").hasAuthority("alarms:write")
                .requestMatchers(HttpMethod.POST, "/api/alarms/batch-acknowledge", "/api/alarms/batch-clear")
                    .hasAnyAuthority("alarms:handle", "alarms:write")
                .requestMatchers(HttpMethod.POST, "/api/alarms/**").hasAnyAuthority("alarms:handle", "alarms:write")
                .requestMatchers(HttpMethod.DELETE, "/api/alarms/**").hasAuthority("alarms:write")

                .requestMatchers(HttpMethod.GET,
                        "/api/configs/**", "/api/config-templates/**",
                        "/api/config-change-logs/**", "/api/backup-schedules/**")
                    .hasAuthority("configs:read")
                .requestMatchers(HttpMethod.POST, "/api/configs/test-ssh/**")
                    .hasAnyAuthority("configs:write", "webssh:connect")
                .requestMatchers(
                        "/api/configs/**", "/api/config-templates/**",
                        "/api/config-change-logs/**", "/api/backup-schedules/**")
                    .hasAuthority("configs:write")

                .requestMatchers(HttpMethod.GET, "/api/topology", "/api/topology/**").hasAuthority("topology:read")
                .requestMatchers("/api/topology/**").hasAuthority("topology:write")

                .requestMatchers(HttpMethod.GET, "/api/performance/**", "/api/performance-alerts/**")
                    .hasAuthority("performance:read")
                .requestMatchers(HttpMethod.POST, "/api/performance-alerts/**")
                    .hasAuthority("alarms:handle")

                // 验收工作台：读=查看权限，写=配置写权限
                .requestMatchers(HttpMethod.GET, "/api/acceptance/**")
                    .hasAnyAuthority("configs:read", "devices:read")
                .requestMatchers("/api/acceptance/**")
                    .hasAnyAuthority("configs:write", "devices:write")

                .requestMatchers("/api/webssh/**").hasAuthority("webssh:connect")

                .requestMatchers("/api/**").authenticated()
                // 未在上面显式放行的路径一律拒绝，避免默认放行遗留接口
                .anyRequest().denyAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"message\":\"未登录或登录已过期\",\"status\":401}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"message\":\"无权限\",\"status\":403}");
                })
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
