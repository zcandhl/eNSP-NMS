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

                // 用户 / 角色 / 权限
                .requestMatchers("/api/users/**").hasAuthority("users:manage")
                .requestMatchers("/api/roles/**", "/api/permissions/**").hasAnyAuthority("roles:manage", "users:manage")

                // 操作日志
                .requestMatchers(HttpMethod.GET, "/api/audit-logs", "/api/audit-logs/**")
                    .hasAuthority("audit:read")

                // 测试工具
                .requestMatchers("/api/mock/**").hasAuthority("system:test")
                .requestMatchers("/api/snmp/**").hasAuthority("system:test")

                // 设备
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

                // 告警
                .requestMatchers(HttpMethod.GET, "/api/alarms", "/api/alarms/**").hasAuthority("alarms:read")
                .requestMatchers(HttpMethod.PUT, "/api/alarms/**").hasAnyAuthority("alarms:handle", "alarms:write")
                .requestMatchers(HttpMethod.POST, "/api/alarms/batch-delete").hasAuthority("alarms:write")
                .requestMatchers(HttpMethod.POST, "/api/alarms/batch-acknowledge", "/api/alarms/batch-clear")
                    .hasAnyAuthority("alarms:handle", "alarms:write")
                .requestMatchers(HttpMethod.POST, "/api/alarms/**").hasAnyAuthority("alarms:handle", "alarms:write")
                .requestMatchers(HttpMethod.DELETE, "/api/alarms/**").hasAuthority("alarms:write")

                // 配置
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

                // 拓扑
                .requestMatchers(HttpMethod.GET, "/api/topology", "/api/topology/**").hasAuthority("topology:read")
                .requestMatchers("/api/topology/**").hasAuthority("topology:write")

                // 性能
                .requestMatchers(HttpMethod.GET, "/api/performance/**", "/api/performance-alerts/**")
                    .hasAuthority("performance:read")
                .requestMatchers(HttpMethod.POST, "/api/performance-alerts/**")
                    .hasAuthority("alarms:handle")

                // WebSSH HTTP API
                .requestMatchers("/api/webssh/**").hasAuthority("webssh:connect")

                .requestMatchers("/api/**").authenticated()
                // 静态前端（native 单端口托管 ./web）与其它非 API 资源
                .anyRequest().permitAll()
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
