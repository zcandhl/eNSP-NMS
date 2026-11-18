<template>
  <div class="nms-page report-page" v-loading="loading">
    <div class="nms-page-header">
      <div class="nms-page-title-block">
        <h1 class="nms-page-title">验收报告</h1>
        <p class="nms-page-subtitle">{{ subtitle }}</p>
      </div>
      <div class="nms-page-actions">
        <el-button :loading="loading" @click="reload">刷新</el-button>
        <el-button
          type="primary"
          :disabled="!canExport"
          :loading="generating"
          @click="doExport"
        >
          生成并下载 Word
        </el-button>
      </div>
    </div>

    <div class="rp-grid">
      <!-- 左：批次档案 -->
      <aside class="rp-list nms-panel">
        <div class="rp-list-head">
          <span class="rl-title">批次档案</span>
          <span class="rl-count">{{ batches.length }}</span>
        </div>
        <div class="rp-list-body">
          <template v-for="p in grouped" :key="p.planId">
            <div class="rp-group-head">
              <span class="rg-name" :title="p.planName">{{ p.planName }}</span>
              <em v-if="!nameHasVer(p.planName)" class="rg-ver">v{{ p.planVersion || '—' }}</em>
            </div>
            <button
              v-for="b in p.batches"
              :key="b.batchId"
              class="rp-batch"
              :class="{ active: b.batchId === selectedId, blocked: b.status !== 'DONE' }"
              :title="b.status !== 'DONE' ? `${statusText(b)}批次不满足出具条件，仅可查看快照` : '点击预览报告'"
              @click="selectBatch(b)"
            >
              <i class="dot" :class="statusLevel(b)"></i>
              <span class="rb-main">
                <b class="rb-no">{{ b.no || b.batchId }}</b>
                <em class="rb-meta">{{ b.startedAt || '—' }} · {{ itemsText(b) }}</em>
              </span>
              <span class="rb-rate" :class="{ ok: rateOf(b).rate >= 90, bad: rateOf(b).rate < 60 }">
                {{ rateOf(b).total ? rateOf(b).rate + '%' : '—' }}
              </span>
            </button>
          </template>
          <div v-if="!batches.length" class="rp-list-empty">
            <p>还没有验收批次</p>
            <el-button link type="primary" @click="goWorkbench">去工作台运行一次</el-button>
          </div>
        </div>
        <div class="rp-list-foot">
          <el-icon><InfoFilled /></el-icon>
          只有「已完成」批次可出具正式报告
        </div>
      </aside>

      <!-- 右：报告预览 -->
      <main class="rp-main">
        <div v-if="!current" class="rp-blank nms-panel">
          <el-empty description="左侧选择一个批次，这里显示报告预览" :image-size="72" />
        </div>

        <template v-else>
          <div class="rp-toolbar">
            <div class="rt-anchors">
              <button
                v-for="s in SECTIONS"
                :key="s.id"
                class="rt-anchor"
                @click="jump(s.id)"
              >
                {{ s.label }}
              </button>
            </div>
            <div class="rt-status" :class="current.status === 'DONE' ? 'ready' : 'blocked'">
              <template v-if="lastGenerated">
                已于 {{ lastGenerated.at }} 生成 {{ lastGenerated.name }}
              </template>
              <template v-else-if="current.status === 'DONE'">
                快照完整 · 可生成 Word
              </template>
              <template v-else>
                {{ statusText(current) }}批次 · 不可出具，仅可查看
              </template>
            </div>
          </div>

          <div class="rp-scroll" ref="scrollEl">
            <article class="rp-paper">
              <!-- 封面 -->
              <header class="paper-cover">
                <div class="pc-org">{{ project?.owner || '验收工程' }}</div>
                <h2 class="pc-title">{{ project?.name || '网络工程' }}</h2>
                <div class="pc-sub">测 试 验 收 报 告</div>
                <div class="pc-verdict" :class="`tone-${verdict.tone}`">
                  验收结论：{{ verdict.label }}
                </div>
                <div class="pc-stat">
                  共 {{ stats.total }} 项 · 通过 {{ stats.pass }} · 待整改 {{ stats.finding }} ·
                  未达标 {{ stats.blocking }}
                </div>
                <div class="pc-note" v-if="current.status !== 'DONE'">
                  注：该批次为「{{ statusText(current) }}」，以下为已冻结的部分快照，不构成验收结论。
                </div>
              </header>

              <div class="paper-meta">
                <div v-for="m in metaRows" :key="m.k" class="pm-row">
                  <span class="pm-k">{{ m.k }}</span>
                  <span class="pm-v" :class="{ mono: m.mono }">{{ m.v }}</span>
                </div>
              </div>

              <!-- 一、验收依据 -->
              <section :id="'sec-basis'" class="paper-sec">
                <h3 class="ps-h1">一、验收依据</h3>
                <ul class="ps-list">
                  <li v-for="b in BASIS" :key="b">{{ b }}</li>
                </ul>
              </section>

              <!-- 二、工程概况 -->
              <section id="sec-overview" class="paper-sec">
                <h3 class="ps-h1">二、工程概况</h3>
                <table class="ps-kv">
                  <tbody>
                    <tr v-for="r in overviewRows" :key="r.k">
                      <th>{{ r.k }}</th>
                      <td>{{ r.v }}</td>
                    </tr>
                  </tbody>
                </table>
              </section>

              <!-- 三、验收组织 -->
              <section id="sec-org" class="paper-sec">
                <h3 class="ps-h1">三、验收组织与人员分工</h3>
                <table class="ps-table">
                  <thead>
                    <tr>
                      <th style="width: 18%">角色</th>
                      <th style="width: 26%">单位 / 来源</th>
                      <th style="width: 24%">人员</th>
                      <th>主要职责</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(r, i) in orgRows" :key="i">
                      <td>{{ r.role }}</td>
                      <td>{{ r.org }}</td>
                      <td>{{ r.person }}</td>
                      <td>{{ r.duty }}</td>
                    </tr>
                  </tbody>
                </table>
                <p class="ps-fine">注：人员名单取自系统账号与工程档案，现场签到以签字栏为准。</p>
              </section>

              <!-- 四、测试方法与环境 -->
              <section id="sec-method" class="paper-sec">
                <h3 class="ps-h1">四、测试方法与环境说明</h3>
                <ul class="ps-list">
                  <li v-for="m in METHOD" :key="m">{{ m }}</li>
                </ul>
              </section>

              <!-- 五、验收项清单 -->
              <section id="sec-checklist" class="paper-sec">
                <h3 class="ps-h1">五、验收项清单与判定准则</h3>
                <table v-if="checklist.length" class="ps-table dense">
                  <thead>
                    <tr>
                      <th style="width: 7%">序号</th>
                      <th style="width: 12%">编号</th>
                      <th style="width: 26%">检查项</th>
                      <th style="width: 14%">负责人</th>
                      <th style="width: 10%">级别</th>
                      <th>判定准则</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(c, i) in checklist" :key="c.code || i">
                      <td>{{ i + 1 }}</td>
                      <td class="mono">{{ c.code }}</td>
                      <td>{{ c.title }}</td>
                      <td>{{ nameOf(c.owner) }}</td>
                      <td>{{ severityText(c.severity) }}</td>
                      <td>{{ describeCheck(c, checkTypes) || c.assertExpr || '—' }}</td>
                    </tr>
                  </tbody>
                </table>
                <p v-else class="ps-fine">（方案未配置检查项）</p>
              </section>

              <!-- 六、分项测试结果 -->
              <section id="sec-result" class="paper-sec">
                <h3 class="ps-h1">六、分项测试结果</h3>
                <h4 class="ps-h2">6.1　分项判定汇总</h4>
                <table v-if="items.length" class="ps-table dense">
                  <thead>
                    <tr>
                      <th style="width: 11%">编号</th>
                      <th style="width: 22%">检查项</th>
                      <th style="width: 27%">判定准则</th>
                      <th style="width: 26%">关键实测值</th>
                      <th>单项结论</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="it in items" :key="it.checkId || it.code">
                      <td class="mono">{{ it.code }}</td>
                      <td>{{ it.title }}</td>
                      <td>{{ exprOf(it) }}</td>
                      <td class="mono expr">{{ brief(it.metrics, it.type) }}</td>
                      <td>
                        <span class="ps-chip" :class="chipTone(it.status)">
                          {{ statusLabel(it.status) }}
                        </span>
                      </td>
                    </tr>
                  </tbody>
                </table>
                <p v-else class="ps-fine">（批次快照为空）</p>

                <h4 class="ps-h2">6.2　逐项判定说明</h4>
                <div v-for="(it, i) in items" :key="'d' + (it.checkId || i)" class="ps-item">
                  <div class="pi-title">
                    6.2.{{ i + 1 }}　{{ it.title }}（<span class="mono">{{ it.code }}</span>）
                  </div>
                  <p class="pi-line"><em>判定准则：</em><span>{{ exprOf(it) }}</span></p>
                  <p class="pi-line">
                    <em>实测情况：</em>{{ it.message || '—' }}。关键实测值：<span class="mono">{{ brief(it.metrics, it.type) }}</span>。执行耗时
                    {{ it.durationMs != null ? it.durationMs + ' ms' : '—' }}，负责人 {{ nameOf(it.owner) }}。
                  </p>
                  <p class="pi-line pi-verdict">
                    <em>判定结论：</em>
                    <b :class="chipTone(it.status)">{{ statusLabel(it.status) }}</b>
                  </p>
                  <p
                    v-for="(f, fi) in it.findings || []"
                    :key="'f' + fi"
                    class="pi-finding"
                  >
                    问题记录（{{ f.device || '—' }}，{{ severityText(f.severity) }}）：{{ f.detail || '—' }}
                  </p>
                </div>
              </section>

              <!-- 七、问题清单 -->
              <section id="sec-finding" class="paper-sec">
                <h3 class="ps-h1">七、问题清单</h3>
                <table v-if="findings.length" class="ps-table dense">
                  <thead>
                    <tr>
                      <th style="width: 9%">编号</th>
                      <th style="width: 10%">所在项</th>
                      <th style="width: 15%">设备</th>
                      <th style="width: 32%">问题描述</th>
                      <th style="width: 8%">等级</th>
                      <th style="width: 13%">整改责任人</th>
                      <th>建议期限</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(f, i) in findings" :key="'fd' + i">
                      <td class="mono">P-{{ String(i + 1).padStart(3, '0') }}</td>
                      <td class="mono">{{ f.code }}</td>
                      <td>{{ f.device || '—' }}</td>
                      <td class="fd-detail">{{ f.detail || '—' }}</td>
                      <td>{{ severityText(f.severity) }}</td>
                      <td>{{ f.owner ? nameOf(f.owner) : '承建单位' }}</td>
                      <td class="mono">{{ deadline }}</td>
                    </tr>
                  </tbody>
                </table>
                <p v-else class="ps-pass">本批次未发现问题。</p>
                <p v-if="findings.length" class="ps-fine">
                  注：建议整改期限默认为验收完成之日起 7 个自然日，复检通过后方可出具正式通过结论。
                </p>
              </section>

              <!-- 八、结论与统计 -->
              <section id="sec-conclusion" class="paper-sec">
                <h3 class="ps-h1">八、验收结论与统计</h3>
                <table class="ps-kv narrow">
                  <tbody>
                    <tr><th>检查项总数</th><td>{{ stats.total }}</td></tr>
                    <tr><th>通过（PASS）</th><td>{{ stats.pass }}</td></tr>
                    <tr><th>发现问题（FINDING）</th><td>{{ stats.finding }}</td></tr>
                    <tr><th>不通过（FAIL）</th><td>{{ stats.fail }}</td></tr>
                    <tr><th>跳过（SKIPPED）</th><td>{{ stats.skip }}</td></tr>
                    <tr><th>一次通过率</th><td>{{ stats.total ? stats.rate.toFixed(1) + '%' : '—' }}</td></tr>
                  </tbody>
                </table>
                <h4 class="ps-h2">结论意见</h4>
                <p class="ps-verdict-text" :class="`tone-${verdict.tone}`">{{ verdict.text }}</p>
                <p v-if="current.statusMessage" class="ps-fine">批次备注：{{ current.statusMessage }}</p>
              </section>

              <!-- 签字栏 + 附录提示 -->
              <section class="paper-sec">
                <h3 class="ps-h1">九、签字确认</h3>
                <div class="ps-sign">
                  <div class="sign-row"><span>建设单位（签章）</span><i></i><span>日期：____年__月__日</span></div>
                  <div class="sign-row"><span>承建单位（签章）</span><i></i><span>日期：____年__月__日</span></div>
                  <div class="sign-row"><span>验收单位（签章）</span><i></i><span>日期：____年__月__日</span></div>
                </div>
                <p class="ps-fine">
                  附录 A　采集原始记录（备查）：本批次 {{ rawCount }} 项含设备回显原文，正文不展开，见导出的 Word 附录页。
                </p>
              </section>

              <footer class="paper-foot">
                文件编号：{{ current.no || current.batchId }}　·　版本 V1.0　·　eNSP-NMS 验收工作台出具
              </footer>
            </article>
          </div>
        </template>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { InfoFilled } from '@element-plus/icons-vue'
import { acceptanceApi, mockMode } from '@/api/acceptance'
import { describeCheck, metricsText } from './components/judgeRule'
import { useProjectStore } from '@/stores/project'

const router = useRouter()

const loading = ref(false)
const generating = ref(false)
// 工程上下文来自全局 store（顶栏切换器是唯一入口）
const projectStore = useProjectStore()
const { projects, projectId } = storeToRefs(projectStore)
const plans = ref([])
const batches = ref([])
const selectedId = ref(null)
const current = ref(null)
const planDetail = ref(null)
const members = ref([])
const checkTypes = ref([])
const lastGenerated = ref(null)
const scrollEl = ref(null)

const SECTIONS = [
  { id: 'sec-basis', label: '依据' },
  { id: 'sec-overview', label: '概况' },
  { id: 'sec-org', label: '组织' },
  { id: 'sec-method', label: '方法' },
  { id: 'sec-checklist', label: '清单' },
  { id: 'sec-result', label: '结果' },
  { id: 'sec-finding', label: '问题' },
  { id: 'sec-conclusion', label: '结论' }
]

const BASIS = [
  'GB 50312-2016 《综合布线系统工程验收规范》',
  'GB 50339-2013 《智能建筑工程质量验收规范》',
  'GB/T 21671-2018 《基于以太网技术的局域网（LAN）系统验收测评规范》',
  'GB 50311-2016 《综合布线系统工程设计规范》',
  'GB/T 22239-2019 《信息安全技术 网络安全等级保护基本要求》'
]

const METHOD = [
  '采集通道：通过 SSH 逐台登录设备管理地址，执行 display 系列命令采集运行状态与配置，连通性项采用 TCP 探测。',
  '判定方式：各检查项预置判定表达式（如 ospf.neighborCount >= expectedNeighbors），采集结果结构化为指标后由引擎自动判定；涉及业务影响的人工确认项，由指定负责人实测后录入结论。',
  '测试环境：在 eNSP 中搭建与工程同构的园区网络仿真环境（拓扑结构与设备型号见系统数字孪生视图）；设备管理平面、SSH 采集通道与判定引擎按真机链路运行，物理介质与仪表测试环节由仿真环境替代。',
  '数据来源：报告数据全部取自批次快照，生成报告时不重新执行任何测试，同批次结果可完整复现。'
]

const project = computed(() => projects.value.find((p) => p.id === projectId.value) || null)

const subtitle = computed(() => {
  const parts = [project.value?.name || '未选择工程']
  if (plans.value.length) parts.push(`${plans.value.length} 个方案`)
  parts.push(`${batches.value.length} 个批次`)
  const done = batches.value.filter((b) => b.status === 'DONE').length
  parts.push(`可出具 ${done} 份`)
  if (mockMode.value) parts.push('演示数据')
  return parts.filter(Boolean).join(' · ')
})

function ts(v) {
  if (!v) return NaN
  return new Date(String(v).replace(' ', 'T')).getTime()
}

function rateOf(b) {
  const items = b?.items || []
  const total = items.length
  const pass = items.filter((i) => i.status === 'PASS').length
  return { total, pass, rate: total ? Math.round((pass * 100) / total) : 0 }
}

function itemsText(b) {
  const r = rateOf(b)
  if (!r.total) return '无检查项'
  return `${r.pass}/${r.total} 项通过`
}

const grouped = computed(() => {
  const order = new Map(plans.value.map((p, i) => [p.id, i]))
  const map = new Map()
  batches.value.forEach((b) => {
    if (!map.has(b.planId)) map.set(b.planId, [])
    map.get(b.planId).push(b)
  })
  return [...map.entries()]
    .sort((a, b) => (order.get(a[0]) ?? 99) - (order.get(b[0]) ?? 99))
    .map(([planId, list]) => {
      const p = plans.value.find((x) => x.id === planId) || {}
      return {
        planId,
        planName: p.name || list[0]?.planName || '未命名方案',
        planVersion: p.version || list[0]?.planVersion,
        batches: list
      }
    })
})

const items = computed(() => current.value?.items || [])

const stats = computed(() => {
  const list = items.value
  const n = (s) => list.filter((i) => i.status === s).length
  const total = list.length
  const pass = n('PASS')
  return {
    total,
    pass,
    finding: n('FINDING'),
    fail: n('FAIL'),
    skip: n('SKIPPED'),
    pending: n('PENDING'),
    // 与报告结论同口径：不通过 = 未达标 + 未完成
    blocking: n('FAIL') + n('SKIPPED') + n('PENDING'),
    rate: total ? (pass * 100) / total : 0
  }
})

const verdict = computed(() => {
  if (!items.value.length) return { label: '待执行', tone: 'muted', text: '本批次没有可判定的检查项。' }
  const s = stats.value
  if (s.blocking > 0) {
    return {
      label: '不通过',
      tone: 'danger',
      text: `不通过：存在 ${s.blocking} 项未达标或未完成，承建单位整改完成后须重新组织验收。`
    }
  }
  if (s.finding > 0) {
    return {
      label: '有条件通过',
      tone: 'warn',
      text: `有条件通过：通过 ${s.pass}/${s.total} 项，另有 ${s.finding} 项存在待整改问题（整改责任与期限见问题清单），整改完成并复检通过后出具正式通过结论。`
    }
  }
  return {
    label: '通过',
    tone: 'ok',
    text: `通过：${s.pass}/${s.total} 项全部通过，本次验收通过。`
  }
})

const canExport = computed(() => !!current.value && current.value.status === 'DONE' && !generating.value)

const metaRows = computed(() => {
  const p = project.value || {}
  const plan = plans.value.find((x) => x.id === current.value?.planId)
  return [
    { k: '报告编号', v: current.value?.no || current.value?.batchId || '—', mono: true },
    { k: '工程名称', v: p.name || '—' },
    { k: '建设单位', v: p.owner || '—' },
    { k: '承建单位', v: p.contractor || '—' },
    { k: '验收单位', v: p.acceptanceOrg || '—' },
    { k: '验收方案', v: plan ? (nameHasVer(plan.name) ? plan.name : `${plan.name}（v${plan.version || '—'}）`) : '—' },
    { k: '测试批次', v: current.value?.batchId || '—', mono: true },
    { k: '开始时间', v: current.value?.startedAt || '—', mono: true },
    { k: '完成时间', v: current.value?.finishedAt || '（未完成）', mono: true }
  ]
})

const overviewRows = computed(() => {
  const p = project.value || {}
  return [
    { k: '工程名称', v: p.name || '—' },
    { k: '建筑与规模', v: p.building || '—' },
    { k: '纳管设备数', v: p.deviceCount != null ? `${p.deviceCount} 台` : '—' },
    { k: '建设单位', v: p.owner || '—' },
    { k: '承建单位', v: p.contractor || '—' },
    { k: '验收单位', v: p.acceptanceOrg || '—' },
    { k: '备注说明', v: p.note || '—' }
  ]
})

const executors = computed(() => {
  const set = new Set()
  items.value.forEach((i) => {
    if (i.owner) set.add(nameOf(i.owner))
  })
  return [...set].join('、') || '—'
})

const orgRows = computed(() => {
  const p = project.value || {}
  return [
    { role: '建设单位代表', org: p.owner || '—', person: '现场负责人', duty: '组织协调与验收结果确认' },
    { role: '承建单位负责人', org: p.contractor || '—', person: '现场负责人', duty: '工程交付与问题整改落实' },
    { role: '验收单位代表', org: p.acceptanceOrg || '—', person: '现场负责人', duty: '验收过程监督与结论审定' },
    { role: '测试执行', org: '验收工作台（本系统）', person: executors.value, duty: '按验收方案执行采集、判定与记录' }
  ]
})

const checklist = computed(() => {
  const checks = planDetail.value?.checks || []
  return checks.filter((c) => c.enabled !== false)
})

const findings = computed(() =>
  items.value.flatMap((it) =>
    (it.findings || []).map((f) => ({ ...f, code: it.code, owner: it.owner }))
  )
)

const rawCount = computed(() => items.value.filter((i) => i.rawOutput).length)

const deadline = computed(() => {
  const t = ts(current.value?.finishedAt)
  if (!Number.isFinite(t)) return '—'
  const d = new Date(t + 7 * 86400000)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

const STATUS_TEXT = {
  DONE: '已完成',
  RUNNING: '运行中',
  INTERRUPTED: '已中断',
  FAILED: '执行失败',
  VOIDED: '已作废'
}
const STATUS_LEVEL = {
  DONE: 'ok',
  RUNNING: 'info',
  INTERRUPTED: 'warn',
  FAILED: 'danger',
  VOIDED: 'muted'
}
const LABEL_TEXT = {
  PASS: '通过',
  FINDING: '发现问题（待整改）',
  FAIL: '不通过',
  SKIPPED: '已跳过',
  PENDING: '待执行',
  WAITING: '待确认',
  RUNNING: '执行中'
}

function statusText(b) {
  return STATUS_TEXT[b?.status] || b?.status || '未知'
}
function statusLevel(b) {
  return STATUS_LEVEL[b?.status] || 'muted'
}
function statusLabel(s) {
  return LABEL_TEXT[s] || s || '—'
}
function chipTone(s) {
  if (s === 'PASS') return 'tone-ok'
  if (s === 'FINDING') return 'tone-warn'
  if (s === 'FAIL') return 'tone-danger'
  return 'tone-muted'
}
const SEVERITY_TEXT = { critical: '致命', high: '高', medium: '中', low: '低' }
function severityText(s) {
  return SEVERITY_TEXT[s] || '一般'
}

function nameOf(username) {
  if (!username) return '—'
  const m = members.value.find((x) => x.username === username)
  return m?.realName || username
}

/** 方案名末尾已带版本号（如"…方案 V1.0"）时不再重复拼 v1.0（与方案设计页同规则） */
const VER_TAIL = /\s*v?\d+(\.\d+){0,2}\s*$/i
function nameHasVer(name) {
  return VER_TAIL.test(String(name || ''))
}

function exprOf(item) {
  const c = (planDetail.value?.checks || []).find((x) => x.code === item?.code)
  if (!c) return '—'
  // 与方案设计/工作台同口径：优先一句话判定，解析不了回退表达式
  return describeCheck(c, checkTypes.value) || c.assertExpr || '—'
}

function brief(metrics, type) {
  const t = metricsText(type, metrics)
  return t || '—'
}

function jump(id) {
  const box = scrollEl.value
  if (!box) return
  const el = box.querySelector('#' + id)
  if (!el) return
  box.scrollTo({ top: Math.max(0, el.offsetTop - 12), behavior: 'smooth' })
}

async function selectBatch(b) {
  selectedId.value = b.batchId
  current.value = b
  lastGenerated.value = null
  if (!planDetail.value || planDetail.value.id !== b.planId) {
    try {
      planDetail.value = await acceptanceApi.getPlan(b.planId)
    } catch (e) {
      planDetail.value = null
    }
  }
}

async function reload() {
  loading.value = true
  try {
    await projectStore.load(true)
    if (!projectId.value) {
      plans.value = []
      batches.value = []
      current.value = null
      return
    }
    plans.value = await acceptanceApi.listPlans(projectId.value)
    const groups = await Promise.all(
      plans.value.map(async (p) => {
        const list = (await acceptanceApi.listBatches(p.id)) || []
        return list.map((b) => ({ ...b, planId: p.id, planName: p.name, planVersion: p.version }))
      })
    )
    batches.value = groups
      .flat()
      .sort((a, b) => (ts(b.startedAt) || 0) - (ts(a.startedAt) || 0))

    const keep = batches.value.find((b) => b.batchId === selectedId.value)
    const target = keep || batches.value.find((b) => b.status === 'DONE') || batches.value[0]
    if (target) await selectBatch(target)
    else current.value = null
  } catch (e) {
    if (!mockMode.value) console.error('加载验收报告失败', e)
  } finally {
    loading.value = false
  }
}

async function doExport() {
  if (!current.value || current.value.status !== 'DONE') return
  generating.value = true
  try {
    const r = await acceptanceApi.generateReport(current.value.batchId)
    const blob = await acceptanceApi.downloadReport(r.reportId)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = r.name || `验收报告_${r.no}.docx`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    const d = new Date()
    lastGenerated.value = {
      no: r.no,
      name: r.name || '验收报告.docx',
      at: `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
    }
    ElMessage.success(`报告已生成：${r.no}`)
  } catch (e) {
    ElMessage.error('生成失败：' + (e?.response?.data?.message || e?.message || e))
  } finally {
    generating.value = false
  }
}

function goWorkbench() {
  router.push('/acceptance/workbench')
}

onMounted(async () => {
  try {
    members.value = await acceptanceApi.members()
  } catch (e) {
    members.value = []
  }
  try {
    checkTypes.value = await acceptanceApi.checkTypes()
  } catch (e) {
    checkTypes.value = []
  }
  await reload()
})

// 顶栏切换工程 → 本页自动刷新
watch(
  () => projectStore.projectId,
  (id, old) => {
    if (id && id !== old) reload()
  }
)
</script>

<style scoped lang="scss">
.report-page {
  height: calc(100vh - 96px);
  min-height: 600px;
  box-sizing: border-box;
}

.rp-sel {
  width: 210px;
}

.rp-grid {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: 316px minmax(0, 1fr);
  gap: 14px;
}

/* ── 左：批次档案 ── */
.rp-list {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.rp-list-head {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--nms-border-soft);
}

.rl-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--nms-text);
}

.rl-count {
  font-size: 12px;
  color: var(--nms-text-secondary);
  background: var(--nms-bg-elevated);
  border-radius: 10px;
  padding: 1px 8px;
}

.rp-list-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
}

.rp-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 8px 6px;
  font-size: 12px;
  color: var(--nms-text-secondary);
}

.rg-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rg-ver {
  flex: 0 0 auto;
  font-style: normal;
  color: var(--nms-text-muted);
}

.rp-batch {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
  transition: background 0.15s, border-color 0.15s;
}

.rp-batch:hover {
  background: var(--nms-bg-elevated);
}

.rp-batch.active {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.rp-batch.blocked {
  opacity: 0.66;
}

.dot {
  flex: 0 0 auto;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--nms-text-muted);
}
.dot.ok {
  background: var(--nms-success, #1f9d6a);
}
.dot.info {
  background: var(--el-color-primary);
}
.dot.warn {
  background: var(--el-color-warning);
}
.dot.danger {
  background: var(--el-color-danger);
}
.dot.muted {
  background: #b9c3d0;
}

.rb-main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.rb-no {
  font-family: var(--nms-mono);
  font-size: 13px;
  font-weight: 600;
  color: var(--nms-text);
}

.rb-meta {
  font-style: normal;
  font-size: 12px;
  color: var(--nms-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rb-rate {
  flex: 0 0 auto;
  font-size: 13px;
  font-weight: 600;
  color: var(--nms-text-secondary);
}
.rb-rate.ok {
  color: #1f9d6a;
}
.rb-rate.bad {
  color: #d4380d;
}

.rp-list-empty {
  padding: 40px 12px;
  text-align: center;
  font-size: 13px;
  color: var(--nms-text-secondary);

  p {
    margin-bottom: 6px;
  }
}

.rp-list-foot {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 9px 14px;
  border-top: 1px solid var(--nms-border-soft);
  background: #fafbfc;
  font-size: 12px;
  color: var(--nms-text-muted);
}

/* ── 右：预览 ── */
.rp-main {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  gap: 10px;
}

.rp-blank {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  justify-content: center;
}

.rp-toolbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 0 2px;
}

.rt-anchors {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

.rt-anchor {
  border: 1px solid var(--nms-border-soft);
  background: var(--nms-surface);
  color: var(--nms-text-secondary);
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 12px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;

  &:hover {
    color: var(--el-color-primary);
    border-color: var(--el-color-primary-light-5);
  }
}

.rt-status {
  margin-left: auto;
  font-size: 12px;
  color: var(--nms-text-secondary);
}
.rt-status.ready {
  color: #1f9d6a;
}
.rt-status.blocked {
  color: var(--el-color-warning);
}

.rp-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 6px 24px;
  scroll-behavior: smooth;
}

/* ── 纸张 ── */
.rp-paper {
  max-width: 940px;
  margin: 0 auto;
  background: var(--nms-surface);
  border: 1px solid var(--nms-border-soft);
  border-radius: 4px;
  box-shadow: 0 6px 24px rgba(16, 40, 76, 0.08);
  padding: 40px 52px 34px;
  color: #1f2937;
  font-size: 13.5px;
  line-height: 1.75;
}

.paper-cover {
  text-align: center;
  padding: 6px 0 22px;
  border-bottom: 2px solid #1f3864;
}

.pc-org {
  font-size: 13px;
  color: #6b7785;
  letter-spacing: 0.14em;
}

.pc-title {
  margin: 10px 0 4px;
  font-size: 25px;
  font-weight: 700;
  color: #1f3864;
  letter-spacing: 0.01em;
}

.pc-sub {
  font-size: 19px;
  font-weight: 600;
  color: #1f3864;
  letter-spacing: 0.34em;
  margin-bottom: 14px;
}

.pc-verdict {
  font-size: 17px;
  font-weight: 700;
  margin-bottom: 4px;
}

.pc-stat {
  font-size: 12.5px;
  color: #595959;
}

.pc-note {
  margin-top: 10px;
  font-size: 12px;
  color: #c05621;
  background: #fff7e8;
  border: 1px solid #f5d9a8;
  border-radius: 4px;
  padding: 6px 10px;
  display: inline-block;
  text-align: left;
}

.paper-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0;
  margin: 18px 0 6px;
  border: 1px solid #dfe5ec;
  border-bottom: none;
}

.pm-row {
  display: flex;
  border-bottom: 1px solid #dfe5ec;
  font-size: 12.5px;

  &:nth-child(odd) {
    border-right: 1px solid #dfe5ec;
  }
}

.pm-k {
  flex: 0 0 88px;
  padding: 6px 10px;
  background: #f4f6f9;
  color: #4a5568;
  border-right: 1px solid #dfe5ec;
}

.pm-v {
  flex: 1;
  min-width: 0;
  padding: 6px 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mono {
  font-family: var(--nms-mono);
}

.paper-sec {
  margin-top: 22px;
}

.ps-h1 {
  font-size: 16px;
  font-weight: 700;
  color: #1f3864;
  margin: 0 0 10px;
  padding-bottom: 6px;
  border-bottom: 1px solid #e5eaf0;
}

.ps-h2 {
  font-size: 13.5px;
  font-weight: 700;
  color: #1f3864;
  margin: 14px 0 8px;
}

.ps-list {
  margin: 0;
  padding-left: 20px;

  li {
    margin: 3px 0;
  }
}

.ps-kv {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;

  th,
  td {
    border: 1px solid #dfe5ec;
    padding: 6px 10px;
    text-align: left;
    vertical-align: top;
  }

  th {
    width: 110px;
    background: #f4f6f9;
    font-weight: 500;
    color: #4a5568;
  }
}

.ps-kv.narrow {
  width: 420px;
}

.ps-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
  table-layout: fixed;

  th,
  td {
    border: 1px solid #dfe5ec;
    padding: 6px 8px;
    text-align: left;
    vertical-align: top;
    word-break: break-all;
  }

  th {
    background: #f4f6f9;
    font-weight: 600;
    color: #33415c;
  }

  &.dense {
    font-size: 12px;

    th,
    td {
      padding: 5px 7px;
    }
  }
}

.expr {
  font-size: 11.5px;
  color: #4a5568;
}

.ps-chip {
  display: inline-block;
  padding: 0 7px;
  border-radius: 3px;
  font-size: 11.5px;
  line-height: 19px;
  border: 1px solid transparent;
  white-space: nowrap;
}

.ps-item {
  margin: 12px 0 4px;
  padding-left: 12px;
  border-left: 2px solid #e5eaf0;
}

.pi-title {
  font-weight: 700;
  color: #33415c;
  margin-bottom: 2px;
}

.pi-line {
  margin: 1px 0;
  font-size: 12.5px;

  em {
    font-style: normal;
    color: #6b7785;
  }
}

.pi-verdict b {
  font-weight: 700;
}

.pi-finding {
  margin: 2px 0;
  font-size: 12.5px;
  color: #c00000;
  font-weight: 600;
}

.fd-detail {
  color: #c00000;
  font-weight: 600;
}

.ps-pass {
  color: #0f7b4f;
  font-size: 13px;
}

.ps-fine {
  margin-top: 8px;
  font-size: 11.5px;
  color: #7a8794;
}


.ps-verdict-text {
  font-size: 13.5px;
  font-weight: 700;
  line-height: 1.7;
}

.ps-sign {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.sign-row {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  font-size: 12.5px;

  i {
    flex: 1;
    border-bottom: 1px solid #9aa6b4;
    height: 1px;
  }

  span:last-child {
    flex: 0 0 auto;
    color: #595959;
  }
}

.paper-foot {
  margin-top: 26px;
  padding-top: 10px;
  border-top: 1px solid #e5eaf0;
  font-size: 11.5px;
  color: #8a95a2;
  text-align: center;
}

/* 结论 / 状态着色 */
.tone-ok {
  color: #0f7b4f;
}
.tone-warn {
  color: #b35c00;
}
.tone-danger {
  color: #c00000;
}
.tone-muted {
  color: #7a8794;
}

.ps-chip.tone-ok {
  background: #eaf7f0;
  border-color: #bfe4d2;
}
.ps-chip.tone-warn {
  background: #fff5e6;
  border-color: #f3d8a8;
}
.ps-chip.tone-danger {
  background: #fdecec;
  border-color: #f0c2c2;
}
.ps-chip.tone-muted {
  background: #f2f4f7;
  border-color: #dde3ea;
}

@media (max-width: 1400px) {
  .rp-paper {
    padding: 30px 32px 28px;
  }
}
</style>
