package com.ensp.nms.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色更新请求入参对象。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Data
public class RoleUpdateRequest {
    private String displayName;
    private String description;
    private List<Long> permissionIds = new ArrayList<>();
}
