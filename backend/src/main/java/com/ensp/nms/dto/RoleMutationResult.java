package com.ensp.nms.dto;

import com.ensp.nms.entity.Role;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色变更结果返回对象。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Data
@Builder
public class RoleMutationResult {
    private Role role;
    private long affectedUserCount;
    @Builder.Default
    private List<String> addedPermissions = new ArrayList<>();
    @Builder.Default
    private List<String> removedPermissions = new ArrayList<>();
    private String auditDetail;
}
