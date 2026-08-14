package com.ensp.nms.service;

import com.ensp.nms.dto.RoleCreateRequest;
import com.ensp.nms.dto.RoleMutationResult;
import com.ensp.nms.dto.RoleUpdateRequest;
import com.ensp.nms.entity.Permission;
import com.ensp.nms.entity.Role;
import com.ensp.nms.repository.RoleRepository;
import com.ensp.nms.repository.UserRepository;
import com.ensp.nms.security.RbacCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private static final Set<String> PRESET_ROLES = RbacCatalog.ROLES.stream()
            .map(RbacCatalog.RoleDef::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    private final RoleRepository roleRepository;
    private final PermissionService permissionService;
    private final UserRepository userRepository;
    private final AuthorityVersionService authorityVersionService;

    public List<Role> getAllRoles() {
        return roleRepository.findAll();
    }

    public Optional<Role> getRoleById(Long id) {
        return roleRepository.findById(id);
    }

    public Optional<Role> getRoleByName(String name) {
        return roleRepository.findByName(name);
    }

    public boolean isPresetRole(String name) {
        return name != null && PRESET_ROLES.contains(name.trim().toUpperCase());
    }

    public long countUsersByRoleId(Long roleId) {
        return userRepository.countByRoleId(roleId);
    }

    @Transactional
    public RoleMutationResult createRole(RoleCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        Role role = new Role();
        role.setName(request.getName());
        role.setDisplayName(request.getDisplayName());
        role.setDescription(request.getDescription());
        Role saved = createRoleEntity(role);

        List<Long> permIds = request.getPermissionIds() != null ? request.getPermissionIds() : List.of();
        List<String> added = List.of();
        if (!permIds.isEmpty()) {
            List<Permission> permissions = permissionService.findByIds(permIds);
            ensureAdminGuards(saved.getName(), permissions);
            saved.setPermissions(new HashSet<>(permissions));
            saved = roleRepository.save(saved);
            added = formatPermLabels(permissions);
            authorityVersionService.bump();
        }

        String detail = buildAuditDetail(saved, 0, added, List.of());
        return RoleMutationResult.builder()
                .role(saved)
                .affectedUserCount(0)
                .addedPermissions(added)
                .removedPermissions(List.of())
                .auditDetail(detail)
                .build();
    }

    /** 兼容旧调用：仅建角色不赋权 */
    @Transactional
    public Role createRole(Role role) {
        return createRoleEntity(role);
    }

    private Role createRoleEntity(Role role) {
        if (role.getName() == null || role.getName().isBlank()) {
            throw new IllegalArgumentException("角色编码不能为空");
        }
        String name = role.getName().trim().toUpperCase();
        if (!name.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw new IllegalArgumentException("角色编码须为英文大写、数字或下划线，2~32 位");
        }
        if (roleRepository.findByName(name).isPresent()) {
            throw new IllegalArgumentException("角色编码已存在: " + name);
        }
        role.setName(name);
        if (role.getDisplayName() == null || role.getDisplayName().isBlank()) {
            role.setDisplayName(name);
        }
        return roleRepository.save(role);
    }

    @Transactional
    public RoleMutationResult updateRole(Long id, RoleUpdateRequest request) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("角色不存在"));

        if (request.getDisplayName() != null) {
            role.setDisplayName(request.getDisplayName());
        }
        if (request.getDescription() != null) {
            role.setDescription(request.getDescription());
        }

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        boolean permsChanged = false;

        if (request.getPermissionIds() != null) {
            Set<Long> oldIds = role.getPermissions() == null
                    ? Set.of()
                    : role.getPermissions().stream()
                    .map(Permission::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            List<Permission> permissions = permissionService.findByIds(request.getPermissionIds());
            ensureAdminGuards(role.getName(), permissions);
            Set<Long> newIds = permissions.stream()
                    .map(Permission::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            Set<Long> addedIds = new LinkedHashSet<>(newIds);
            addedIds.removeAll(oldIds);
            Set<Long> removedIds = new LinkedHashSet<>(oldIds);
            removedIds.removeAll(newIds);

            Map<Long, Permission> byId = indexPermissions(role.getPermissions(), permissions);
            added = formatPermLabels(addedIds.stream().map(byId::get).filter(Objects::nonNull).toList());
            removed = formatPermLabels(removedIds.stream().map(byId::get).filter(Objects::nonNull).toList());

            role.setPermissions(new HashSet<>(permissions));
            permsChanged = !addedIds.isEmpty() || !removedIds.isEmpty();
        }

        Role saved = roleRepository.save(role);
        long affected = countUsersByRoleId(saved.getId());
        if (permsChanged) {
            authorityVersionService.bump();
        }

        String detail = buildAuditDetail(saved, affected, added, removed);
        return RoleMutationResult.builder()
                .role(saved)
                .affectedUserCount(affected)
                .addedPermissions(added)
                .removedPermissions(removed)
                .auditDetail(detail)
                .build();
    }

    /** 兼容旧接口 */
    @Transactional
    public Role updateRole(Long id, Role roleDetails) {
        RoleUpdateRequest req = new RoleUpdateRequest();
        req.setDisplayName(roleDetails.getDisplayName());
        req.setDescription(roleDetails.getDescription());
        if (roleDetails.getPermissions() != null) {
            req.setPermissionIds(roleDetails.getPermissions().stream()
                    .map(Permission::getId)
                    .filter(Objects::nonNull)
                    .toList());
        }
        return updateRole(id, req).getRole();
    }

    @Transactional
    public void deleteRole(Long id) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("角色不存在"));
        if (isPresetRole(role.getName())) {
            throw new IllegalArgumentException("系统预置角色不可删除: " + role.getName());
        }
        long bound = userRepository.countByRoleId(id);
        if (bound > 0) {
            throw new IllegalArgumentException("该角色仍绑定 " + bound + " 个用户，请先调整用户角色后再删除");
        }
        roleRepository.deleteById(id);
        authorityVersionService.bump();
    }

    private void ensureAdminGuards(String roleName, List<Permission> permissions) {
        if (!"ADMIN".equals(roleName)) {
            return;
        }
        Set<String> names = permissions.stream().map(Permission::getName).collect(Collectors.toSet());
        if (!names.contains("users:manage") || !names.contains("roles:manage")) {
            throw new IllegalArgumentException("系统管理员角色必须保留「用户管理」和「角色权限」");
        }
    }

    private static Map<Long, Permission> indexPermissions(Set<Permission> a, List<Permission> b) {
        Map<Long, Permission> m = new HashMap<>();
        if (a != null) {
            for (Permission p : a) {
                if (p != null && p.getId() != null) m.put(p.getId(), p);
            }
        }
        if (b != null) {
            for (Permission p : b) {
                if (p != null && p.getId() != null) m.put(p.getId(), p);
            }
        }
        return m;
    }

    private static List<String> formatPermLabels(List<Permission> permissions) {
        return permissions.stream()
                .filter(Objects::nonNull)
                .map(p -> {
                    String dn = p.getDisplayName() != null && !p.getDisplayName().isBlank()
                            ? p.getDisplayName() : p.getName();
                    return dn + " (" + p.getName() + ")";
                })
                .toList();
    }

    public static String buildAuditDetail(Role role, long affectedUsers,
                                          List<String> added, List<String> removed) {
        StringBuilder sb = new StringBuilder();
        String label = role.getDisplayName() != null ? role.getDisplayName() : role.getName();
        sb.append("角色：").append(label).append(" (").append(role.getName()).append(")\n");
        sb.append("影响用户：").append(affectedUsers).append('\n');
        if (added != null && !added.isEmpty()) {
            sb.append("新增权限：").append(String.join("；", added)).append('\n');
        } else {
            sb.append("新增权限：（无）\n");
        }
        if (removed != null && !removed.isEmpty()) {
            sb.append("移除权限：").append(String.join("；", removed));
        } else {
            sb.append("移除权限：（无）");
        }
        return sb.toString().trim();
    }
}
