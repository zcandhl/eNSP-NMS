/**
 * 验收工作台 · 演示数据与内存实现
 *
 * 用途：后端 /api/acceptance/** 尚未落地时，前端仍可完整演示（配置、试运行、运行、导报告）。
 * 原则：只要真实接口可用就不走这里；一旦降级，界面顶部会显示"演示数据"标记，不伪装真实。
 */

// 前端不按原语写死表单，全部依据 fields 渲染，新增第 10 类只加一条数据。
import { describeCheck } from '../views/acceptance/components/judgeRule'

export const CHECK_TYPES = [
  {
    type: 'TOPOLOGY_MATCH',
    label: '拓扑符合性',
    description: '比对当前纳管拓扑与设计方案中的节点、链路数量',
    fields: [
      { key: 'expectedNodes', label: '期望节点数', kind: 'number', default: 17, min: 1 },
      { key: 'tolerance', label: '允许偏差（台）', kind: 'number', default: 0, min: 0 }
    ],
    assertHint: 'topology.matched == topology.total'
  },
  {
    type: 'REACHABILITY',
    label: '连通与可达',
    description: '逐节点探测管理地址可达性，统计在线比例',
    fields: [
      { key: 'minUpRatio', label: '最低在线比例', kind: 'number', default: 1, min: 0, max: 1, step: 0.01 },
      { key: 'timeoutMs', label: '单点超时（ms）', kind: 'number', default: 2000, min: 500 }
    ],
    assertHint: 'reachability.up / reachability.total >= minUpRatio'
  },
  {
    type: 'OSPF_NEIGHBOR',
    label: 'OSPF 邻居',
    description: '采集各设备 OSPF 邻居状态，统计 Full 数量',
    fields: [
      { key: 'expectedNeighbors', label: '期望 Full 邻居数', kind: 'number', default: 11, min: 0 },
      { key: 'areaId', label: '区域号', kind: 'text', default: '0.0.0.0' }
    ],
    assertHint: 'ospf.neighborCount >= expectedNeighbors'
  },
  {
    type: 'VRRP_ROLE',
    label: 'VRRP 主备分布',
    description: '汇总两台核心的 VRRP 角色，并实测一次倒换耗时',
    fields: [
      { key: 'expectMaster', label: '期望主用组数', kind: 'number', default: 12, min: 0 },
      { key: 'expectBackup', label: '期望备用组数', kind: 'number', default: 10, min: 0 },
      { key: 'switchoverMaxMs', label: '倒换时间上限（ms）', kind: 'number', default: 1000, min: 100 }
    ],
    assertHint: 'vrrp.masterCount == expectMaster and vrrp.backupCount == expectBackup'
  },
  {
    type: 'DHCP_POOL',
    label: 'DHCP 地址池',
    description: '统计地址池数量与最高使用率，识别单点风险',
    fields: [
      { key: 'expectedPools', label: '期望地址池数', kind: 'number', default: 20, min: 0 },
      { key: 'maxUsagePercent', label: '最高使用率（%）', kind: 'number', default: 80, min: 1, max: 100 }
    ],
    assertHint: 'dhcp.poolCount == expectedPools and dhcp.maxUsage <= maxUsagePercent'
  },
  {
    type: 'VLAN_MATCH',
    label: 'VLAN 与网关',
    description: '比对 VLAN 数量、网段第三段与房间号是否一致、网关是否统一 .254',
    fields: [
      { key: 'expectedVlans', label: '期望 VLAN 数', kind: 'number', default: 22, min: 1 },
      { key: 'gatewaySuffix', label: '网关末位', kind: 'text', default: '254' }
    ],
    assertHint: 'vlan.matched == expectedVlans and vlan.gatewaySuffix == gatewaySuffix'
  },
  {
    type: 'COMPLIANCE_SCORE',
    label: '配置合规评分',
    description: '按规则集对每台设备配置打分，取最低分判定',
    fields: [
      { key: 'minScore', label: '最低合规分', kind: 'number', default: 90, min: 0, max: 100 },
      { key: 'ruleSet', label: '规则集', kind: 'select', default: 'security-baseline-v1', options: [
        { label: '安全基线 V1（5 条）', value: 'security-baseline-v1' },
        { label: '安全基线 V2（21 条）', value: 'security-baseline-v2' }
      ] }
    ],
    assertHint: 'min(device.complianceScore) >= minScore',
    allowFinding: true
  },
  {
    type: 'CONFIG_DIFF',
    label: '配置基线比对',
    description: '将当前运行配置与已归档基线逐行比对',
    fields: [
      { key: 'baselineName', label: '基线名称', kind: 'text', default: '2026-11-08 交付基线' },
      { key: 'allowDiffLines', label: '允许差异行数', kind: 'number', default: 0, min: 0 }
    ],
    assertHint: 'config.diffLines <= allowDiffLines'
  },
  {
    type: 'CUSTOM_COMMAND',
    label: '自定义命令检查',
    description: '下发一条只读命令，按匹配规则判定。信息中心老师可用它自行扩展检查项',
    fields: [
      { key: 'command', label: '只读命令', kind: 'text', default: 'display ntp-service status', placeholder: '必须以 display 开头' },
      { key: 'matchMode', label: '匹配方式', kind: 'select', default: 'contains', options: [
        { label: '包含', value: 'contains' },
        { label: '不包含', value: 'notContains' },
        { label: '正则匹配', value: 'regex' },
        { label: '数值大于', value: 'gt' },
        { label: '数值小于', value: 'lt' }
      ] },
      { key: 'matchValue', label: '匹配内容', kind: 'text', default: 'synchronized' },
      { key: 'timeoutMs', label: '超时（ms）', kind: 'number', default: 30000, min: 1000 }
    ],
    assertHint: 'custom.matched == true'
  }
]

const PROJECTS = [
  {
    id: 1,
    name: '三期工程综合实训大楼',
    building: '综合实训大楼（6 层 / 约 3.5 万㎡）',
    owner: '学校信息中心',
    contractor: '网络承建单位',
    acceptanceOrg: '信息中心 + 第三方',
    deviceCount: 17,
    note: '5F 13 间机房 650 工位，1—4F、6F 为普通实训室'
  },
  {
    id: 2,
    name: '教学楼 B 座网络改造',
    building: '教学楼 B（4 层）',
    owner: '学校信息中心',
    contractor: '网络承建单位',
    acceptanceOrg: '信息中心',
    deviceCount: 9,
    note: '改造项目：替换老旧接入层，核心利旧'
  }
]

// 队员账号（与后端 /api/acceptance/members 契约一致）：检查项 owner 绑定 username
export const MEMBERS = [
  { username: 'player1', realName: '1号·网络设计与实施' },
  { username: 'player2', realName: '2号·软件开发' },
  { username: 'player3', realName: '3号·测试与交付' },
  { username: 'player4', realName: '4号·验收质检' }
]

const PLANS = [
  {
    id: 101,
    projectId: 1,
    name: '新建园区网验收 V1',
    version: '2026.1',
    reportNoRule: 'YS-{date}-{batch}',
    status: 'active',
    checks: [
      {
        id: 1, seq: 1, code: 'A-01', title: '拓扑符合性', type: 'TOPOLOGY_MATCH',
        owner: 'player1', severity: 'high', enabled: true, allowFinding: false,
        params: { expectedNodes: 17, tolerance: 0 },
        assertExpr: 'topology.matched == topology.total'
      },
      {
        id: 2, seq: 2, code: 'A-02', title: '连通与路由', type: 'OSPF_NEIGHBOR',
        owner: 'player1', severity: 'critical', enabled: true, allowFinding: false,
        params: { expectedNeighbors: 11, areaId: '0.0.0.0' },
        assertExpr: 'ospf.neighborCount >= expectedNeighbors'
      },
      {
        id: 3, seq: 3, code: 'A-03', title: '冗余可靠性', type: 'VRRP_ROLE',
        owner: 'player2', severity: 'critical', enabled: true, allowFinding: false,
        params: { expectMaster: 12, expectBackup: 10, switchoverMaxMs: 1000 },
        assertExpr: 'vrrp.masterCount == expectMaster and vrrp.backupCount == expectBackup'
      },
      {
        id: 4, seq: 4, code: 'A-04', title: '安全基线', type: 'COMPLIANCE_SCORE',
        owner: 'player4', severity: 'high', enabled: true, allowFinding: true,
        params: { minScore: 90, ruleSet: 'security-baseline-v1' },
        assertExpr: 'min(device.complianceScore) >= minScore'
      },
      {
        id: 5, seq: 5, code: 'A-05', title: '故障预演', type: 'CUSTOM_COMMAND',
        owner: 'player2', severity: 'medium', enabled: true, allowFinding: true, needConfirm: true,
        params: { command: 'display interface brief | include down', matchMode: 'contains', matchValue: 'DOWN', timeoutMs: 30000 },
        assertExpr: 'custom.matched == false'
      },
      {
        id: 6, seq: 6, code: 'A-06', title: '配置基线', type: 'CONFIG_DIFF',
        owner: 'player3', severity: 'medium', enabled: true, allowFinding: false,
        params: { baselineName: '2026-11-08 交付基线', allowDiffLines: 0 },
        assertExpr: 'config.diffLines <= allowDiffLines'
      },
      {
        id: 7, seq: 7, code: 'A-07', title: 'NTP 同步状态', type: 'CUSTOM_COMMAND',
        owner: 'player4', severity: 'low', enabled: false, allowFinding: true,
        params: { command: 'display ntp-service status', matchMode: 'contains', matchValue: 'synchronized', timeoutMs: 30000 },
        assertExpr: 'custom.matched == true'
      }
    ]
  },
  {
    id: 201,
    projectId: 2,
    name: '改造项目验收 V1',
    version: '2026.1',
    reportNoRule: 'YS-{date}-{batch}',
    status: 'draft',
    checks: [
      {
        id: 21, seq: 1, code: 'B-01', title: '连通与可达', type: 'REACHABILITY',
        owner: 'player1', severity: 'critical', enabled: true, allowFinding: false,
        params: { minUpRatio: 0.98, timeoutMs: 2000 },
        assertExpr: 'reachability.up / reachability.total >= minUpRatio'
      },
      {
        id: 22, seq: 2, code: 'B-02', title: 'VLAN 与网关', type: 'VLAN_MATCH',
        owner: 'player1', severity: 'high', enabled: true, allowFinding: false,
        params: { expectedVlans: 12, gatewaySuffix: '254' },
        assertExpr: 'vlan.matched == expectedVlans'
      },
      {
        id: 23, seq: 3, code: 'B-03', title: '配置合规评分', type: 'COMPLIANCE_SCORE',
        owner: 'player4', severity: 'high', enabled: true, allowFinding: true,
        params: { minScore: 90, ruleSet: 'security-baseline-v2' },
        assertExpr: 'min(device.complianceScore) >= minScore'
      }
    ]
  }
]

const RAW_OUTPUT = {
  1: `# 拓扑采集 · LLDP 汇总\n节点 17 / 链路 27\n已纳管 17 台，缺失 0 台\n匹配设计拓扑：17/17`,
  2: `# CORE-1 · display ospf peer brief\nArea 0.0.0.0 interface Vlanif90\n\nRouter ID       Address         State     Interface\n10.20.255.2     10.20.255.10    Full      Vlanif90\n10.20.255.21    10.20.255.1     Full      Vlanif91\n10.20.255.3     10.20.255.5     Full      Vlanif92\n\nFull 邻居合计：11`,
  3: `# CORE-1 · display vrrp brief\nTotal:22   Master:12   Backup:10   Non-active:0\n\nVRID  State    Interface     Type     Virtual IP\n51    Master   Vlanif51      Normal   10.20.51.254\n52    Master   Vlanif52      Normal   10.20.52.254\n53    Master   Vlanif53      Normal   10.20.53.254\n...\n23    Backup   Vlanif23      Normal   10.20.23.254\n\n# CORE-2 · display vrrp brief\nTotal:22   Master:10   Backup:12   Non-active:0\n\n51    Backup   Vlanif51      Normal   10.20.51.254\n...\n\n# 倒换实测\nshutdown Vlanif51 @ CORE-1  →  CORE-2 接管耗时 620 ms`,
  4: `# 合规扫描 · 17 台设备\nACC-6F     score 84  non_compliant  failed: SSH_PREFERRED\nCORE-1     score 100 compliant\nCORE-2     score 100 compliant\nACC-1F     score 100 compliant\nACC-2F     score 100 compliant\nACC-3F     score 100 compliant\nACC-4F     score 100 compliant\nACC-5F-1   score 100 compliant\nACC-5F-2   score 100 compliant\nACC-5F-3   score 100 compliant\nACC-5F-4   score 100 compliant\n...\n\n最低分 84（ACC-6F），未达阈值 90`,
  5: `# 故障预演 · shutdown ACC-3F GE0/0/1\n00:00  shutdown 接口\n00:12  终端重连至备用链路\n00:12  业务恢复，MTTR 12 s\n\n# display interface brief | include down\nGE0/0/1   DOWN   (本次预演人为关闭)`,
  6: `# 配置基线比对 · 基线「2026-11-08 交付基线」\n逐台比对 17 台，差异行 0\n与基线一致：17/17`,
  7: `# ACC-6F · display ntp-service status\nClock status: unsynchronized\nClock stratum: 16`
}

function makeItems(plan, statusMap) {
  return plan.checks.filter((c) => c.enabled).map((c) => {
    const st = statusMap[c.code] || 'PASS'
    return {
      checkId: c.id,
      code: c.code,
      title: c.title,
      type: c.type,
      owner: c.owner,
      severity: c.severity,
      status: st,
      durationMs: st === 'PENDING' ? 0 : 800 + (c.id * 137) % 2400,
      metrics: metricsOf(c, st),
      rawOutput: RAW_OUTPUT[c.id] || '',
      message: messageOf(c, st),
      findings: st === 'FINDING'
        ? [{ code: 'P-00' + (c.id % 3 + 1), device: 'ACC-6F', detail: '未启用 SSH（stelnet server enable 缺失），仍存在 telnet 通道', severity: 'high' }]
        : []
    }
  })
}

function metricsOf(c, st) {
  switch (c.type) {
    case 'TOPOLOGY_MATCH': return { total: 17, matched: 17 }
    case 'OSPF_NEIGHBOR': return { neighborCount: 11, expected: 11 }
    case 'VRRP_ROLE': return { masterCount: 12, backupCount: 10, switchoverMs: 620 }
    case 'COMPLIANCE_SCORE': return { minScore: st === 'FINDING' ? 84 : 100, devices: 17, failed: st === 'FINDING' ? 1 : 0 }
    case 'CONFIG_DIFF': return { diffLines: 0, devices: 17 }
    case 'DHCP_POOL': return { poolCount: 20, maxUsage: 43 }
    case 'VLAN_MATCH': return { matched: 22, total: 22, gatewaySuffix: '254' }
    case 'REACHABILITY': return { up: 17, total: 17 }
    case 'CUSTOM_COMMAND': return { matched: st === 'PASS' ? false : true, command: c.params.command }
    default: return {}
  }
}

function messageOf(c, st) {
  if (st === 'PENDING') return '需人工确认后执行（故障预演会中断接口）'
  if (st === 'FINDING') return '1 项待整改：ACC-6F 合规分 84，低于阈值 90'
  if (st === 'FAIL') return '未达判定准则'
  return '判定准则满足'
}

const BATCHES = [
  {
    batchId: 'B-011', planId: 101, no: 'YS-2026-1108-011', status: 'DONE',
    startedAt: '2026-11-08 15:20:11', finishedAt: '2026-11-08 15:21:38',
    items: makeItems(PLANS[0], { 'A-01': 'PASS', 'A-02': 'PASS', 'A-03': 'PASS', 'A-04': 'FINDING', 'A-05': 'PASS', 'A-06': 'PASS' })
  },
  {
    batchId: 'B-012', planId: 101, no: 'YS-2026-1109-012', status: 'DONE',
    startedAt: '2026-11-09 09:40:02', finishedAt: '2026-11-09 09:41:30',
    items: makeItems(PLANS[0], { 'A-01': 'PASS', 'A-02': 'PASS', 'A-03': 'PASS', 'A-04': 'FINDING', 'A-05': 'PENDING', 'A-06': 'PASS' })
  }
]

let seq = 13
const runtime = new Map() // batchId -> batch

function clone(v) {
  return JSON.parse(JSON.stringify(v))
}

function delay(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

export const mockApi = {
  async listProjects() {
    await delay(60)
    return clone(PROJECTS)
  },
  async updateProject(id, payload) {
    await delay(80)
    const p = PROJECTS.find((x) => x.id === Number(id))
    if (!p) throw new Error('工程不存在')
    Object.assign(p, payload, { id: p.id })
    return clone(p)
  },
  async deleteProject(id) {
    await delay(80)
    const pid = Number(id)
    const idx = PROJECTS.findIndex((x) => x.id === pid)
    if (idx >= 0) PROJECTS.splice(idx, 1)
    // 级联：方案与历史批次一起清
    const planIds = PLANS.filter((p) => p.projectId === pid).map((p) => p.id)
    for (let i = PLANS.length - 1; i >= 0; i--) {
      if (PLANS[i].projectId === pid) PLANS.splice(i, 1)
    }
    for (let i = BATCHES.length - 1; i >= 0; i--) {
      if (planIds.includes(BATCHES[i].planId)) BATCHES.splice(i, 1)
    }
    return null
  },
  async createPlan(payload) {
    await delay(80)
    // 标准模板：克隆首个方案的检查项，负责人换成当前登录人
    const src = PLANS[0]
    const checks = (src?.checks || []).map((c) => ({ ...clone(c), id: c.id, owner: payload.owner || c.owner }))
    const plan = {
      id: Date.now(),
      projectId: Number(payload.projectId),
      name: payload.name || '未命名验收方案',
      version: payload.version || '1.0',
      status: 'draft',
      reportNoRule: 'YS-{date}-{batch}',
      checks: payload.template === false ? [] : checks
    }
    PLANS.push(plan)
    return clone(plan)
  },
  async deletePlan(id) {
    await delay(80)
    const pid = Number(id)
    const idx = PLANS.findIndex((p) => p.id === pid)
    if (idx >= 0) PLANS.splice(idx, 1)
    for (let i = BATCHES.length - 1; i >= 0; i--) {
      if (BATCHES[i].planId === pid) BATCHES.splice(i, 1)
    }
    return null
  },
  async listPlans(projectId) {
    await delay(60)
    return clone(PLANS.filter((p) => !projectId || p.projectId === projectId))
  },
  async getPlan(id) {
    await delay(60)
    const p = PLANS.find((x) => x.id === Number(id))
    if (!p) throw new Error('方案不存在')
    return clone(p)
  },
  async savePlan(id, payload) {
    await delay(120)
    const p = PLANS.find((x) => x.id === Number(id))
    if (p) {
      p.checks = clone(payload.checks || p.checks)
      p.name = payload.name || p.name
    }
    return clone(p)
  },
  async members() {
    await delay(40)
    return clone(MEMBERS)
  },
  async checkTypes() {
    await delay(40)
    return clone(CHECK_TYPES)
  },

  // 试运行：只跑当前一项，不产生正式批次
  async dryRun(planId, checkId, deviceId) {
    await delay(700)
    const plan = PLANS.find((x) => x.id === Number(planId))
    const check = plan?.checks.find((c) => c.id === Number(checkId))
    if (!check) throw new Error('检查项不存在')
    const st = check.code === 'A-04' ? 'FINDING' : 'PASS'
    return {
      checkId: check.id, code: check.code, title: check.title, status: st,
      durationMs: 700, metrics: metricsOf(check, st), rawOutput: RAW_OUTPUT[check.id] || '',
      message: messageOf(check, st) + (deviceId ? `（试运行设备 ${deviceId}）` : ''),
      findings: st === 'FINDING'
        ? [{ code: 'P-001', device: 'ACC-6F', detail: '未启用 SSH，仍存在 telnet 通道', severity: 'high' }]
        : []
    }
  },

  async runPlan(planId) {
    await delay(120)
    const plan = PLANS.find((x) => x.id === Number(planId))
    if (!plan) throw new Error('方案不存在')
    const batchId = 'B-' + String(seq).padStart(3, '0')
    const no = `YS-2026-${new Date().toISOString().slice(5, 10).replace('-', '')}-${seq}`
    seq += 1
    const enabled = plan.checks.filter((c) => c.enabled)
    const batch = {
      batchId, planId: plan.id, no, status: 'RUNNING',
      startedAt: new Date().toLocaleString('sv-SE').replace('T', ' ').slice(0, 19),
      finishedAt: null,
      items: enabled.map((c) => ({
        checkId: c.id, code: c.code, title: c.title, type: c.type,
        owner: c.owner, severity: c.severity,
        status: c.needConfirm ? 'WAITING' : 'WAITING',
        durationMs: 0, metrics: {}, rawOutput: '', message: '排队中', findings: []
      })),
      _plan: plan,
      _startedMs: Date.now()
    }
    runtime.set(batchId, batch)
    return { batchId, no, status: 'RUNNING' }
  },

  async stopBatch(batchId) {
    await delay(120)
    const batch = runtime.get(batchId) || BATCHES.find((b) => b.batchId === batchId)
    if (!batch) throw new Error('批次不存在')
    if (batch.status !== 'RUNNING') throw new Error('批次不在运行中（当前 ' + batch.status + '）')
    batch._stopped = true
    return this.getBatch(batchId)
  },

  async voidBatch(batchId, reason = '') {
    await delay(120)
    const batch = runtime.get(batchId) || BATCHES.find((b) => b.batchId === batchId)
    if (!batch) throw new Error('批次不存在')
    if (batch.status !== 'DONE') throw new Error('只有已完成的批次才能作废（当前 ' + batch.status + '）')
    batch.status = 'VOIDED'
    batch.finishedAt = new Date().toLocaleString('sv-SE').replace('T', ' ').slice(0, 19)
    batch.statusMessage = '已作废：' + (reason || '未填写原因')
    return { ...batch }
  },

  async deleteBatch(batchId) {
    await delay(120)
    const idx = BATCHES.findIndex((b) => b.batchId === batchId)
    if (idx >= 0) {
      if (!['INTERRUPTED', 'FAILED'].includes(BATCHES[idx].status)) {
        throw new Error('仅「已中断/执行失败」的批次可删除；已完成批次请使用作废')
      }
      BATCHES.splice(idx, 1)
    }
    runtime.delete(batchId)
    return { deleted: true, batchId }
  },

  async getBatch(batchId) {
    let batch = runtime.get(batchId) || BATCHES.find((b) => b.batchId === batchId)
    if (!batch) throw new Error('批次不存在')
    if (batch._stopped) {
      batch.status = 'INTERRUPTED'
      batch.finishedAt = new Date().toLocaleString('sv-SE').replace('T', ' ').slice(0, 19)
      batch.statusMessage = '人工终止：后续检查项未执行'
      batch.items.forEach((it) => {
        if (it.status === 'WAITING' || it.status === 'PENDING') {
          it.status = 'SKIPPED'
          it.message = '人工终止，该项未执行'
        }
      })
      batch._stopped = false
    }
    if (batch._startedMs) {
      const plan = batch._plan
      const elapsed = Date.now() - batch._startedMs
      const per = 1100
      const doneCount = Math.min(batch.items.length, Math.floor(elapsed / per))
      for (let i = 0; i < doneCount; i += 1) {
        const it = batch.items[i]
        if (it.status !== 'WAITING') continue
        const check = plan.checks.find((c) => c.id === it.checkId)
        const st = check.code === 'A-04' ? 'FINDING' : (check.needConfirm ? 'PENDING' : 'PASS')
        it.status = st
        it.durationMs = 900 + (i * 173) % 1500
        it.metrics = metricsOf(check, st)
        it.rawOutput = RAW_OUTPUT[check.id] || ''
        it.message = messageOf(check, st)
        it.findings = st === 'FINDING'
          ? [{ code: 'P-001', device: 'ACC-6F', detail: '未启用 SSH，仍存在 telnet 通道', severity: 'high' }]
          : []
      }
      const allDone = batch.items.every((x) => x.status !== 'WAITING')
      if (allDone) {
        batch.status = 'DONE'
        batch.finishedAt = new Date().toLocaleString('sv-SE').replace('T', ' ').slice(0, 19)
      }
    }
    const finished = batch.items.filter((x) => x.status !== 'WAITING').length
    return {
      batchId: batch.batchId, planId: batch.planId, no: batch.no, status: batch.status,
      startedAt: batch.startedAt, finishedAt: batch.finishedAt,
      progress: { done: finished, total: batch.items.length, current: batch.items[finished]?.title || '' },
      items: clone(batch.items)
    }
  },

  async listBatches(planId) {
    await delay(60)
    const done = BATCHES.filter((b) => b.planId === Number(planId))
    const running = [...runtime.values()]
      .filter((b) => b.planId === Number(planId))
      .map((b) => ({ batchId: b.batchId, planId: b.planId, no: b.no, status: b.status, startedAt: b.startedAt, finishedAt: b.finishedAt, items: b.items }))
    return clone([...running, ...done])
  },

  // 全局视角：跨方案查询运行中的批次（演示模式合并 runtime 与内置演示数据）
  async listRunningBatches() {
    await delay(60)
    const viewOf = (b) => {
      const plan = PLANS.find((p) => p.id === b.planId) || {}
      const project = PROJECTS.find((p) => p.id === plan.projectId) || {}
      const finished = b.items.filter((x) => x.status !== 'WAITING').length
      return {
        batchId: b.batchId, planId: b.planId, no: b.no, status: b.status,
        startedAt: b.startedAt, finishedAt: b.finishedAt,
        planName: plan.name || '', planVersion: plan.version || '',
        projectName: project.name || '',
        progress: { done: finished, total: b.items.length, current: b.items[finished]?.title || '' }
      }
    }
    const running = [...runtime.values()].filter((b) => b.status === 'RUNNING')
    const demo = BATCHES.filter((b) => b.status === 'RUNNING')
    return clone([...running, ...demo].map(viewOf))
  },

  async generateReport(batchId) {
    await delay(600)
    const batch = runtime.get(batchId) || BATCHES.find((b) => b.batchId === batchId)
    if (!batch) throw new Error('批次不存在')
    return { reportId: 'R-' + batchId, no: batch.no, name: `验收报告_${batch.no}.docx`, batchId }
  },

  // 演示用 AI 摘要：固定样例文案，模拟本地大模型输出三段式结构
  async aiStatus() {
    await delay(60)
    return { enabled: true, provider: 'ollama', model: 'qwen2.5:7b（演示）', providerLabel: '本地模型（Ollama）' }
  },

  async aiSummary(batchId) {
    await delay(1200)
    const batch = runtime.get(batchId) || BATCHES.find((b) => b.batchId === batchId)
    if (!batch) throw new Error('批次不存在')
    const bad = (batch.items || []).filter((x) => x.status === 'FAIL' || x.status === 'FINDING')
    return {
      batchId,
      no: batch.no,
      batchStatus: batch.status,
      summary: `本批次共执行 ${(batch.items || []).length} 个检查项，通过 ${
        (batch.items || []).filter((x) => x.status === 'PASS').length
      } 项${bad.length ? `，发现 ${bad.length} 项异常，整体结论倾向「有条件通过」` : '，整体结论倾向「通过」'}。建议核对问题项后复测确认。`,
      rootCauses: bad.length
        ? bad.map((x) => `${x.code} ${x.title}：${x.message || '实测指标未达判定准则'}`)
        : ['未发现异常项'],
      suggestions: bad.length
        ? bad.map((x) => `${x.code}：核查设备配置与链路状态后，在整改完成后重新运行本批次复测`)
        : ['无需整改'],
      provider: 'ollama',
      model: 'qwen2.5:7b（演示）',
      generatedAt: new Date().toLocaleString('sv-SE').replace('T', ' ').slice(0, 19),
      disclaimer: '本摘要由 AI 生成（演示数据），仅供参考，不作为验收判定依据'
    }
  },

  // 演示用：mock 端无 POI，仍返回文本内容（真实实现后端 POI 出 docx）
  async downloadReport(reportId) {
    await delay(200)
    const batchId = String(reportId).replace(/^R-/, '')
    const batch = runtime.get(batchId) || BATCHES.find((b) => b.batchId === batchId)
    const plan = PLANS.find((p) => p.id === batch?.planId)
    const project = PROJECTS.find((p) => p.id === plan?.projectId)
    const lines = []
    lines.push('综合实训大楼网络工程 · 测试验收报告')
    lines.push('='.repeat(52))
    lines.push(`报告编号：${batch?.no || ''}`)
    lines.push(`工程名称：${project?.name || ''}`)
    lines.push(`建设单位：${project?.owner || ''}    承建单位：${project?.contractor || ''}`)
    lines.push(`验收方案：${plan?.name || ''}（${plan?.version || ''}）`)
    lines.push(`测试批次：${batch?.batchId || ''}    ${batch?.startedAt || ''}`)
    lines.push('')
    lines.push('1  验收依据')
    lines.push('   GB 50312-2016  综合布线系统工程验收规范')
    lines.push('   GB 50339-2013  智能建筑工程质量验收规范')
    lines.push('   GB/T 21671-2018 基于以太网技术的局域网系统验收测评规范')
    lines.push('   GB 50311-2016  综合布线系统工程设计规范')
    lines.push('   GB/T 22239-2019 信息安全技术 网络安全等级保护基本要求')
    lines.push('')
    lines.push('2  工程概况')
    lines.push(`   建筑：${project?.building || ''}`)
    lines.push(`   规模：${project?.deviceCount || 0} 台网络设备；${project?.note || ''}`)
    lines.push('')
    lines.push('3  验收项清单')
    lines.push('   编号   名称           负责人  级别      判定准则')
    ;(plan?.checks || []).filter((c) => c.enabled).forEach((c) => {
      const rule = describeCheck(c, CHECK_TYPES) || c.assertExpr
      lines.push(`   ${c.code}   ${c.title.padEnd(12, '　')} ${c.owner}    ${c.severity.padEnd(8)}  ${rule}`)
    })
    lines.push('')
    lines.push('4  分项测试记录')
    ;(batch?.items || []).forEach((it) => {
      lines.push('')
      lines.push(`4.${it.code.slice(-2)}  ${it.title}（${it.code}）`)
      const _c = plan?.checks.find((c) => c.id === it.checkId)
      lines.push(`     判定准则：${(_c && (describeCheck(_c, CHECK_TYPES) || _c.assertExpr)) || ''}`)
      lines.push(`     实测结果：${it.status}    耗时：${it.durationMs} ms`)
      lines.push(`     实测数据：${JSON.stringify(it.metrics)}`)
      lines.push(`     说明：${it.message}`)
    })
    lines.push('')
    lines.push('5  问题清单')
    const findings = (batch?.items || []).flatMap((it) => (it.findings || []).map((f) => ({ ...f, code: it.code })))
    if (!findings.length) {
      lines.push('   本批次未发现问题。')
    } else {
      lines.push('   编号     所在项  设备      描述                                    等级')
      findings.forEach((f, i) => {
        lines.push(`   P-${String(i + 1).padStart(3, '0')}  ${f.code}  ${f.device.padEnd(9)} ${f.detail.slice(0, 36)}  ${f.severity}`)
      })
    }
    lines.push('')
    lines.push('6  验收结论')
    const hasFinding = findings.length > 0
    lines.push(`   ${hasFinding ? '有条件合格：存在 1 项待整改问题，整改后复检。' : '合格。'}`)
    lines.push('')
    lines.push('7  签字')
    lines.push('   建设单位（签章）：____________    日期：________')
    lines.push('   承建单位（签章）：____________    日期：________')
    lines.push('   验收单位（签章）：____________    日期：________')
    lines.push('')
    lines.push('（本报告由 eNSP-NMS 验收工作台按方案自动生成，数据取自批次快照，未重新执行测试）')
    return new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' })
  }
}
