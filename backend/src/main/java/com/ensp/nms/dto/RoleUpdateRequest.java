package com.ensp.nms.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RoleUpdateRequest {
    private String displayName;
    private String description;
    /** 权限 ID 列表；传空数组表示清空权限 */
    private List<Long> permissionIds = new ArrayList<>();
}
