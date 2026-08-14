# ENSP LLDP 问题诊断指南

## 1. 检查 ENSP 设备 LLDP 配置

在 ENSP 设备上执行以下命令：

```bash
# 查看 LLDP 状态
display lldp neighbor

# 查看 LLDP 运行状态
display lldp status

# 查看 LLDP 本地信息
display lldp local
```

## 2. 测试 SNMP LLDP MIB

在服务器上执行 snmpwalk 测试：

```bash
# 测试华为 LLDP MIB
snmpwalk -v 2c -c public 1.1.1.1 1.0.8802.1.1.2.1.4.1.1

# 测试标准 LLDP MIB
snmpwalk -v 2c -c public 1.1.1.1 1.3.111.2.802.1.1.13.1.4.1.1
```

如果返回 `No Such Object` 或超时，说明设备不支持此 MIB。

## 3. 检查 Trap 是否包含邻居信息

查看后端日志中的 LLDP Trap，记录显示有 OSPF 认证失败等 Trap，但没有看到 LLDP 相关的 Trap。

需要确认：
- ENSP 设备是否配置了 `lldp notification enable`
- LLDP Trap 是否发送到管理系统

## 4. ENSP 设备的限制

ENSP 虚拟设备可能有以下限制：
- **不支持通过 SNMP 查询 LLDP 信息**
- **不支持 LLDP Trap 发送**
- **LLDP 功能主要用于学习，不支持管理**

## 5. 解决方案

### 方案 A：手动添加连接（推荐）

既然自动发现无法工作，我们可以通过手动方式添加拓扑连接：

1. 打开拓扑视图
2. 点击「添加连接」按钮
3. 选择源设备、目标设备、端口信息
4. 保存连接

### 方案 B：修复 ENSP LLDP 配置

在 ENSP 设备上配置：

```bash
# 全局启用 LLDP
lldp enable

# 接口启用 LLDP
interface GigabitEthernet0/0/1
 lldp enable
 lldp trap-interval 30

# 配置 LLDP 管理地址
lldp management-address 1.1.1.1

# 配置 SNMP Trap
snmp-agent trap enable lldp
```

### 方案 C：启用 LLDP MED（如果支持）

```bash
lldp med enable
```

## 6. 下一步

请先执行以下命令测试：

```bash
# 在服务器上测试 LLDP MIB
snmpwalk -v 2c -c public 1.1.1.1 1.0.8802.1.1.2.1.4.1.1.1
```

如果返回空结果，说明 ENSP 设备不支持 LLDP SNMP 查询，我们需要采用手动添加连接的方式。
