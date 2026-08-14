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

  return {
    snmp: true,
    icmp: true,
    performance: true,
    topologyDiscover: type !== 'ap',
    configBackup: hasSsh,
    webssh: hasSsh
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
