package com.ensp.nms.service;

import com.ensp.nms.entity.Permission;
import com.ensp.nms.entity.Role;
import com.ensp.nms.entity.User;
import com.ensp.nms.repository.RoleRepository;
import com.ensp.nms.repository.UserRepository;
import com.ensp.nms.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    public static final int MAX_FAILED_LOGINS = 5;
    public static final int LOCK_MINUTES = 15;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthorityVersionService authorityVersionService;

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }

    public Optional<User> getUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Transactional
    public User createUser(User user) {
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            throw new RuntimeException("用户名不能为空");
        }
        String username = user.getUsername().trim();
        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("用户名已存在");
        }
        validatePassword(user.getPassword());
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (user.getStatus() == null || user.getStatus().isBlank()) {
            user.setStatus("active");
        }
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setRoles(resolveRoles(user.getRoles()));
        if (user.getRoles() == null || user.getRoles().isEmpty()) {
            throw new RuntimeException("请至少分配一个角色");
        }
        return userRepository.save(user);
    }

    @Transactional
    public User updateUser(Long id, User userDetails) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        String currentUsername = currentUsername();
        boolean isSelf = currentUsername != null && currentUsername.equalsIgnoreCase(user.getUsername());

        if (userDetails.getStatus() != null) {
            String newStatus = userDetails.getStatus().trim();
            if (isSelf && !"active".equalsIgnoreCase(newStatus)) {
                throw new RuntimeException("不能禁用当前登录账号");
            }
            if (hasAdminRole(user) && !"active".equalsIgnoreCase(newStatus)
                    && userRepository.countActiveByRoleName("ADMIN") <= 1) {
                throw new RuntimeException("不能禁用唯一的系统管理员账号");
            }
            user.setStatus(newStatus);
        }

        user.setEmail(userDetails.getEmail());
        user.setPhone(userDetails.getPhone());
        user.setRealName(userDetails.getRealName());
        user.setDescription(userDetails.getDescription());

        if (userDetails.getRoles() != null) {
            Set<Role> newRoles = resolveRoles(userDetails.getRoles());
            if (newRoles.isEmpty()) {
                throw new RuntimeException("请至少分配一个角色");
            }
            boolean wasAdmin = hasAdminRole(user);
            boolean willBeAdmin = newRoles.stream().anyMatch(r -> "ADMIN".equals(r.getName()));
            if (wasAdmin && !willBeAdmin && userRepository.countActiveByRoleName("ADMIN") <= 1) {
                throw new RuntimeException("不能移除唯一系统管理员的 ADMIN 角色");
            }
            if (isSelf && wasAdmin && !willBeAdmin) {
                throw new RuntimeException("不能移除当前登录账号的管理员角色");
            }
            Set<Long> oldIds = user.getRoles() == null ? Set.of()
                    : user.getRoles().stream().map(Role::getId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
            Set<Long> newIds = newRoles.stream().map(Role::getId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
            user.setRoles(newRoles);
            if (!oldIds.equals(newIds)) {
                authorityVersionService.bump();
            }
        }

        return userRepository.save(user);
    }

    @Transactional
    public void updatePassword(Long id, String newPassword) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        validatePassword(newPassword);
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        userRepository.save(user);
    }

    /** 当前用户修改自己的密码（登录后强制改密 / 自助改密） */
    @Transactional
    public void changeOwnPassword(String username, String oldPassword, String newPassword) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        if (!Boolean.TRUE.equals(user.getMustChangePassword())) {
            if (oldPassword == null || !passwordEncoder.matches(oldPassword, user.getPassword())) {
                throw new RuntimeException("原密码不正确");
            }
        }
        validatePassword(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new RuntimeException("新密码不能与旧密码相同");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        String currentUsername = currentUsername();
        if (currentUsername != null && currentUsername.equalsIgnoreCase(user.getUsername())) {
            throw new RuntimeException("不能删除当前登录账号");
        }
        if ("admin".equalsIgnoreCase(user.getUsername())) {
            throw new RuntimeException("不能删除内置管理员账号 admin");
        }
        if (hasAdminRole(user) && userRepository.countActiveByRoleName("ADMIN") <= 1) {
            throw new RuntimeException("不能删除唯一的系统管理员账号");
        }
        userRepository.deleteById(id);
        authorityVersionService.bump();
    }

    @Transactional
    public User unlockUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        return userRepository.save(user);
    }

    public boolean isLoginLocked(User user) {
        if (user == null || user.getLockedUntil() == null) {
            return false;
        }
        return user.getLockedUntil().isAfter(LocalDateTime.now());
    }

    @Transactional
    public void recordLoginFailure(User user) {
        if (user == null) return;
        int count = user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount();
        count++;
        user.setFailedLoginCount(count);
        if (count >= MAX_FAILED_LOGINS) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
        }
        userRepository.save(user);
    }

    @Transactional
    public void recordLoginSuccess(User user) {
        if (user == null) return;
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
    }

    /** 合并角色权限，按 resource 分组（只读视图） */
    public List<Map<String, Object>> getEffectivePermissions(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        Map<String, Permission> byName = new LinkedHashMap<>();
        if (user.getRoles() != null) {
            for (Role role : user.getRoles()) {
                if (role.getPermissions() == null) continue;
                for (Permission p : role.getPermissions()) {
                    if (p.getName() != null) {
                        byName.putIfAbsent(p.getName(), p);
                    }
                }
            }
        }
        Map<String, List<Permission>> byResource = new LinkedHashMap<>();
        for (Permission p : byName.values()) {
            String resource = p.getResource() != null ? p.getResource() : "other";
            byResource.computeIfAbsent(resource, k -> new ArrayList<>()).add(p);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<Permission>> e : byResource.entrySet()) {
            List<Permission> perms = e.getValue();
            perms.sort(Comparator.comparing(Permission::getName, Comparator.nullsLast(String::compareTo)));
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("resource", e.getKey());
            g.put("label", resourceLabel(e.getKey()));
            g.put("permissions", perms.stream().map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.getId());
                m.put("name", p.getName());
                m.put("displayName", p.getDisplayName());
                m.put("description", p.getDescription());
                m.put("action", p.getAction());
                return m;
            }).toList());
            groups.add(g);
        }
        groups.sort(Comparator.comparing(m -> String.valueOf(m.get("resource"))));
        return groups;
    }

    public List<String> collectPermissionCodes(User user) {
        return JwtService.collectPermissions(user);
    }

    public boolean verifyPassword(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

    public static void validatePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new RuntimeException("密码不能为空");
        }
        if (password.length() < 8) {
            throw new RuntimeException("密码至少 8 位");
        }
        if (password.length() > 64) {
            throw new RuntimeException("密码过长");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new RuntimeException("密码须同时包含字母与数字");
        }
    }

    private static String resourceLabel(String resource) {
        if (resource == null) return "其他";
        return switch (resource) {
            case "devices" -> "设备";
            case "alarms" -> "告警";
            case "configs" -> "配置";
            case "topology" -> "拓扑";
            case "performance" -> "性能";
            case "aiops" -> "智能运维";
            case "webssh" -> "WebSSH";
            case "audit" -> "审计";
            case "users", "roles" -> "用户权限";
            case "system" -> "系统";
            default -> resource;
        };
    }

    private boolean hasAdminRole(User user) {
        return user.getRoles() != null && user.getRoles().stream()
                .anyMatch(r -> r != null && "ADMIN".equals(r.getName()));
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        return name != null && !name.isBlank() ? name : null;
    }

    private Set<Role> resolveRoles(Set<Role> incoming) {
        Set<Role> resolved = new HashSet<>();
        if (incoming == null || incoming.isEmpty()) {
            return resolved;
        }
        for (Role r : incoming) {
            if (r == null) continue;
            if (r.getId() != null) {
                roleRepository.findById(r.getId()).ifPresent(resolved::add);
            } else if (r.getName() != null) {
                roleRepository.findByName(r.getName()).ifPresent(resolved::add);
            }
        }
        return resolved;
    }
}
