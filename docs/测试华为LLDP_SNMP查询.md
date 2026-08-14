# 测试华为私有LLDP SNMP查询

## 1. 在ENSP设备上配置SNMP（如果还没配置）

```bash
<Huawei> system-view
[Huawei] snmp-agent community read public
[Huawei] snmp-agent sys-info location Beijing
[Huawei] snmp-agent sys-info version v2c
[Huawei] quit
```

## 2. 在服务器上测试SNMP查询

```bash
# 测试华为私有LLDP邻居名称
snmpwalk -v 2c -c public 1.1.1.1 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.3

# 测试华为私有LLDP邻居IP地址
snmpwalk -v 2c -c public 1.1.1.1 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.7

# 测试华为私有LLDP本地端口
snmpwalk -v 2c -c public 1.1.1.1 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.4

# 测试华为私有LLDP对端端口
snmpwalk -v 2c -c public 1.1.1.1 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.5
```

## 3. 预期输出

根据 `display lldp neighbor` 的结果，应该返回：

```
# snmpwalk 邻居名称
SNMPv2-SMI::enterprises.2011.5.25.134.1.2.5.1.3.3 = STRING: "SW"
SNMPv2-SMI::enterprises.2011.5.25.134.1.2.5.1.3.5 = STRING: "AR2"

# snmpwalk 邻居IP地址
SNMPv2-SMI::enterprises.2011.5.25.134.1.2.5.1.7.3 = IpAddress: 10.10.10.2
SNMPv2-SMI::enterprises.2011.5.25.134.1.2.5.1.7.5 = IpAddress: 10.10.10.3

# snmpwalk 本地端口
SNMPv2-SMI::enterprises.2011.5.25.134.1.2.5.1.4.3 = STRING: "GigabitEthernet0/0/1"
SNMPv2-SMI::enterprises.2011.5.25.134.1.2.5.1.4.5 = STRING: "GigabitEthernet0/0/2"

# snmpwalk 对端端口
SNMPv2-SMI::enterprises.2011.5.25.134.1.2.5.1.5.3 = STRING: "Ethernet0/0/1"
SNMPv2-SMI::enterprises.2011.5.25.134.1.2.5.1.5.5 = STRING: "GigabitEthernet0/0/0"
```

## 4. 检查索引

注意OID末尾的数字（3, 5）可能是：
- **ifIndex**: 3, 5 (对应 GigabitEthernet0/0/1, GigabitEthernet0/0/2)
- **Neighbor index**: 1 (都是1，因为每个端口只有一个邻居)

## 5. 如果SNMP查询失败

检查：
- SNMP Community是否正确配置
- 防火墙是否允许UDP 161端口
- 设备是否在线

```bash
# 检查SNMP是否可达
ping 1.1.1.1
```

## 6. Windows PowerShell测试

如果服务器是Windows，使用下面的命令：

```powershell
# 安装snmpwalk工具（如果还没有）
# 可以使用Paessler SNMP Tester或其他SNMP工具

# 或者使用PowerShell的Test-NetConnection测试端口
Test-NetConnection -ComputerName 1.1.1.1 -Port 161
```

## 7. 在后端日志查看

重新运行拓扑发现，查看后端日志中的：

```
华为LLDP邻居名称查询返回 X 条
华为LLDP邻居IP查询返回 X 条
华为邻居[0]: {sysName=?, portId=?, managementAddress=?}
```
