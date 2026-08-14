# 华为设备私有 MIB 说明

## LLDP MIB (1.3.6.1.4.1.2011.5.25.134)

华为私有 LLDP MIB 用于发现邻居设备信息。

### 基础 OID
```
1.3.6.1.4.1.2011.5.25.134.1.2.5.1
```

### 子 OID 说明

| 子 OID | 完整 OID | 描述 | 数据类型 |
|--------|----------|------|----------|
| .1 | 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.1 | 邻居数量 | Integer |
| .2 | 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.2 | 邻居时间戳 | TimeTicks |
| .3 | 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.3 | **邻居设备名称** (sysName) | String |
| .4 | 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.4 | **本地接口名称** | String |
| .5 | 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.5 | **对端接口名称** | String |
| .6 | 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.6 | 邻居设备描述 | String |
| .7 | 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.7 | **邻居管理地址** (IP) | IpAddress |
| .8 | 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.8 | 邻居能力 | String |
| .9 | 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.9 | 邻居VLAN ID | Integer |
| .10 | 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.10 | 对端设备ID | String |

### 索引方式

华为私有 LLDP MIB 通常使用 **ifIndex** 作为索引：
- OID 格式：`{base_oid}.{sub_id}.{ifIndex}`
- 例如：`1.3.6.1.4.1.2011.5.25.134.1.2.5.1.3.3` 表示 ifIndex=3 的邻居设备名称

### 在 ENSP 设备上查看

```bash
# 查看 LLDP 邻居信息
display lldp neighbor

# 查看 LLDP 本地信息
display lldp local

# 查看 LLDP 运行状态
display lldp status

# 使用 SNMPWalk 测试（服务器上执行）
snmpwalk -v 2c -c public 1.1.1.1 1.3.6.1.4.1.2011.5.25.134.1.2.5.1.3
```

### 示例输出

```
# display lldp neighbor 的输出格式
Local Interface     Neighbor Device     Neighbor Interface    Hold Time
GE0/0/1            SW                 GE0/0/1              120
GE0/0/2            Router             GE0/0/0              120
```

### 注意事项

1. **确保 LLDP 已启用**
   ```bash
   [Huawei] lldp enable
   ```

2. **确保接口启用 LLDP**
   ```bash
   [Huawei]interface GigabitEthernet0/0/1
   [Huawei-GigabitEthernet0/0/1] lldp enable
   ```

3. **配置管理地址（可选）**
   ```bash
   [Huawei] lldp management-address 10.10.10.1
   ```

4. **SNMP Community 配置**
   ```bash
   [Huawei] snmp-agent community read public
   [Huawei] snmp-agent sys-info version v2c
   ```
