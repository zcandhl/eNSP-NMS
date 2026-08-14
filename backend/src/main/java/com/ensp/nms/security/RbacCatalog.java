package com.ensp.nms.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 常见网管（NMS）权限与角色模板。
 * 参考 eSight / 网管运维分工：系统管理、运维操作、监控只读、告警值班、配置管理。
 */
public final class RbacCatalog {

    private RbacCatalog() {}

    public record PermDef(String name, String displayName, String resource, String action, String description) {}

    public record RoleDef(String name, String displayName, String description, Set<String> permissionNames) {}

    public static final List<PermDef> PERMISSIONS = List.of(
            // 设备
            new PermDef("devices:read", "查看设备", "devices", "read", "查看设备列表与详情"),
            new PermDef("devices:write", "管理设备", "devices", "write", "新增、编辑、删除、刷新设备"),
            new PermDef("devices:discover", "设备发现", "devices", "discover", "网段扫描发现设备"),
            // 告警
            new PermDef("alarms:read", "查看告警", "alarms", "read", "查看告警列表与统计"),
            new PermDef("alarms:handle", "处理告警", "alarms", "handle", "确认、清除告警"),
            new PermDef("alarms:write", "管理告警", "alarms", "write", "删除告警及批量管理"),
            // 配置
            new PermDef("configs:read", "查看配置", "configs", "read", "查看备份、模板、变更记录、计划"),
            new PermDef("configs:write", "管理配置", "configs", "write", "备份、恢复、下发、模板与计划管理"),
            // 拓扑
            new PermDef("topology:read", "查看拓扑", "topology", "read", "查看网络拓扑"),
            new PermDef("topology:write", "管理拓扑", "topology", "write", "编辑链路、布局、拓扑发现"),
            // 性能
            new PermDef("performance:read", "查看性能", "performance", "read", "查看性能指标与性能告警"),
            // 智能运维
            new PermDef("aiops:read", "智能运维", "aiops", "read", "查看智能中心、健康分、根因与运维助手"),
            new PermDef("aiops:write", "智能运维写操作", "aiops", "write", "无人值守策略、暂停/恢复、触发自动处置与写工具"),
            // 远程
            new PermDef("webssh:connect", "WebSSH", "webssh", "connect", "通过 Web 终端登录设备"),
            // 系统
            new PermDef("users:manage", "用户管理", "users", "manage", "管理账号与重置密码"),
            new PermDef("roles:manage", "角色权限", "roles", "manage", "管理角色及权限分配"),
            new PermDef("audit:read", "查看操作日志", "audit", "read", "查看全站用户操作审计日志"),
            new PermDef("system:test", "系统测试", "system", "test", "SNMP/Mock 等测试工具")
    );

    public static final Map<String, String> RESOURCE_LABELS = new LinkedHashMap<>();

    static {
        RESOURCE_LABELS.put("devices", "设备管理");
        RESOURCE_LABELS.put("alarms", "告警管理");
        RESOURCE_LABELS.put("configs", "配置管理");
        RESOURCE_LABELS.put("topology", "拓扑视图");
        RESOURCE_LABELS.put("performance", "性能监控");
        RESOURCE_LABELS.put("aiops", "智能运维");
        RESOURCE_LABELS.put("webssh", "远程终端");
        RESOURCE_LABELS.put("users", "用户账号");
        RESOURCE_LABELS.put("roles", "角色权限");
        RESOURCE_LABELS.put("audit", "操作日志");
        RESOURCE_LABELS.put("system", "系统工具");
    }

    private static final Set<String> ALL = PERMISSIONS.stream()
            .map(PermDef::name)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

    private static final Set<String> READ_ONLY = Set.of(
            "devices:read", "alarms:read", "configs:read", "topology:read", "performance:read", "aiops:read"
    );

    private static final Set<String> OPERATOR_PERMS = Set.of(
            "devices:read", "devices:write", "devices:discover",
            "alarms:read", "alarms:handle", "alarms:write",
            "configs:read", "configs:write",
            "topology:read", "topology:write",
            "performance:read",
            "aiops:read", "aiops:write",
            "webssh:connect"
    );

    private static final Set<String> ALARM_DUTY_PERMS = Set.of(
            "devices:read",
            "alarms:read", "alarms:handle",
            "topology:read",
            "performance:read",
            "aiops:read"
    );

    private static final Set<String> CONFIG_ADMIN_PERMS = Set.of(
            "devices:read",
            "configs:read", "configs:write",
            "topology:read",
            "webssh:connect"
    );

    public static final List<RoleDef> ROLES = List.of(
            new RoleDef("ADMIN", "系统管理员", "拥有全部网管权限，含用户与角色管理", ALL),
            new RoleDef("OPERATOR", "运维操作员", "日常运维：设备、告警、配置、拓扑、性能与远程登录", OPERATOR_PERMS),
            new RoleDef("VIEWER", "监控只读", "仅可查看设备、告警、配置、拓扑与性能，不可变更", READ_ONLY),
            new RoleDef("ALARM_DUTY", "告警值班员", "值班处理告警，可查看设备与拓扑", ALARM_DUTY_PERMS),
            new RoleDef("CONFIG_ADMIN", "配置管理员", "负责配置备份/下发与模板管理", CONFIG_ADMIN_PERMS)
    );
}
