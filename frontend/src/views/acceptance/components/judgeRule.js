
/**
 * @file    judgeRule.js
 * @module  视图页/验收组件
 * @desc    验收判定规则文案与阈值计算。
 *
 * @author  ZC
 * @date    2026-11-22 * @version 1.0.0
 */

export const METRICS = {
  TOPOLOGY_MATCH: [
    { key: 'topology.total', label: '实测纳管节点数', kind: 'number', unit: '台' },
    { key: 'topology.links', label: '拓扑链路数', kind: 'number', unit: '条' },
    { key: 'topology.matched', label: '已关联设备数', kind: 'number', unit: '台' }
  ],
  REACHABILITY: [
    { key: 'reachability.up', label: '管理地址可达台数', kind: 'number', unit: '台' },
    { key: 'reachability.total', label: '纳入检测台数', kind: 'number', unit: '台' }
  ],
  OSPF_NEIGHBOR: [
    { key: 'ospf.neighborCount', label: 'OSPF 满邻居数', kind: 'number', unit: '个' }
  ],
  VRRP_ROLE: [
    { key: 'vrrp.masterCount', label: 'VRRP 主用组数', kind: 'number', unit: '组' },
    { key: 'vrrp.backupCount', label: 'VRRP 备用组数', kind: 'number', unit: '组' }
  ],
  DHCP_POOL: [{ key: 'dhcp.poolCount', label: 'DHCP 地址池数', kind: 'number', unit: '个' }],
  VLAN_MATCH: [
    { key: 'vlan.matched', label: '已配置 VLAN 网关数', kind: 'number', unit: '个' },
    { key: 'vlan.actualSuffix', label: '网关末位', kind: 'text' }
  ],
  COMPLIANCE_SCORE: [
    { key: 'minComplianceScore', label: '最低合规得分', kind: 'number', unit: '分' }
  ],
  CONFIG_DIFF: [
    { key: 'config.diffLines', label: '与基线的差异行数', kind: 'number', unit: '行' }
  ],
  CUSTOM_COMMAND: [
    {
      key: 'custom.matched',
      label: '命令输出是否命中匹配内容',
      kind: 'bool',
      trueText: '输出中出现匹配内容',
      falseText: '输出中不出现匹配内容'
    }
  ]
};

const OPS = {
  number: [
    { op: '>=', label: '不少于' },
    { op: '>', label: '多于' },
    { op: '==', label: '等于' },
    { op: '<=', label: '不超过' },
    { op: '<', label: '少于' }
  ],
  text: [
    { op: '==', label: '等于' },
    { op: '!=', label: '不等于' }
  ],
  bool: [
    { op: '==true', label: '出现（命中）' },
    { op: '==false', label: '不出现（未命中）' }
  ]
};

const BOOL_VALUES = { true: true, false: false };

export function metricList(type) {
  return METRICS[type] || []
}

export function metricDef(type, key) {
  return metricList(type).find((m) => m.key === key) || null
}

export function opList(type, metricKey) {
  const def = metricDef(type, metricKey)
  return OPS[def?.kind || 'number'] || OPS.number
}

export function parseExpr(expr, type, paramKeys = []) {
  const raw = String(expr || '').trim()
  if (!raw) return null
  const metricKeys = metricList(type).map((m) => m.key)
  const parts = raw.split(/\s+and\s+/i)
  const items = []
  for (const part of parts) {
    const m = part.trim().match(/^([\w.]+)\s*(>=|<=|==|!=|>|<)\s*(.+)$/)
    if (!m) return null
    const [, left, op, rightRaw] = m
    if (!metricKeys.includes(left)) return null
    const def = metricDef(type, left)
    const right = rightRaw.trim()
    const item = { metric: left, op }
    if (def?.kind === 'bool') {
      const v = BOOL_VALUES[right.toLowerCase()]
      if (v === undefined) return null
      item.op = v ? '==true' : '==false'
    } else if (paramKeys.includes(right)) {
      item.targetKind = 'param'
      item.targetValue = right
    } else if (metricKeys.includes(right)) {
      item.targetKind = 'metric'
      item.targetValue = right
    } else if (/^'(.+)'$/.test(right)) {
      item.targetKind = 'value'
      item.targetValue = right.replace(/^'|'$/g, '')
    } else if (/^-?\d+(\.\d+)?$/.test(right)) {
      item.targetKind = 'value'
      item.targetValue = Number(right)
    } else {
      return null
    }
    items.push(item)
  }
  return items.length ? items : null
}

export function buildExpr(items, type) {
  if (!items || !items.length) return ''
  return items
    .map((it) => {
      const def = metricDef(type, it.metric)
      if (def?.kind === 'bool') return `${it.metric} == ${it.op === '==true' ? 'true' : 'false'}`
      if (it.targetKind === 'param' || it.targetKind === 'metric') {
        return `${it.metric} ${it.op} ${it.targetValue}`
      }
      const v = it.targetValue
      if (typeof v === 'number') return `${it.metric} ${it.op} ${v}`
      return `${it.metric} ${it.op} '${v}'`
    })
    .join(' and ')
}

function targetText(it, type, params, fieldLabel) {
  const def = metricDef(type, it.metric)
  if (def?.kind === 'bool') return ''
  if (it.targetKind === 'param') {
    const name = fieldLabel(it.targetValue) || it.targetValue
    const cur = params?.[it.targetValue]
    return cur === undefined || cur === '' ? name : `${name}（方案填 ${cur}）`
  }
  if (it.targetKind === 'metric') {
    return metricDef(type, it.targetValue)?.label || it.targetValue
  }
  const unit = def?.unit || ''
  return `${it.targetValue}${unit}`
}

export function describe(items, type, params, fieldLabel = (k) => k) {
  if (!items || !items.length) return ''
  const parts = items.map((it) => {
    const def = metricDef(type, it.metric)
    const metricLabel = def?.label || it.metric
    if (def?.kind === 'bool') {
      // trueText/falseText本身就是完整短语，直接成句
      return it.op === '==true' ? def.trueText || '输出中出现匹配内容' : def.falseText || '输出中不出现匹配内容'
    }
    const opLabel = (OPS[def?.kind || 'number'] || OPS.number).find((o) => o.op === it.op)?.label || it.op
    return `「${metricLabel}」${opLabel}「${targetText(it, type, params, fieldLabel)}」`
  })
  return `当${parts.join('，并且')}时，判定通过`
}

export function fromHint(hint, type, paramKeys) {
  return parseExpr(hint, type, paramKeys) || []
}

export const METRIC_KEY_LABEL = {
  masterCount: '主用组数',
  backupCount: '备用组数',
  neighborCount: '满邻居数',
  scopedDevices: '检测范围内设备数',
  excludedDevices: '范围外排除设备数',
  totalDevices: '纳管设备总数',
  matchedDevices: '匹配设备数',
  up: '可达台数',
  total: '总数',
  matched: '匹配数',
  links: '链路数',
  devices: '设备数',
  poolCount: '地址池数',
  actualSuffix: '实测网关末位',
  diffLines: '差异行数',
  lowestScore: '最低得分',
  minComplianceScore: '最低合规得分',
  command: '检测命令',
  expected: '期望值',
  failed: '未通过项',
  switchoverMs: '倒换耗时(ms)',
  maxUsage: '最高使用率(%)',
  noBaseline: '未找到基线'
}

export function metricLabel(type, key) {
  const def = metricList(type).find((m) => m.key === key)
  if (def) return def.label
  const bare = key.split('.').pop()
  return METRIC_KEY_LABEL[bare] || key
}

function metricValueText(v) {
  if (typeof v === 'boolean') return v ? '是' : '否'
  const s = String(v)
  return s.length > 60 ? `${s.slice(0, 60)}…` : s
}

export function metricsText(type, metrics) {
  if (!metrics || typeof metrics !== 'object') return ''
  const parts = Object.entries(metrics)
    .filter(([k, v]) => k !== 'detail' && v !== null && v !== undefined && v !== '')
    .map(([k, v]) => `${metricLabel(type, k)}=${metricValueText(v)}`)
  return parts.join('；')
}

export function describeCheck(check, checkTypes = []) {
  if (!check) return ''
  const fields = checkTypes.find((t) => t.type === check.type)?.fields || []
  const pk = fields.map((f) => f.key)
  const items =
    (Array.isArray(check.rule?.items) && check.rule.items.length ? check.rule.items : null) ||
    parseExpr(check.assertExpr, check.type, pk)
  if (!items || !items.length) return ''
  return describe(items, check.type, check.params, (k) => fields.find((f) => f.key === k)?.label || k)
}
