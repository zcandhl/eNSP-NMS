package com.ensp.nms.service;

import com.ensp.nms.entity.Permission;
import com.ensp.nms.entity.Role;
import com.ensp.nms.repository.PermissionRepository;
import com.ensp.nms.repository.RoleRepository;
import com.ensp.nms.security.RbacCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RbacSeedService {

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;

    /**
     * 幂等：补齐权限目录与预置角色，并同步预置角色的默认权限（仅当角色权限为空时写入默认集，
     * 避免覆盖管理员在界面上手动调整过的权限）。ADMIN 始终对齐全量权限。
     */
    @Transactional
    public void ensureRbacSeeded() {
        Map<String, Permission> byName = ensurePermissions();
        ensureRoles(byName);
        log.info("RBAC 初始化完成：权限 {} 项，角色 {} 个", byName.size(), roleRepository.count());
    }

    private Map<String, Permission> ensurePermissions() {
        Map<String, Permission> existing = permissionRepository.findAll().stream()
                .collect(Collectors.toMap(Permission::getName, p -> p, (a, b) -> a, LinkedHashMap::new));

        for (RbacCatalog.PermDef def : RbacCatalog.PERMISSIONS) {
            Permission p = existing.get(def.name());
            if (p == null) {
                p = new Permission();
                p.setName(def.name());
                existing.put(def.name(), p);
            }
            p.setDisplayName(def.displayName());
            p.setResource(def.resource());
            p.setAction(def.action());
            p.setDescription(def.description());
            permissionRepository.save(p);
        }
        return existing;
    }

    private void ensureRoles(Map<String, Permission> byName) {
        for (RbacCatalog.RoleDef def : RbacCatalog.ROLES) {
            Role role = roleRepository.findByName(def.name()).orElseGet(() -> {
                Role r = new Role();
                r.setName(def.name());
                return r;
            });
            role.setDisplayName(def.displayName());
            role.setDescription(def.description());

            boolean empty = role.getPermissions() == null || role.getPermissions().isEmpty();
            boolean isAdmin = "ADMIN".equals(def.name());
            if (empty || isAdmin) {
                Set<Permission> perms = def.permissionNames().stream()
                        .map(byName::get)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                role.setPermissions(perms);
            } else {
                // 预置角色增量补齐目录新增权限（如 aiops:read），不移除管理员已调整的权限
                Set<Permission> current = role.getPermissions();
                if (current == null) {
                    current = new LinkedHashSet<>();
                    role.setPermissions(current);
                }
                for (String name : def.permissionNames()) {
                    Permission p = byName.get(name);
                    if (p != null && current.stream().noneMatch(x -> name.equals(x.getName()))) {
                        current.add(p);
                    }
                }
            }
            roleRepository.save(role);
        }
    }
}
