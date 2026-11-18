<template>
  <div class="nms-page acc-overview" v-loading="loading">
    <div class="nms-page-header">
      <div class="nms-page-title-block">
        <h1 class="nms-page-title">验收总览</h1>
        <p class="nms-page-subtitle">{{ subtitle }}</p>
      </div>
      <div class="nms-page-actions">
        <el-button :loading="loading" @click="reload">刷新</el-button>
      </div>
    </div>

    <div class="metric-grid">
      <div v-for="m in metrics" :key="m.key" class="metric-card">
        <div class="mc-label">{{ m.label }}</div>
        <div class="mc-value" :class="{ danger: m.danger, muted: m.value === '—' }">
          {{ m.value }}<span v-if="m.unit" class="mc-unit">{{ m.unit }}</span>
        </div>
        <div class="mc-foot" :class="m.footClass">{{ m.foot }}</div>
      </div>
    </div>

    <div class="ov-row">
      <section class="nms-panel ov-chart">
        <div class="panel-head">
          <h2>通过率趋势</h2>
          <span class="ph-extra">{{ trend.length ? `近 ${trend.length} 批` : '' }}</span>
        </div>
        <div v-if="trend.length" class="chart">
          <div class="chart-y">
            <span
              v-for="g in GRID"
              :key="'y' + g.p"
              :style="{ top: `calc((100% - ${LABEL_ZONE}px) * ${g.p / 100})` }"
            >{{ g.v }}</span>
          </div>
          <div class="chart-plot">
            <i
              v-for="g in GRID"
              :key="'g' + g.p"
              class="gl"
              :class="{ base: g.p === 100 }"
              :style="{ top: `calc((100% - ${LABEL_ZONE}px) * ${g.p / 100})` }"
            ></i>
            <div class="bars">
              <div
                v-for="(t, i) in trend"
                :key="t.label + i"
                class="bar-col"
                :title="`${t.label} · 通过率 ${t.rate}%`"
              >
                <div class="bar-slot">
                  <div
                    class="bar"
                    :class="{ latest: i === trend.length - 1 }"
                    :style="{ height: t.barH + '%' }"
                  ></div>
                </div>
                <div class="bar-val" :class="{ latest: i === trend.length - 1 }">{{ t.rate }}%</div>
                <div class="bar-label" :class="{ latest: i === trend.length - 1 }">{{ t.short }}</div>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="panel-empty">还没有已完成的批次，运行一次后这里会出现趋势</div>
      </section>

      <section class="nms-panel ov-defect">
        <div class="panel-head">
          <h2>未关闭缺陷</h2>
          <span class="ph-extra">{{ defects.length }} 个</span>
        </div>
        <div class="defect-body">
          <div v-for="(d, i) in defects" :key="i" class="defect-row">
            <i class="dot" :class="d.level"></i>
            <span class="df-title" :title="d.title">{{ d.title }}</span>
            <span class="df-sev" :class="d.level">{{ d.severityText }}</span>
          </div>
          <div v-if="!defects.length" class="defect-empty">
            {{ lastResult ? '最近批次没有失败项' : '暂无批次数据' }}
          </div>
        </div>
        <div v-if="resumePlan" class="resume-card" @click="goWorkbench">
          <div class="rc-label">继续上次工作</div>
          <div class="rc-name">{{ resumePlan.name }}</div>
          <div class="rc-meta">{{ resumePlan.meta }}</div>
        </div>
      </section>
    </div>

    <section class="nms-panel ov-batches">
      <div class="panel-head">
        <h2>最近批次</h2>
        <div class="ph-right">
          <span class="ph-extra">共 {{ batches.length }} 批</span>
          <el-button link type="primary" class="ph-link" @click="goWorkbench">去执行台 ›</el-button>
        </div>
      </div>
      <div v-if="batches.length" class="batch-table">
        <div class="bt-head">
          <span class="c-status">状态</span>
          <span class="c-main">批次</span>
          <span class="c-plan">方案</span>
          <span class="c-time">开始时间</span>
          <span class="c-rate">通过率</span>
          <span class="c-act"></span>
        </div>
        <div v-for="b in recentBatches" :key="b.batchId" class="bt-row">
          <span class="c-status">
            <i class="dot" :class="statusLevel(b)"></i>
            <em :class="statusLevel(b)">{{ statusText(b) }}</em>
          </span>
          <span class="c-main">
            <b class="mono">{{ b.no || b.batchId }}</b>
            <em>{{ summaryText(b) }}</em>
          </span>
          <span class="c-plan" :title="b.planName">{{ b.planName || '—' }}</span>
          <span class="c-time mono">{{ b.startedAt || '—' }}</span>
          <span class="c-rate" :class="{ ok: statsOf(b).rate >= 90, bad: statsOf(b).rate < 60 }">
            {{ b.items?.length ? statsOf(b).rate + '%' : '—' }}
          </span>
          <span class="c-act">
            <el-button
              link
              type="primary"
              :disabled="b.status !== 'DONE'"
              :title="b.status === 'DONE' ? '查看该批次验收报告' : '批次未收尾，暂不可出具报告'"
              @click="goReport"
            >报告</el-button>
          </span>
        </div>
      </div>
      <div v-else class="panel-empty">还没有验收批次</div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { acceptanceApi, mockMode } from '@/api/acceptance'
import { useProjectStore } from '@/stores/project'

const router = useRouter()
const projectStore = useProjectStore()
const { projects, projectId } = storeToRefs(projectStore)

const loading = ref(false)
const plans = ref([])
const batches = ref([])

const projectName = computed(
  () => projects.value.find((p) => p.id === projectId.value)?.name || ''
)

const subtitle = computed(() => {
  if (!plans.value.length) {
    return `${projectName.value || '当前工程'} · 还没有验收方案，进入工作台新建`
  }
  const running = batches.value.filter((b) => b.status === 'RUNNING').length
  const parts = [projectName.value, `${plans.value.length} 个方案`, `${batches.value.length} 个批次`]
  if (running) parts.push(`${running} 个正在运行`)
  if (mockMode.value) parts.push('演示数据')
  return parts.filter(Boolean).join(' · ')
})

function ts(v) {
  if (!v) return NaN
  return new Date(String(v).replace(' ', 'T')).getTime()
}

function statsOf(b) {
  const items = b?.items || []
  const total = items.length
  const pass = items.filter((i) => i.status === 'PASS').length
  const fail = items.filter((i) => i.status === 'FAIL').length
  const skip = items.filter((i) => i.status === 'SKIPPED').length
  return { total, pass, fail, skip, rate: total ? Math.round((pass * 100) / total) : 0 }
}

const sortedBatches = computed(() =>
  [...batches.value].sort((a, b) => (ts(b.startedAt) || 0) - (ts(a.startedAt) || 0))
)

// 柱图刻度：p 为距顶部百分比，v 为对应通过率刻度值
const GRID = [
  { p: 0, v: 100 },
  { p: 25, v: 75 },
  { p: 50, v: 50 },
  { p: 75, v: 25 },
  { p: 100, v: 0 }
]
// 柱底下方留给「数值 + 批次号」两行的固定高度，需与 .bar-val / .bar-label 的高度之和一致
const LABEL_ZONE = 44

const doneBatches = computed(() => sortedBatches.value.filter((b) => b.status === 'DONE'))

/**
 * 最近一个"有判定结果"的批次。
 * 不限定 DONE：中断/失败批次里已判定的项仍然是真实结果，直接丢掉会让总览一片空白。
 * 指标卡脚注会如实标出批次状态（例如"已中断"），不把残缺批次说成完整结论。
 */
const lastResult = computed(
  () =>
    sortedBatches.value.find((b) =>
      (b.items || []).some((i) => i.status === 'PASS' || i.status === 'FAIL')
    ) || null
)

const checkTotal = computed(() =>
  plans.value.reduce((n, p) => n + (p.checks?.length || 0), 0)
)
const checkEnabled = computed(() =>
  plans.value.reduce((n, p) => n + (p.checks?.filter((c) => c.enabled).length || 0), 0)
)

const recentBatches = computed(() => sortedBatches.value.slice(0, 6))

const trend = computed(() =>
  doneBatches.value
    .slice(0, 7)
    .reverse()
    .map((b) => {
      const s = statsOf(b)
      const full = String(b.no || b.batchId || '')
      // 批次号形如 YS-20261113-014，柱子下方只放末段；完整号放 title
      const seg = full.split('-').filter(Boolean)
      return {
        rate: s.rate,
        // 柱高按百分比换算，0% 也留 2% 的可见底座
        barH: Math.max(2, Math.min(100, s.rate)),
        label: full,
        short: seg.length > 1 ? seg[seg.length - 1] : full
      }
    })
)

const SEVERITY_TEXT = { critical: '致命', high: '高', medium: '中', low: '低' }
const SEVERITY_LEVEL = { critical: 'danger', high: 'danger', medium: 'warn', low: 'muted' }

const defects = computed(() => {
  if (!lastResult.value) return []
  return (lastResult.value.items || [])
    .filter((i) => i.status === 'FAIL')
    .slice(0, 4)
    .map((i) => ({
      title: i.title || i.code || '未命名检查项',
      severityText: SEVERITY_TEXT[i.severity] || '一般',
      level: SEVERITY_LEVEL[i.severity] || 'warn'
    }))
})

const resumePlan = computed(() => {
  const p = plans.value[0]
  if (!p) return null
  const s = lastResult.value ? statsOf(lastResult.value) : null
  return {
    name:
      /v?\d+(\.\d+){0,2}\s*$/i.test(p.name || '')
        ? p.name
        : `${p.name}${p.version ? ` v${p.version}` : ''}`,
    meta: s
      ? `最近批次通过 ${s.pass}/${s.total} 项${lastResult.value.status !== 'DONE' ? `（${statusText(lastResult.value)}）` : ''}`
      : '还没有运行过批次'
  }
})

const metrics = computed(() => {
  const last = lastResult.value
  const s = last ? statsOf(last) : null
  const done = doneBatches.value
  const prevRate = done.length >= 2 ? statsOf(done[1]).rate : null
  const delta = s && prevRate != null ? s.rate - prevRate : null
  const partial = !!last && last.status !== 'DONE'

  const rateFoot = partial
    ? `${statusText(last)} · 已判定 ${s.pass + s.fail}/${s.total} 项`
    : delta == null
      ? '暂无对比'
      : `较上一批 ${delta >= 0 ? '+' : ''}${delta}%`

  return [
    {
      key: 'rate',
      label: '上次通过率',
      value: s ? s.rate : '—',
      unit: s ? '%' : '',
      foot: rateFoot,
      footClass: partial || delta == null ? 'muted' : delta >= 0 ? 'up' : 'down'
    },
    {
      key: 'items',
      label: '检查项',
      value: checkTotal.value || (s ? s.total : 0) || '—',
      unit: checkTotal.value || s ? ' 项' : '',
      foot: checkTotal.value
        ? `启用 ${checkEnabled.value} · 停用 ${checkTotal.value - checkEnabled.value}`
        : s
          ? `本批 ${s.total} 项`
          : '暂无检查项',
      footClass: 'muted'
    },
    {
      key: 'defect',
      label: '未关闭缺陷',
      value: s ? s.fail : '—',
      unit: s ? ' 个' : '',
      foot: s ? (s.fail ? '来自最近批次失败项' : '最近批次无失败项') : '暂无批次数据',
      footClass: 'muted',
      danger: !!s && s.fail > 0
    },
    {
      key: 'duration',
      label: '最近完成批次耗时',
      // 中断批次的 finishedAt 是"服务重启时刻"，不是真实结束时间，不能当耗时用
      value: done.length ? durationText(done[0]) : '—',
      unit: '',
      foot: done.length ? done[0].no || done[0].batchId : '暂无已完成批次',
      footClass: 'muted'
    }
  ]
})

function durationText(b) {
  const ms = ts(b?.finishedAt) - ts(b?.startedAt)
  if (!Number.isFinite(ms) || ms <= 0) return '—'
  const sec = Math.round(ms / 1000)
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  if (h) return m ? `${h} 小时 ${m} 分` : `${h} 小时`
  if (m) return s ? `${m} 分 ${s} 秒` : `${m} 分`
  return `${s} 秒`
}

/** 只有正常跑完的批次才有"耗时"；中断/失败的结束时间是回收时补写的，不能算 */
function runDurationText(b) {
  return b && b.status === 'DONE' ? durationText(b) : '未完成'
}

function summaryText(b) {
  const s = statsOf(b)
  if (!s.total) return '无检查项'
  return `${s.pass}/${s.total} 项通过 · ${runDurationText(b)}`
}

const STATUS_TEXT = { DONE: '已完成', RUNNING: '运行中', INTERRUPTED: '已中断', FAILED: '失败' }

function statusText(b) {
  if (b.status === 'RUNNING') {
    const items = b.items || []
    if (!items.some((i) => i.status === 'WAITING') && items.some((i) => i.status === 'PENDING')) {
      return '待确认'
    }
  }
  return STATUS_TEXT[b.status] || b.status || '未知'
}

function statusLevel(b) {
  if (b.status === 'DONE') return 'ok'
  if (b.status === 'RUNNING') return 'info'
  return 'muted'
}

async function reload() {
  loading.value = true
  try {
    await projectStore.load(true)
    if (!projectId.value) {
      plans.value = []
      batches.value = []
      return
    }
    plans.value = await acceptanceApi.listPlans(projectId.value)
    const groups = await Promise.all(
      plans.value.map(async (p) => {
        const list = await acceptanceApi.listBatches(p.id)
        return (list || []).map((b) => ({ ...b, planName: p.name }))
      })
    )
    batches.value = groups.flat()
  } catch (e) {
    if (!mockMode.value) {
      // 接口失败时保留上一次数据，不把页面清空
      console.error('加载验收总览失败', e)
    }
  } finally {
    loading.value = false
  }
}

// 总览是只读看板：不在页头放主按钮，执行入口弱化为批次面板的"去执行台"文字链，
// 避免"看板"长出"动作页"的语法（点进去反而是一张空执行页）。
function goWorkbench() {
  router.push(plans.value.length ? '/acceptance/workbench' : '/acceptance/plan')
}

function goReport() {
  router.push('/acceptance/report')
}

onMounted(reload)

// 顶栏切换工程 → 本页自动刷新（store 是唯一事实来源）
watch(
  () => projectStore.projectId,
  (id, old) => {
    if (id && id !== old) reload()
  }
)
</script>

<style scoped>
.ov-project {
  width: 200px;
}

.ph-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ph-link {
  font-size: 13px;
  padding: 0;
  height: auto;
}

/* 指标卡 */
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.metric-card {
  background: var(--nms-surface);
  border: 1px solid var(--nms-border-soft);
  border-radius: var(--nms-radius);
  padding: 18px;
  min-width: 0;
}

.mc-label {
  font-size: 13px;
  color: var(--nms-text-secondary);
}

.mc-value {
  font-size: 30px;
  font-weight: 600;
  line-height: 1.25;
  margin: 6px 0 4px;
  color: var(--nms-text);
  letter-spacing: -0.02em;
}

.mc-value.danger {
  color: var(--nms-danger);
}

.mc-value.muted {
  color: var(--nms-text-muted);
}

.mc-unit {
  font-size: 15px;
  font-weight: 400;
  color: var(--nms-text-secondary);
  margin-left: 2px;
}

.mc-foot {
  font-size: 13px;
  color: var(--nms-text-muted);
}

.mc-foot.up {
  color: var(--nms-success);
}

.mc-foot.down {
  color: var(--nms-danger);
}

.mc-foot.muted {
  color: var(--nms-text-muted);
}

/* 趋势 + 缺陷 */
.ov-row {
  display: flex;
  gap: 16px;
  align-items: stretch;
}

.ov-chart {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.ov-defect {
  width: 300px;
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--nms-border-soft);
}

.panel-head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--nms-text);
}

.ph-extra {
  margin-left: auto;
  font-size: 13px;
  color: var(--nms-text-muted);
}

/* 坐标图：左侧刻度 + 绘图区（网格线 + 柱） */
.chart {
  display: flex;
  flex: 1 1 auto;
  align-items: center;
  gap: 8px;
  padding: 12px 18px 14px;
  min-height: 0;
}

.chart-y {
  position: relative;
  flex: 0 0 auto;
  width: 26px;
  height: 148px;
}

.chart-y span {
  position: absolute;
  right: 0;
  transform: translateY(-50%);
  font-size: 11px;
  line-height: 1;
  color: var(--nms-text-muted);
}

.chart-plot {
  position: relative;
  flex: 1;
  min-width: 0;
  height: 148px;
}

.chart-plot .gl {
  position: absolute;
  left: 0;
  right: 0;
  height: 1px;
  background: var(--nms-border-soft);
}

.chart-plot .gl.base {
  background: #dce5f0;
}

.bars {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 12px;
}

.bar-col {
  /* 关键：限制单根柱的最大宽度。批次少的时候不再被拉成整块色板 */
  flex: 1 1 0;
  max-width: 84px;
  min-width: 0;
  text-align: center;
  display: flex;
  flex-direction: column;
  height: 100%;
}

/* 柱高百分比按这块区域算：整体高度减去下方两行文字 */
.bar-slot {
  height: calc(100% - 44px);
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.bar {
  width: 100%;
  background: #dbe9fb;
  border-radius: 6px 6px 0 0;
  transition: height 0.35s ease;
}

.bar.latest {
  background: var(--nms-primary);
}

.bar-val {
  height: 26px;
  line-height: 26px;
  font-size: 13px;
  color: var(--nms-text-secondary);
}

.bar-val.latest {
  color: var(--nms-primary);
  font-weight: 600;
}

/* 柱子下方批次号：只有末段（014），完整号在 title 里 */
.bar-label {
  height: 18px;
  line-height: 18px;
  font-size: 12px;
  color: var(--nms-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bar-label.latest {
  color: var(--nms-text-secondary);
  font-weight: 600;
}


.defect-body {
  flex: 1;
  padding: 6px 18px 12px;
}

.defect-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 0;
  border-bottom: 1px solid var(--nms-border-soft);
}

.defect-row:last-child {
  border-bottom: none;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: 0 0 auto;
  background: var(--nms-text-muted);
}

.dot.ok {
  background: var(--nms-success);
}

.dot.danger {
  background: var(--nms-danger);
}

.dot.warn {
  background: var(--nms-warning);
}

.dot.info {
  background: var(--nms-primary);
}

.dot.muted {
  background: #d9e1ec;
}

.df-title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.df-sev {
  font-size: 13px;
  flex: 0 0 auto;
}

.df-sev.danger {
  color: var(--nms-danger);
}

.df-sev.warn {
  color: var(--nms-warning);
}

.df-sev.muted {
  color: var(--nms-text-muted);
}

.defect-empty {
  font-size: 13px;
  color: var(--nms-text-muted);
  padding: 14px 0;
}

.resume-card {
  margin: 0 18px 18px;
  background: var(--nms-primary-soft);
  border-radius: 8px;
  padding: 12px 14px;
  cursor: pointer;
}

.resume-card:hover {
  background: #ddeafc;
}

.rc-label {
  font-size: 13px;
  color: var(--nms-primary);
}

.rc-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--nms-text);
  margin-top: 4px;
}

.rc-meta {
  font-size: 13px;
  color: var(--nms-text-secondary);
  margin-top: 2px;
}

/* 批次表 */
.batch-table {
  width: 100%;
}

.bt-head,
.bt-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 18px;
  height: 48px;
  border-bottom: 1px solid var(--nms-border-soft);
}

.bt-head {
  height: 40px;
  background: var(--nms-bg-elevated);
  font-size: 13px;
  color: var(--nms-text-secondary);
}

.bt-row:last-child {
  border-bottom: none;
}

.bt-row {
  font-size: 14px;
}

.c-status {
  width: 108px;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

.c-status em {
  font-style: normal;
  font-size: 13px;
  color: var(--nms-text-secondary);
}

.c-status em.ok {
  color: var(--nms-success);
}

.c-status em.info {
  color: var(--nms-primary);
}

.c-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}

.c-main b {
  font-weight: 600;
  flex: 0 0 auto;
}

.c-main em {
  font-style: normal;
  font-size: 13px;
  color: var(--nms-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bt-head .c-main {
  display: block;
}

.c-plan {
  width: 150px;
  flex: 0 0 auto;
  font-size: 13px;
  color: var(--nms-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.c-time {
  width: 150px;
  flex: 0 0 auto;
  font-size: 13px;
  color: var(--nms-text-secondary);
}

.c-rate {
  width: 70px;
  flex: 0 0 auto;
  font-size: 14px;
  font-weight: 600;
  color: var(--nms-text-secondary);
}

.c-rate.ok {
  color: var(--nms-success);
}

.c-rate.bad {
  color: var(--nms-danger);
}

.c-act {
  width: 96px;
  flex: 0 0 auto;
  text-align: right;
  display: flex;
  justify-content: flex-end;
  gap: 2px;
}

.c-act :deep(.el-button + .el-button) {
  margin-left: 0;
}

.mono {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 13px;
}

.panel-empty {
  padding: 28px 18px;
  font-size: 13px;
  color: var(--nms-text-muted);
}

@media (max-width: 1280px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .ov-row {
    flex-direction: column;
  }
  .ov-defect {
    width: auto;
  }
  .c-plan,
  .c-time {
    display: none;
  }
}
</style>
