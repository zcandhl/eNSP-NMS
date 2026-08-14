package com.ensp.nms.controller;

import com.ensp.nms.dto.RoleCreateRequest;
import com.ensp.nms.dto.RoleMutationResult;
import com.ensp.nms.dto.RoleUpdateRequest;
import com.ensp.nms.entity.Role;
import com.ensp.nms.security.SecurityUtils;
import com.ensp.nms.service.AuditLogService;
import com.ensp.nms.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('roles:manage', 'users:manage')")
    public ResponseEntity<List<Map<String, Object>>> getAllRoles() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Role role : roleService.getAllRoles()) {
            out.add(toRoleView(role, null));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('roles:manage')")
    public ResponseEntity<?> getRoleById(@PathVariable Long id) {
        return roleService.getRoleById(id)
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(toRoleView(r, null)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('roles:manage')")
    public ResponseEntity<?> createRole(@RequestBody RoleCreateRequest request, Authentication authentication) {
        try {
            RoleMutationResult result = roleService.createRole(request);
            auditRole("create", result.getRole(), "success",
                    "新增角色 " + result.getRole().getName(),
                    result.getAuditDetail(), authentication);
            return ResponseEntity.ok(toRoleView(result.getRole(), result));
        } catch (Exception e) {
            String name = request != null ? request.getName() : null;
            auditRole("create", name, name, "failed",
                    "新增角色失败: " + e.getMessage(), null, authentication);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('roles:manage')")
    public ResponseEntity<?> updateRole(@PathVariable Long id, @RequestBody RoleUpdateRequest request,
                                        Authentication authentication) {
        try {
            RoleMutationResult result = roleService.updateRole(id, request);
            auditRole("update", result.getRole(), "success",
                    "更新角色权限 " + result.getRole().getDisplayName(),
                    result.getAuditDetail(), authentication);
            return ResponseEntity.ok(toRoleView(result.getRole(), result));
        } catch (Exception e) {
            auditRole("update", String.valueOf(id), null, "failed",
                    "更新角色失败: " + e.getMessage(), null, authentication);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('roles:manage')")
    public ResponseEntity<?> deleteRole(@PathVariable Long id, Authentication authentication) {
        try {
            Role role = roleService.getRoleById(id).orElse(null);
            roleService.deleteRole(id);
            if (role != null) {
                auditRole("delete", role, "success", "删除角色 " + role.getName(), null, authentication);
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            auditRole("delete", String.valueOf(id), null, "failed",
                    "删除角色失败: " + e.getMessage(), null, authentication);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private Map<String, Object> toRoleView(Role role, RoleMutationResult mutation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", role.getId());
        m.put("name", role.getName());
        m.put("displayName", role.getDisplayName());
        m.put("description", role.getDescription());
        m.put("permissions", role.getPermissions());
        m.put("preset", roleService.isPresetRole(role.getName()));
        m.put("userCount", role.getId() != null ? roleService.countUsersByRoleId(role.getId()) : 0L);
        if (mutation != null) {
            m.put("affectedUserCount", mutation.getAffectedUserCount());
            m.put("addedPermissions", mutation.getAddedPermissions());
            m.put("removedPermissions", mutation.getRemovedPermissions());
        }
        return m;
    }

    private void auditRole(String action, Role role, String status, String summary, String detail,
                           Authentication authentication) {
        if (role == null) return;
        auditRole(action,
                role.getId() != null ? String.valueOf(role.getId()) : role.getName(),
                role.getDisplayName() != null ? role.getDisplayName() : role.getName(),
                status, summary, detail, authentication);
    }

    private void auditRole(String action, String targetId, String targetName, String status,
                           String summary, String detail, Authentication authentication) {
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("role")
                .action(action)
                .operator(SecurityUtils.resolveOperator(authentication))
                .targetType("role")
                .targetId(targetId)
                .targetName(targetName)
                .status(status)
                .summary(summary)
                .detail(detail)
                .clientIp(AuditLogService.currentClientIp())
                .build());
    }
}
