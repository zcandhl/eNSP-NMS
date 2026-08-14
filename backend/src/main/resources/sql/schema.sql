-- 创建数据库
CREATE DATABASE IF NOT EXISTS ensp_nms DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ensp_nms;

-- 设备表
CREATE TABLE IF NOT EXISTS device (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '设备ID',
    name VARCHAR(100) NOT NULL COMMENT '设备名称',
    ip_address VARCHAR(45) NOT NULL COMMENT 'IP地址',
    model VARCHAR(100) COMMENT '设备型号',
    vendor VARCHAR(50) COMMENT '厂商',
    device_type VARCHAR(20) DEFAULT 'other' COMMENT '设备类型：router/switch/firewall/ac/ap/pc/server/other',
    monitor_mode VARCHAR(20) DEFAULT 'auto' COMMENT '监控方式：snmp/icmp/auto',
    last_probe_method VARCHAR(20) COMMENT '最近探测方式：snmp/icmp',
    snmp_version VARCHAR(10) DEFAULT 'v2c' COMMENT 'SNMP版本',
    snmp_community VARCHAR(50) DEFAULT 'public' COMMENT 'SNMP community',
    snmp_port INT DEFAULT 161 COMMENT 'SNMP端口',
    ssh_username VARCHAR(50) COMMENT 'SSH用户名',
    ssh_password VARCHAR(100) COMMENT 'SSH密码',
    ssh_port INT DEFAULT 22 COMMENT 'SSH端口',
    status VARCHAR(20) DEFAULT 'offline' COMMENT '状态：online/offline/unknown',
    description TEXT COMMENT '描述',
    group_id BIGINT COMMENT '分组ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    last_seen DATETIME COMMENT '最后在线时间',
    INDEX idx_ip_address (ip_address),
    INDEX idx_status (status),
    INDEX idx_group_id (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备表';

-- 设备分组表
CREATE TABLE IF NOT EXISTS device_group (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '分组ID',
    name VARCHAR(100) NOT NULL COMMENT '分组名称',
    description TEXT COMMENT '分组描述',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备分组表';

-- 拓扑节点表
CREATE TABLE IF NOT EXISTS topology_node (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '节点ID',
    device_id BIGINT NOT NULL COMMENT '关联设备ID',
    x INT DEFAULT 0 COMMENT 'X坐标',
    y INT DEFAULT 0 COMMENT 'Y坐标',
    icon_type VARCHAR(50) COMMENT '图标类型',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_device_id (device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拓扑节点表';

-- 拓扑链路表
CREATE TABLE IF NOT EXISTS topology_link (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '链路ID',
    source_node_id BIGINT NOT NULL COMMENT '源节点ID',
    target_node_id BIGINT NOT NULL COMMENT '目标节点ID',
    source_port VARCHAR(50) COMMENT '源端口',
    target_port VARCHAR(50) COMMENT '目标端口',
    status VARCHAR(20) DEFAULT 'up' COMMENT '链路状态：up/down',
    bandwidth VARCHAR(20) COMMENT '带宽',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_source_node (source_node_id),
    INDEX idx_target_node (target_node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拓扑链路表';

-- 设备端口表
CREATE TABLE IF NOT EXISTS device_port (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '端口ID',
    device_id BIGINT NOT NULL COMMENT '设备ID',
    port_name VARCHAR(50) NOT NULL COMMENT '端口名称',
    port_type VARCHAR(50) COMMENT '端口类型',
    if_index INT COMMENT '接口索引',
    admin_status VARCHAR(20) COMMENT '管理状态',
    oper_status VARCHAR(20) COMMENT '操作状态',
    speed BIGINT COMMENT '端口速率',
    mtu INT COMMENT 'MTU',
    description TEXT COMMENT '描述',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_device_id (device_id),
    INDEX idx_port_name (port_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备端口表';

-- 告警表（为第二阶段预留）
CREATE TABLE IF NOT EXISTS alarm (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '告警ID',
    device_id BIGINT COMMENT '设备ID',
    severity VARCHAR(20) NOT NULL COMMENT '告警级别：critical/warning/info',
    type VARCHAR(50) COMMENT '告警类型',
    title VARCHAR(200) NOT NULL COMMENT '告警标题',
    description TEXT COMMENT '告警描述',
    status VARCHAR(20) DEFAULT 'active' COMMENT '状态：active/cleared/acknowledged',
    acknowledged BOOLEAN DEFAULT FALSE COMMENT '是否已确认',
    acknowledged_by VARCHAR(50) COMMENT '确认人',
    acknowledged_at DATETIME COMMENT '确认时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '告警时间',
    cleared_at DATETIME COMMENT '清除时间',
    INDEX idx_device_id (device_id),
    INDEX idx_status (status),
    INDEX idx_severity (severity),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警表';

-- 用户表
CREATE TABLE IF NOT EXISTS app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码哈希',
    email VARCHAR(100) COMMENT '邮箱',
    phone VARCHAR(50) COMMENT '手机号',
    real_name VARCHAR(50) COMMENT '真实姓名',
    status VARCHAR(20) DEFAULT 'active' COMMENT '状态：active/disabled',
    description TEXT COMMENT '描述',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 角色表
CREATE TABLE IF NOT EXISTS app_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '角色ID',
    name VARCHAR(50) NOT NULL UNIQUE COMMENT '角色名称',
    display_name VARCHAR(100) COMMENT '显示名称',
    description TEXT COMMENT '角色描述',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- 权限表
CREATE TABLE IF NOT EXISTS app_permission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '权限ID',
    name VARCHAR(100) NOT NULL UNIQUE COMMENT '权限名称',
    display_name VARCHAR(100) COMMENT '显示名称',
    resource VARCHAR(50) COMMENT '资源',
    action VARCHAR(50) COMMENT '操作',
    description TEXT COMMENT '权限描述',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表';

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS app_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    INDEX idx_user_id (user_id),
    INDEX idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- 角色权限关联表
CREATE TABLE IF NOT EXISTS app_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    INDEX idx_role_id (role_id),
    INDEX idx_permission_id (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';

-- 设备配置表
CREATE TABLE IF NOT EXISTS device_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '配置ID',
    device_id BIGINT NOT NULL COMMENT '设备ID',
    config_type VARCHAR(50) NOT NULL COMMENT '配置类型：startup/running',
    content LONGTEXT COMMENT '配置内容',
    config_version VARCHAR(20) COMMENT '配置版本',
    description VARCHAR(500) COMMENT '描述',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    created_by VARCHAR(50) COMMENT '创建人',
    INDEX idx_device_id (device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备配置表';

-- 配置模板表
CREATE TABLE IF NOT EXISTS config_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '模板ID',
    name VARCHAR(100) NOT NULL COMMENT '模板名称',
    category VARCHAR(50) COMMENT '分类：interface/route/security/qos/other',
    content LONGTEXT COMMENT '配置内容',
    description TEXT COMMENT '描述',
    device_type VARCHAR(50) COMMENT '适用设备类型：AR/S/USG',
    created_by VARCHAR(50) COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_category (category),
    INDEX idx_device_type (device_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置模板表';

-- 配置变更日志表
CREATE TABLE IF NOT EXISTS config_change_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '日志ID',
    device_id BIGINT NOT NULL COMMENT '设备ID',
    device_name VARCHAR(100) COMMENT '设备名称',
    change_type VARCHAR(50) NOT NULL COMMENT '变更类型：backup/restore/apply/compare',
    before_version VARCHAR(50) COMMENT '变更前版本',
    after_version VARCHAR(50) COMMENT '变更后版本',
    commands TEXT COMMENT '执行的命令',
    operator VARCHAR(50) COMMENT '操作人',
    reason TEXT COMMENT '变更原因',
    result TEXT COMMENT '执行结果',
    status VARCHAR(20) DEFAULT 'pending' COMMENT '状态：pending/running/success/failed',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_device_id (device_id),
    INDEX idx_change_type (change_type),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置变更日志表';
