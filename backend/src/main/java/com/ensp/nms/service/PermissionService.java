package com.ensp.nms.service;

import com.ensp.nms.entity.Permission;
import com.ensp.nms.repository.PermissionRepository;
import com.ensp.nms.security.RbacCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PermissionService {

    private final PermissionRepository permissionRepository;

    public List<Permission> getAllPermissions() {
        return permissionRepository.findAll().stream()
                .sorted(Comparator
                        .comparing(Permission::getResource, Comparator.nullsLast(String::compareTo))
                        .thenComparing(Permission::getName, Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getPermissionGroups() {
        Map<String, List<Permission>> grouped = new LinkedHashMap<>();
        for (String resource : RbacCatalog.RESOURCE_LABELS.keySet()) {
            grouped.put(resource, new ArrayList<>());
        }
        for (Permission p : getAllPermissions()) {
            grouped.computeIfAbsent(p.getResource(), k -> new ArrayList<>()).add(p);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<Permission>> e : grouped.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("resource", e.getKey());
            group.put("label", RbacCatalog.RESOURCE_LABELS.getOrDefault(e.getKey(), e.getKey()));
            group.put("permissions", e.getValue());
            result.add(group);
        }
        return result;
    }

    public List<Permission> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return permissionRepository.findAllById(ids);
    }
}
