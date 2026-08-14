package com.ensp.nms.controller;

import com.ensp.nms.entity.User;
import com.ensp.nms.security.SecurityUtils;
import com.ensp.nms.service.AuditLogService;
import com.ensp.nms.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('users:manage')")
public class UserController {

    private final UserService userService;
    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllUsers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : userService.getAllUsers()) {
            out.add(toUserView(u));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getUserById(@PathVariable Long id) {
        return userService.getUserById(id)
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(toUserView(u)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/effective-permissions")
    public ResponseEntity<?> getEffectivePermissions(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(Map.of(
                    "groups", userService.getEffectivePermissions(id),
                    "codes", userService.getUserById(id)
                            .map(userService::collectPermissionCodes)
                            .orElse(List.of())
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody User user, Authentication authentication) {
        try {
            User saved = userService.createUser(user);
            auditUser("create", saved, "success", "新增用户 " + saved.getUsername(), authentication);
            return ResponseEntity.ok(toUserView(saved));
        } catch (Exception e) {
            auditUser("create", user.getUsername(), null, "failed",
                    "新增用户失败: " + e.getMessage(), authentication);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody User user,
                                        Authentication authentication) {
        try {
            User saved = userService.updateUser(id, user);
            auditUser("update", saved, "success", "编辑用户 " + saved.getUsername(), authentication);
            return ResponseEntity.ok(toUserView(saved));
        } catch (Exception e) {
            auditUser("update", String.valueOf(id), user.getUsername(), "failed",
                    "编辑用户失败: " + e.getMessage(), authentication);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<?> updatePassword(@PathVariable Long id, @RequestBody Map<String, String> request,
                                            Authentication authentication) {
        try {
            String newPassword = request.get("newPassword");
            userService.updatePassword(id, newPassword);
            userService.getUserById(id).ifPresent(u ->
                    auditUser("update_password", u, "success", "重置用户密码 " + u.getUsername(), authentication));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            auditUser("update_password", String.valueOf(id), null, "failed",
                    "重置密码失败: " + e.getMessage(), authentication);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/unlock")
    public ResponseEntity<?> unlockUser(@PathVariable Long id, Authentication authentication) {
        try {
            User saved = userService.unlockUser(id);
            auditUser("unlock", saved, "success", "解锁用户 " + saved.getUsername(), authentication);
            return ResponseEntity.ok(toUserView(saved));
        } catch (Exception e) {
            auditUser("unlock", String.valueOf(id), null, "failed",
                    "解锁失败: " + e.getMessage(), authentication);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id, Authentication authentication) {
        try {
            User target = userService.getUserById(id).orElse(null);
            userService.deleteUser(id);
            if (target != null) {
                auditUser("delete", target, "success", "删除用户 " + target.getUsername(), authentication);
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            auditUser("delete", String.valueOf(id), null, "failed",
                    "删除用户失败: " + e.getMessage(), authentication);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private Map<String, Object> toUserView(User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("username", user.getUsername());
        m.put("email", user.getEmail());
        m.put("phone", user.getPhone());
        m.put("realName", user.getRealName());
        m.put("status", user.getStatus());
        m.put("description", user.getDescription());
        m.put("roles", user.getRoles());
        m.put("createdAt", user.getCreatedAt());
        m.put("updatedAt", user.getUpdatedAt());
        m.put("lastLoginAt", user.getLastLoginAt());
        m.put("failedLoginCount", user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount());
        m.put("lockedUntil", user.getLockedUntil());
        boolean locked = user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now());
        m.put("locked", locked);
        return m;
    }

    private void auditUser(String action, User user, String status, String summary, Authentication authentication) {
        if (user == null) return;
        auditUser(action, String.valueOf(user.getId()), user.getUsername(), status, summary, authentication);
    }

    private void auditUser(String action, String targetId, String targetName, String status, String summary,
                           Authentication authentication) {
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("user")
                .action(action)
                .operator(SecurityUtils.resolveOperator(authentication))
                .targetType("user")
                .targetId(targetId)
                .targetName(targetName)
                .status(status)
                .summary(summary)
                .clientIp(AuditLogService.currentClientIp())
                .build());
    }
}
