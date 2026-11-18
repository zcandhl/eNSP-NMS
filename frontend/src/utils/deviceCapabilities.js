/** 与后端 DeviceCapabilityService 对齐的前端兜底能力矩阵 */

export const DEVICE_TYPE_OPTIONS = [
  { value: 'router', label: '路由器' },
  { value: 'switch', label: '交换机' },
  { value: 'firewall', label: '防火墙' },
  { value: 'ac', label: 'AC 无线控制器' },
  { value: 'ap', label: 'AP 接入点' },
  { value: 'pc', label: '虚拟 PC / 终端' },
  { value: 'server', label: '服务器' },
  { value: 'other', label: '其他' }
]

export const MONITOR_MODE_OPTIONS = [
  { value: 'auto', label: '自动（SNMP 优先，失败再 Ping）' },
  { value: 'snmp', label: '仅 SNMP' },
  { value: 'icmp', label: '仅 Ping (ICMP)' }
]

/** 远程管理协议：AC6005 在 eNSP 中 SSH 不可用，需选 Telnet */
export const PROTOCOL_OPTIONS = [
  { value: 'ssh', label: 'SSH（stelnet，默认）' },
  { value: 'telnet', label: 'Telnet（AC 类设备）' }
]

export function protocolLabel(protocol) {
  return PROTOCOL_OPTIONS.find(o => o.value === protocol)?.label || 'SSH（stelnet，默认）'
}

export function protocolShort(protocol) {
  return (protocol || 'ssh').toLowerCase() === 'telnet' ? 'Telnet' : 'SSH'
}

export function deviceTypeLabel(type) {
  return DEVICE_TYPE_OPTIONS.find(o => o.value === type)?.label || type || '其他'
}

export function resolveCapabilities(device) {
  if (device?.capabilities && typeof device.capabilities === 'object') {
    return {
      snmp: !!device.capabilities.snmp,
      icmp: device.capabilities.icmp !== false,
      performance: !!device.capabilities.performance,
      topologyDiscover: !!device.capabilities.topologyDiscover,
      configBackup: !!device.capabilities.configBackup,
      webssh: !!device.capabilities.webssh
    }
  }

  const type = (device?.deviceType || 'other').toLowerCase()
  const mode = (device?.monitorMode || 'auto').toLowerCase()
  const hasSsh = !!(device?.sshUsername && String(device.sshUsername).trim())

  if (type === 'pc' || mode === 'icmp') {
    return {
      snmp: false,
      icmp: true,
      performance: false,
      topologyDiscover: false,
      configBackup: type === 'pc' ? false : hasSsh,
      webssh: type === 'pc' ? false : hasSsh
    }
  }

  // AC6005（eNSP）的 SSH 服务不可用，已改为 telnet 管理；
  // 只要有远程管理账号，备份与 Web 终端都开放
  const remoteUsable = hasSsh

  return {
    snmp: true,
    icmp: true,
    performance: true,
    topologyDiscover: type !== 'ap',
    configBackup: remoteUsable,
    webssh: remoteUsable
  }
}

export function canCollectPerformance(device) {
  return resolveCapabilities(device).performance
}

export function canConfigBackup(device) {
  return resolveCapabilities(device).configBackup
}

export function canUseWebSsh(device) {
  return resolveCapabilities(device).webssh
}
