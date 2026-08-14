package com.ensp.nms.controller;

import com.ensp.nms.entity.Permission;
import com.ensp.nms.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    @GetMapping
    @PreAuthorize("hasAuthority('roles:manage')")
    public ResponseEntity<List<Permission>> list() {
        return ResponseEntity.ok(permissionService.getAllPermissions());
    }

    @GetMapping("/groups")
    @PreAuthorize("hasAuthority('roles:manage')")
    public ResponseEntity<List<Map<String, Object>>> groups() {
        return ResponseEntity.ok(permissionService.getPermissionGroups());
    }
}
