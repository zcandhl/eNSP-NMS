package com.ensp.nms.dto;

import com.ensp.nms.entity.Role;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class RoleMutationResult {
    private Role role;
    private long affectedUserCount;
    @Builder.Default
    private List<String> addedPermissions = new ArrayList<>();
    @Builder.Default
    private List<String> removedPermissions = new ArrayList<>();
    /** 可读审计 detail */
    private String auditDetail;
}
