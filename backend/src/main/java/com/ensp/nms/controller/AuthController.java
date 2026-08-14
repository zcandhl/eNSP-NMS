package com.ensp.nms.controller;

import com.ensp.nms.dto.LoginRequest;
import com.ensp.nms.entity.Role;
import com.ensp.nms.entity.User;
import com.ensp.nms.security.JwtService;
import com.ensp.nms.security.SecurityUtils;
import com.ensp.nms.service.AuditLogService;
import com.ensp.nms.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final AuditLogService auditLogService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String clientIp = AuditLogService.currentClientIp(httpRequest);
        String username = request.getUsername();

        User user = userService.getUserByUsername(username).orElse(null);
        if (user != null && userService.isLoginLocked(user)) {
            auditLogService.record(AuditLogService.AuditRecord.builder()
                    .module("auth")
                    .action("login")
                    .operator(username)
                    .targetType("user")
                    .targetId(String.valueOf(user.getId()))
                    .targetName(user.getRealName() != null ? user.getRealName() : username)
                    .status("failed")
                    .summary("登录失败：账号已锁定")
                    .clientIp(clientIp)
                    .build());
            return ResponseEntity.status(403).body(Map.of(
                    "message", "账号已锁定，请 " + UserService.LOCK_MINUTES + " 分钟后再试，或联系管理员解锁",
                    "code", "ACCOUNT_LOCKED"
            ));
        }

        if (user == null || !userService.verifyPassword(request.getPassword(), user.getPassword())) {
            if (user != null) {
                userService.recordLoginFailure(user);
            }
            auditLogService.record(AuditLogService.AuditRecord.builder()
                    .module("auth")
                    .action("login")
                    .operator(username != null ? username : "unknown")
                    .targetType("user")
                    .targetId(username)
                    .targetName(username)
                    .status("failed")
                    .summary("登录失败：用户名或密码错误")
                    .clientIp(clientIp)
                    .build());
            String msg = "用户名或密码错误";
            if (user != null) {
                User refreshed = userService.getUserByUsername(username).orElse(user);
                if (userService.isLoginLocked(refreshed)) {
                    msg = "连续失败次数过多，账号已锁定 " + UserService.LOCK_MINUTES + " 分钟";
                } else {
                    int fails = refreshed.getFailedLoginCount() == null ? 0 : refreshed.getFailedLoginCount();
                    int remain = Math.max(0, UserService.MAX_FAILED_LOGINS - fails);
                    if (remain > 0 && remain <= 2) {
                        msg = "用户名或密码错误，还可尝试 " + remain + " 次";
                    }
                }
            }
            return ResponseEntity.status(401).body(Map.of("message", msg));
        }
        if (!"active".equalsIgnoreCase(user.getStatus())) {
            auditLogService.record(AuditLogService.AuditRecord.builder()
                    .module("auth")
                    .action("login")
                    .operator(username)
                    .targetType("user")
                    .targetId(String.valueOf(user.getId()))
                    .targetName(user.getRealName() != null ? user.getRealName() : username)
                    .status("failed")
                    .summary("登录失败：账号已禁用")
                    .clientIp(clientIp)
                    .build());
            return ResponseEntity.status(403).body(Map.of("message", "账号已禁用"));
        }

        userService.recordLoginSuccess(user);
        String token = jwtService.generateToken(user);
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("auth")
                .action("login")
                .operator(user.getRealName() != null && !user.getRealName().isBlank()
                        ? user.getRealName() : username)
                .targetType("user")
                .targetId(String.valueOf(user.getId()))
                .targetName(user.getRealName() != null ? user.getRealName() : username)
                .status("success")
                .summary("登录成功")
                .clientIp(clientIp)
                .build());
        return ResponseEntity.ok(buildUserBody(user, token));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(Authentication authentication, HttpServletRequest httpRequest) {
        if (authentication != null && authentication.isAuthenticated()) {
            auditLogService.record(AuditLogService.AuditRecord.builder()
                    .module("auth")
                    .action("logout")
                    .operator(SecurityUtils.resolveOperator(authentication))
                    .targetType("user")
                    .targetId(authentication.getName())
                    .targetName(authentication.getName())
                    .status("success")
                    .summary("登出")
                    .clientIp(AuditLogService.currentClientIp(httpRequest))
                    .build());
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("message", "未登录"));
        }
        User user = userService.getUserByUsername(authentication.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "用户不存在"));
        }
        // 重新签发 JWT，避免 RBAC 权限变更后前端已刷新权限、接口仍用旧 token 导致 403
        return ResponseEntity.ok(buildUserBody(user, jwtService.generateToken(user)));
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
            Authentication authentication,
            @RequestBody Map<String, String> body
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("message", "未登录"));
        }
        try {
            userService.changeOwnPassword(
                    authentication.getName(),
                    body.get("oldPassword"),
                    body.get("newPassword")
            );
            User user = userService.getUserByUsername(authentication.getName()).orElseThrow();
            return ResponseEntity.ok(buildUserBody(user, jwtService.generateToken(user)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private Map<String, Object> buildUserBody(User user, String token) {
        Map<String, Object> body = new HashMap<>();
        if (token != null) {
            body.put("token", token);
        }
        body.put("username", user.getUsername());
        body.put("realName", user.getRealName());
        body.put("email", user.getEmail());
        body.put("roles", user.getRoles() == null
                ? java.util.List.of()
                : user.getRoles().stream().map(Role::getName).collect(Collectors.toList()));
        body.put("permissions", JwtService.collectPermissions(user));
        body.put("mustChangePassword", Boolean.TRUE.equals(user.getMustChangePassword()));
        return body;
    }
}
