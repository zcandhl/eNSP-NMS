package com.ensp.nms.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RoleCreateRequest {
    private String name;
    private String displayName;
    private String description;
    /** 权限 ID；可为空，表示先建角色不赋权 */
    private List<Long> permissionIds = new ArrayList<>();
}
