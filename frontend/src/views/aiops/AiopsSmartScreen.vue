<template>
  <div class="smart-screen" v-loading="loading">
    <!-- 顶部标题栏 -->
    <header class="ss-header">
      <div class="ss-brand">
        <div class="ss-logo">AIOps</div>
        <div>
          <h1>智能运维数据大屏</h1>
          <div class="ss-sub">
            <span class="ss-live"><i class="dot"></i>实时数据</span>
            <span class="ss-clock">{{ clock }}</span>
            <span v-if="generatedAt" class="ss-generated">数据时间 {{ generatedAt }}</span>
          </div>
        </div>
      </div>
      <div class="ss-badges">
        <div class="ss-score" :class="scoreClass">
          <span class="ss-score-n">{{ summary.healthScore ?? '-' }}</span>
          <span class="ss-score-l">健康分 · {{ summary.healthGrade || '-' }}</span>
        </div>
        <el-tag :type="summary.llmOpsMode === 'unattended' ? 'warning' : 'success'" effect="dark" size="small">
          {{ summary.llmOpsMode === 'unattended' ? '无人值守' : '人工运维' }}
        </el-tag>
        <el-button size="small" @click="$router.push('/aiops/overview')">智能中心</el-button>
        <el-button size="small" @click="$router.push('/aiops/workbench')">事件处置</el-button>
        <el-button size="small" @click="askScreenBrief">助手解读</el-button>
        <el-button size="small" :loading="loading" @click="refresh(true)">刷新</el-button>
      </div>
    </header>

    <!-- 态势简报 -->
    <div class="ss-brief">
      <span class="ss-brief-tag">态势</span>
      <span class="ss-brief-text">{{ brief || '正在汇总设备、告警与处置数据…' }}</span>
    </div>

    <el-alert
      v-if="apiMissing"
      type="warning"
      :closable="false"
      show-icon
      title="数据看板接口不可用"
      description="当前后端未提供 /api/aiops/dashboard（多为旧进程未重启）。重启后端后本页会自动加载全部真实指标。"
      class="ss-alert"
    />

    <!-- KPI 指标卡 -->
    <section class="ss-kpis">
      <div class="kpi" v-for="k in kpis" :key="k.label" :class="k.tone">
        <div class="kpi-main">
          <span class="kpi-n">{{ k.value }}</span>
          <span v-if="k.unit" class="kpi-u">{{ k.unit }}</span>
        </div>
        <div class="kpi-label">{{ k.label }}</div>
        <div class="kpi-foot">{{ k.foot }}</div>
      </div>
    </section>

    <!-- 主体图表区 -->
    <section class="ss-grid">
      <div class="panel span6">
        <div class="panel-head">
          <h3>告警趋势（近 24 小时）</h3>
          <span class="panel-extra">合计 {{ trendTotal }} 条</span>
        </div>
        <div ref="trendRef" class="chart chart-lg"></div>
      </div>

      <div class="panel span3">
        <div class="panel-head">
          <h3>活动告警级别</h3>
          <span class="panel-extra">共 {{ summary.alarmActive ?? 0 }} 条</span>
        </div>
        <div ref="severityRef" class="chart chart-lg"></div>
      </div>

      <div class="panel span3">
        <div class="panel-head">
          <h3>设备在线状态</h3>
          <span class="panel-extra">{{ onlineRate }}% 在线</span>
        </div>
        <div ref="statusRef" class="chart chart-lg"></div>
      </div>

      <div class="panel span4">
        <div class="panel-head">
          <h3>资源占用 Top</h3>
          <span class="panel-extra">真实 SNMP 采样</span>
        </div>
        <div ref="capacityRef" class="chart"></div>
      </div>

      <div class="panel span4">
        <div class="panel-head">
          <h3>告警最多设备</h3>
          <span class="panel-extra">活动告警</span>
        </div>
        <div ref="topDeviceRef" class="chart"></div>
      </div>

      <div class="panel span4">
        <div class="panel-head">
          <h3>告警类型分布</h3>
          <span class="panel-extra">近 7 天</span>
        </div>
        <div ref="typeRef" class="chart"></div>
      </div>

      <div class="panel span6">
        <div class="panel-head">
          <h3>实时告警流</h3>
          <span class="panel-extra">最近 12 条</span>
        </div>
        <div class="ss-list">
          <div v-for="row in recentAlarms" :key="row.id" class="alarm-row">
            <span class="sev" :class="'sev-' + (row.severity || 'INFO')">{{ severityText(row.severity) }}</span>
            <span class="alarm-title">{{ row.title }}</span>
            <span class="alarm-dev">{{ row.deviceName || row.deviceIp || '-' }}</span>
            <span class="alarm-time">{{ shortTime(row.occurredAt) }}</span>
            <span class="alarm-status" :class="'st-' + (row.status || 'ACTIVE')">{{ statusText(row.status) }}</span>
          </div>
          <el-empty v-if="!recentAlarms.length" description="暂无告警" :image-size="48" />
        </div>
      </div>

      <div class="panel span6">
        <div class="panel-head">
          <h3>代表事件与可疑根因</h3>
          <span class="panel-extra">
            收敛 {{ correlation.representativeCount ?? 0 }} · 抑制 {{ correlation.suppressedCount ?? 0 }} · 风暴组 {{ correlation.stormGroups ?? 0 }}
          </span>
        </div>
        <div class="two-col">
          <div class="col">
            <div class="col-title">代表事件</div>
            <div class="ss-list">
              <div v-for="row in incidents" :key="row.id" class="incident-row">
                <span class="sev" :class="'sev-' + (row.severity || 'INFO')">{{ severityText(row.severity) }}</span>
                <div class="incident-body">
                  <div class="incident-title">{{ row.title }}</div>
                  <div class="incident-meta">{{ row.deviceName || '-' }} · 关联 {{ row.relatedCount ?? 1 }} 条</div>
                </div>
                <span class="phase" :class="'phase-' + (row.phase || 'pending')">{{ phaseText(row.phase) }}</span>
              </div>
              <el-empty v-if="!incidents.length" description="无代表事件" :image-size="42" />
            </div>
          </div>
          <div class="col">
            <div class="col-title">可疑根因 Top5</div>
            <div class="ss-list">
              <div v-for="c in rca" :key="c.deviceId" class="rca-row">
                <span class="rca-score" :class="scoreTone(c.score)">{{ c.score }}</span>
                <div class="rca-body">
                  <div class="rca-name">{{ c.name }}</div>
                  <div class="rca-reason">{{ c.reason }}</div>
                </div>
              </div>
              <el-empty v-if="!rca.length" description="暂无可疑根因" :image-size="42" />
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 处置流水 -->
    <footer class="ss-footer">
      <div class="panel-head">
        <h3>最近自动 / 人工处置</h3>
        <span class="panel-extra">
          24h 自动处置 {{ summary.autoHandled24h ?? 0 }} 次
          <template v-if="summary.autoSuccessRate != null"> · 成功率 {{ summary.autoSuccessRate }}%</template>
        </span>
      </div>
      <div class="actions">
        <div v-for="(a, i) in recentActions" :key="i" class="action-row">
          <span class="action-src" :class="a.source === 'unattended' ? 'auto' : 'manual'">
            {{ a.source === 'unattended' ? '自动' : '人工' }}
          </span>
          <span class="action-msg">{{ a.message }}</span>
          <span class="action-at">{{ shortTime(a.at) }}</span>
        </div>
        <span v-if="!recentActions.length" class="muted">暂无处置记录</span>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { aiopsApi } from '@/api/device'
import { askOpsAssistant } from '@/composables/askOpsAssistant'

const loading = ref(false)
const data = ref({})
const brief = ref('')
const clock = ref('')
const generatedAt = ref('')
const apiMissing = ref(false)

const trendRef = ref(null)
const severityRef = ref(null)
const statusRef = ref(null)
const capacityRef = ref(null)
const topDeviceRef = ref(null)
const typeRef = ref(null)

let charts = {}
let timer = null
let clockTimer = null
let resizeHandler = null

const summary = computed(() => data.value.summary || {})
const correlation = computed(() => data.value.correlation || {})
const incidents = computed(() => (Array.isArray(data.value.incidents) ? data.value.incidents : []))
const rca = computed(() => (Array.isArray(data.value.rcaCandidates) ? data.value.rcaCandidates : []))
const recentAlarms = computed(() => (Array.isArray(data.value.recentAlarms) ? data.value.recentAlarms : []))
const recentActions = computed(() => (Array.isArray(data.value.recentActions) ? data.value.recentActions : []))
const trendTotal = computed(() => {
  const v = data.value.alarmTrend?.values
  return Array.isArray(v) ? v.reduce((a, b) => a + Number(b || 0), 0) : 0
})
const onlineRate = computed(() => {
  const total = Number(summary.value.deviceTotal || 0)
  if (!total) return 0
  return Math.round((Number(summary.value.deviceOnline || 0) * 1000) / total) / 10
})

const kpis = computed(() => {
  const s = summary.value
  return [
    {
      label: '纳管设备',
      value: s.deviceTotal ?? 0,
      unit: '台',
      foot: `在线 ${s.deviceOnline ?? 0} · 离线 ${s.deviceOffline ?? 0}`,
      tone: 'tone-blue'
    },
    {
      label: '活动告警',
      value: s.alarmActive ?? 0,
      unit: '条',
      foot: `紧急 ${s.alarmCritical ?? 0} 条`,
      tone: (s.alarmActive ?? 0) > 0 ? 'tone-red' : 'tone-green'
    },
    {
      label: '今日新增告警',
      value: s.alarmToday ?? 0,
      unit: '条',
      foot: `近 7 天 ${s.alarmWeek ?? 0} 条`,
      tone: 'tone-orange'
    },
    {
      label: '健康分',
      value: s.healthScore ?? '-',
      unit: '',
      foot: `评级 ${s.healthGrade || '-'}`,
      tone: 'tone-cyan'
    },
    {
      label: '24h 自动处置',
      value: s.autoHandled24h ?? 0,
      unit: '次',
      foot: s.autoSuccessRate != null ? `成功率 ${s.autoSuccessRate}%` : '暂无处置',
      tone: 'tone-purple'
    },
    {
      label: '代表事件',
      value: correlation.value.representativeCount ?? 0,
      unit: '个',
      foot: `待处理 ${correlation.value.pendingCount ?? 0} · 处理中 ${correlation.value.inProgressCount ?? 0}`,
      tone: 'tone-slate'
    }
  ]
})

const scoreClass = computed(() => {
  const n = Number(summary.value.healthScore || 0)
  if (n >= 90) return 'good'
  if (n >= 75) return 'ok'
  return 'bad'
})

function severityText(s) {
  const map = { CRITICAL: '紧急', MAJOR: '重要', WARNING: '警告', MINOR: '次要', INFO: '提示' }
  return map[s] || s || '-'
}
function statusText(s) {
  const map = { ACTIVE: '活动', ACKNOWLEDGED: '处理中', CLEARED: '已清除' }
  return map[s] || s || '-'
}
function phaseText(p) {
  const map = { pending: '待处理', in_progress: '处理中', closed: '已闭环' }
  return map[p] || p || '-'
}
function scoreTone(score) {
  const n = Number(score || 0)
  if (n >= 80) return 'high'
  if (n >= 60) return 'mid'
  return 'low'
}
function shortTime(v) {
  if (!v) return ''
  const s = String(v).replace('T', ' ')
  return s.length >= 16 ? s.slice(5, 16) : s
}

function askScreenBrief() {
  askOpsAssistant({
    source: 'patrol',
    primaryToolLabel: '网络态势',
    recommendedTools: [
      { name: 'get_network_overview', label: '网络态势', needConfirm: false, args: {} },
      { name: 'inspect', label: '重算巡检', needConfirm: false, args: {} }
    ],
    autoAsk: true,
    autoAskQuestion: `大屏简报：${String(brief.value || '（空）').slice(0, 240)}。诊断值班优先项。`
  })
}

function tickClock() {
  clock.value = new Date().toLocaleString('zh-CN', { hour12: false })
}

function getChart(key, el) {
  if (!el) return null
  if (!charts[key] || charts[key].isDisposed?.()) {
    charts[key] = echarts.init(el)
  }
  return charts[key]
}

const axisLine = { lineStyle: { color: 'rgba(120,160,210,0.35)' } }
const axisLabel = { color: '#8fa3bf', fontSize: 11 }
const splitLine = { lineStyle: { color: 'rgba(120,160,210,0.12)' } }

function renderTrend() {
  const c = getChart('trend', trendRef.value)
  if (!c) return
  const trend = data.value.alarmTrend || {}
  const labels = Array.isArray(trend.labels) ? trend.labels : []
  const values = Array.isArray(trend.values) ? trend.values : []
  c.setOption({
    grid: { left: 42, right: 18, top: 24, bottom: 34 },
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(10,20,36,0.94)', borderColor: 'rgba(120,160,210,0.3)', textStyle: { color: '#e8eef7' } },
    xAxis: {
      type: 'category',
      data: labels,
      boundaryGap: false,
      axisLine,
      axisLabel: { ...axisLabel, interval: 2 },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel,
      axisLine: { show: false },
      splitLine
    },
    series: [
      {
        name: '告警数',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        showSymbol: false,
        lineStyle: { width: 2, color: '#409eff' },
        itemStyle: { color: '#79bbff' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(64,158,255,0.45)' },
            { offset: 1, color: 'rgba(64,158,255,0.02)' }
          ])
        },
        data: values
      }
    ]
  }, true)
}

function renderSeverity() {
  const c = getChart('severity', severityRef.value)
  if (!c) return
  const colors = {
    CRITICAL: '#f56c6c',
    MAJOR: '#e6a23c',
    WARNING: '#f7ba2a',
    MINOR: '#409eff',
    INFO: '#67c23a'
  }
  const dist = Array.isArray(data.value.severityDist) ? data.value.severityDist : []
  const items = dist
    .filter((d) => Number(d.value) > 0)
    .map((d) => ({ name: d.name, value: Number(d.value), itemStyle: { color: colors[d.key] || '#7f8fa6' } }))
  const total = items.reduce((a, b) => a + b.value, 0)
  const series = items.length
    ? items
    : [{ name: '无告警', value: 1, itemStyle: { color: 'rgba(120,160,210,0.18)' } }]
  c.setOption({
    tooltip: { show: items.length > 0, trigger: 'item', backgroundColor: 'rgba(10,20,36,0.94)', borderColor: 'rgba(120,160,210,0.3)', textStyle: { color: '#e8eef7' } },
    legend: {
      orient: 'vertical',
      right: 6,
      top: 'center',
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { color: '#9fb3cd', fontSize: 11 },
      formatter: (name) => name
    },
    graphic: [
      {
        type: 'text',
        left: '30%',
        top: '44%',
        style: {
          text: String(total),
          fill: '#ffffff',
          fontSize: 26,
          fontWeight: 700,
          textAlign: 'center'
        }
      },
      {
        type: 'text',
        left: '30%',
        top: '60%',
        style: { text: '活动告警', fill: '#8fa3bf', fontSize: 11, textAlign: 'center' }
      }
    ],
    series: [
      {
        type: 'pie',
        radius: ['52%', '74%'],
        center: ['34%', '52%'],
        avoidLabelOverlap: true,
        label: { show: false },
        labelLine: { show: false },
        data: series
      }
    ]
  }, true)
}

function renderStatus() {
  const c = getChart('status', statusRef.value)
  if (!c) return
  const dist = Array.isArray(data.value.statusDist) ? data.value.statusDist : []
  const colorMap = { 在线: '#67c23a', 离线: '#f56c6c', 未知: '#909399' }
  const items = dist
    .filter((d) => Number(d.value) > 0)
    .map((d) => ({ name: d.name, value: Number(d.value), itemStyle: { color: colorMap[d.name] || '#7f8fa6' } }))
  const total = items.reduce((a, b) => a + b.value, 0)
  c.setOption({
    tooltip: { trigger: 'item', backgroundColor: 'rgba(10,20,36,0.94)', borderColor: 'rgba(120,160,210,0.3)', textStyle: { color: '#e8eef7' } },
    legend: {
      orient: 'vertical',
      right: 6,
      top: 'center',
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { color: '#9fb3cd', fontSize: 11 }
    },
    graphic: [
      {
        type: 'text',
        left: '30%',
        top: '44%',
        style: { text: String(total), fill: '#ffffff', fontSize: 26, fontWeight: 700, textAlign: 'center' }
      },
      {
        type: 'text',
        left: '30%',
        top: '60%',
        style: { text: '设备总数', fill: '#8fa3bf', fontSize: 11, textAlign: 'center' }
      }
    ],
    series: [
      {
        type: 'pie',
        radius: ['52%', '74%'],
        center: ['34%', '52%'],
        label: { show: false },
        labelLine: { show: false },
        data: items.length ? items : [{ name: '暂无设备', value: 1, itemStyle: { color: 'rgba(120,160,210,0.18)' } }]
      }
    ]
  }, true)
}

function renderCapacity() {
  const c = getChart('capacity', capacityRef.value)
  if (!c) return
  const rows = Array.isArray(data.value.capacityTop) ? data.value.capacityTop : []
  const names = rows.map((r) => r.name)
  c.setOption({
    grid: { left: 8, right: 18, top: 30, bottom: 6, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(10,20,36,0.94)',
      borderColor: 'rgba(120,160,210,0.3)',
      textStyle: { color: '#e8eef7' },
      valueFormatter: (v) => (v == null ? '-' : `${v}%`)
    },
    legend: {
      top: 0,
      right: 0,
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { color: '#9fb3cd', fontSize: 11 }
    },
    xAxis: { type: 'category', data: names, axisLine, axisLabel: { ...axisLabel, interval: 0, rotate: names.length > 4 ? 20 : 0 }, axisTick: { show: false } },
    yAxis: { type: 'value', max: 100, axisLabel: { ...axisLabel, formatter: '{value}%' }, splitLine, axisLine: { show: false } },
    series: [
      {
        name: 'CPU',
        type: 'bar',
        barMaxWidth: 16,
        itemStyle: { color: '#409eff', borderRadius: [3, 3, 0, 0] },
        data: rows.map((r) => r.cpu)
      },
      {
        name: '内存',
        type: 'bar',
        barMaxWidth: 16,
        itemStyle: { color: '#36cfc9', borderRadius: [3, 3, 0, 0] },
        data: rows.map((r) => r.mem)
      }
    ]
  }, true)
}

function renderTopDevice() {
  const c = getChart('topDevice', topDeviceRef.value)
  if (!c) return
  const rows = Array.isArray(data.value.topAlarmDevices) ? data.value.topAlarmDevices : []
  const sorted = [...rows].sort((a, b) => Number(a.value) - Number(b.value))
  c.setOption({
    grid: { left: 8, right: 34, top: 12, bottom: 6, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, backgroundColor: 'rgba(10,20,36,0.94)', borderColor: 'rgba(120,160,210,0.3)', textStyle: { color: '#e8eef7' } },
    xAxis: { type: 'value', minInterval: 1, axisLabel, splitLine, axisLine: { show: false } },
    yAxis: { type: 'category', data: sorted.map((r) => r.name), axisLine, axisLabel, axisTick: { show: false } },
    series: [
      {
        name: '活动告警',
        type: 'bar',
        barMaxWidth: 14,
        label: { show: true, position: 'right', color: '#c5d4e8', fontSize: 11 },
        itemStyle: {
          borderRadius: [0, 3, 3, 0],
          color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
            { offset: 0, color: 'rgba(245,108,108,0.45)' },
            { offset: 1, color: '#f56c6c' }
          ])
        },
        data: sorted.map((r) => Number(r.value))
      }
    ]
  }, true)
}

function renderType() {
  const c = getChart('type', typeRef.value)
  if (!c) return
  const rows = Array.isArray(data.value.alarmTypes) ? data.value.alarmTypes : []
  const sorted = [...rows].sort((a, b) => Number(b.value) - Number(a.value))
  c.setOption({
    grid: { left: 8, right: 18, top: 12, bottom: 6, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, backgroundColor: 'rgba(10,20,36,0.94)', borderColor: 'rgba(120,160,210,0.3)', textStyle: { color: '#e8eef7' } },
    xAxis: { type: 'value', minInterval: 1, axisLabel, splitLine, axisLine: { show: false } },
    yAxis: { type: 'category', data: sorted.map((r) => r.name), axisLine, axisLabel: { ...axisLabel, width: 90, overflow: 'truncate' }, axisTick: { show: false } },
    series: [
      {
        type: 'bar',
        barMaxWidth: 13,
        label: { show: true, position: 'right', color: '#c5d4e8', fontSize: 11 },
        itemStyle: {
          borderRadius: [0, 3, 3, 0],
          color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
            { offset: 0, color: 'rgba(230,162,60,0.4)' },
            { offset: 1, color: '#e6a23c' }
          ])
        },
        data: sorted.map((r) => Number(r.value))
      }
    ]
  }, true)
}

function renderCharts() {
  renderTrend()
  renderSeverity()
  renderStatus()
  renderCapacity()
  renderTopDevice()
  renderType()
}

async function refresh(force = false) {
  loading.value = true
  try {
    const [dash, screen] = await Promise.all([
      aiopsApi.getDashboard(force).catch((e) => {
        if (e?.response?.status === 404 || e?.response?.status === 405) apiMissing.value = true
        return null
      }),
      aiopsApi.getScreenBrief().catch(() => null)
    ])
    data.value = dash || {}
    brief.value = screen?.brief || ''
    generatedAt.value = dash?.generatedAt ? String(dash.generatedAt).replace('T', ' ').slice(0, 19) : ''
    if (!dash) {
      // 旧后端没有 /dashboard：至少把简报里的健康分与事件数呈现出来
      data.value = {
        summary: {
          healthScore: screen?.health?.networkScore,
          alarmActive: screen?.incidentTotal,
          llmOpsMode: screen?.llmOpsMode
        },
        correlation: screen?.correlation || {}
      }
    }
    await nextTick()
    renderCharts()
  } catch (e) {
    data.value = {}
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  tickClock()
  clockTimer = setInterval(tickClock, 1000)
  refresh()
  timer = setInterval(() => refresh(false), 30000)
  resizeHandler = () => Object.values(charts).forEach((c) => c && !c.isDisposed?.() && c.resize())
  window.addEventListener('resize', resizeHandler)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  if (clockTimer) clearInterval(clockTimer)
  if (resizeHandler) window.removeEventListener('resize', resizeHandler)
  Object.values(charts).forEach((c) => c && !c.isDisposed?.() && c.dispose())
  charts = {}
})
</script>

<style scoped>
.smart-screen {
  min-height: calc(100vh - 56px);
  padding: 14px 18px 20px;
  background:
    radial-gradient(1200px 600px at 12% -10%, rgba(64, 158, 255, 0.16), transparent 60%),
    radial-gradient(900px 500px at 92% 0%, rgba(54, 207, 201, 0.12), transparent 55%),
    linear-gradient(160deg, #0a1120 0%, #101d33 48%, #0b1526 100%);
  color: #e8eef7;
  font-variant-numeric: tabular-nums;
}

/* 顶部 */
.ss-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.ss-brand { display: flex; align-items: center; gap: 12px; }
.ss-logo {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: #06121f;
  background: linear-gradient(135deg, #79bbff, #36cfc9);
  box-shadow: 0 0 20px rgba(64, 158, 255, 0.35);
}
.ss-brand h1 {
  margin: 0;
  font-size: 21px;
  font-weight: 700;
  letter-spacing: 0.06em;
}
.ss-sub {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 3px;
  font-size: 12px;
  color: #8fa3bf;
}
.ss-live { display: inline-flex; align-items: center; gap: 5px; color: #67c23a; }
.ss-live .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #67c23a;
  box-shadow: 0 0 8px #67c23a;
  animation: pulse 1.6s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}
.ss-badges { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ss-score {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 4px 14px;
  border-radius: 9px;
  background: rgba(64, 158, 255, 0.1);
  border: 1px solid rgba(64, 158, 255, 0.32);
}
.ss-score.good { border-color: rgba(103, 194, 58, 0.45); background: rgba(103, 194, 58, 0.1); }
.ss-score.ok { border-color: rgba(230, 162, 60, 0.45); background: rgba(230, 162, 60, 0.1); }
.ss-score.bad { border-color: rgba(245, 108, 108, 0.45); background: rgba(245, 108, 108, 0.1); }
.ss-score-n { font-size: 22px; font-weight: 700; color: #79bbff; line-height: 1.1; }
.ss-score.good .ss-score-n { color: #85ce61; }
.ss-score.ok .ss-score-n { color: #e6a23c; }
.ss-score.bad .ss-score-n { color: #f89898; }
.ss-score-l { font-size: 10px; color: #8fa3bf; }

/* 简报 */
.ss-brief {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 12px;
  padding: 9px 14px;
  border-radius: 8px;
  background: linear-gradient(90deg, rgba(64, 158, 255, 0.12), rgba(255, 255, 255, 0.02));
  border: 1px solid rgba(120, 160, 210, 0.16);
  font-size: 13px;
  line-height: 1.5;
  color: #c5d4e8;
}
.ss-brief-tag {
  flex-shrink: 0;
  padding: 1px 8px;
  border-radius: 4px;
  font-size: 11px;
  color: #06121f;
  background: #79bbff;
  font-weight: 600;
}
.ss-brief-text { flex: 1; min-width: 0; }
.ss-alert { margin-bottom: 12px; }

/* KPI */
.ss-kpis {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}
@media (max-width: 1400px) {
  .ss-kpis { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
.kpi {
  position: relative;
  padding: 12px 14px;
  border-radius: 10px;
  background: rgba(15, 28, 48, 0.86);
  border: 1px solid rgba(120, 160, 210, 0.16);
  overflow: hidden;
}
.kpi::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: #409eff;
}
.kpi.tone-red::before { background: #f56c6c; }
.kpi.tone-green::before { background: #67c23a; }
.kpi.tone-orange::before { background: #e6a23c; }
.kpi.tone-cyan::before { background: #36cfc9; }
.kpi.tone-purple::before { background: #b37feb; }
.kpi.tone-slate::before { background: #7f8fa6; }
.kpi-main { display: flex; align-items: baseline; gap: 4px; }
.kpi-n {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.1;
  color: #ffffff;
  letter-spacing: 0.01em;
}
.kpi-u { font-size: 12px; color: #8fa3bf; }
.kpi-label { margin-top: 3px; font-size: 12px; color: #c5d4e8; }
.kpi-foot { margin-top: 4px; font-size: 11px; color: #6b7f99; }

/* 网格 */
.ss-grid {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 10px;
}
.panel {
  background: rgba(15, 28, 48, 0.86);
  border: 1px solid rgba(120, 160, 210, 0.16);
  border-radius: 10px;
  padding: 10px 12px 8px;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.span3 { grid-column: span 3; }
.span4 { grid-column: span 4; }
.span6 { grid-column: span 6; }
@media (max-width: 1280px) {
  .span3, .span4 { grid-column: span 6; }
  .span6 { grid-column: span 12; }
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.panel-head h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: #9ec5ff;
  letter-spacing: 0.02em;
}
.panel-extra { font-size: 11px; color: #6b7f99; }
.chart { height: 208px; width: 100%; }
.chart-lg { height: 226px; }

/* 列表 */
.ss-list {
  flex: 1;
  max-height: 226px;
  overflow: auto;
  padding-right: 2px;
}
.alarm-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 0;
  border-bottom: 1px solid rgba(120, 160, 210, 0.1);
  font-size: 12px;
}
.alarm-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #dbe6f5;
}
.alarm-dev { width: 96px; color: #8fa3bf; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.alarm-time { width: 82px; text-align: right; color: #6b7f99; }
.alarm-status { width: 46px; text-align: right; font-size: 11px; }
.st-ACTIVE { color: #f89898; }
.st-ACKNOWLEDGED { color: #e6a23c; }
.st-CLEARED { color: #67c23a; }
.sev {
  flex-shrink: 0;
  width: 38px;
  text-align: center;
  padding: 1px 0;
  border-radius: 3px;
  font-size: 11px;
  color: #0a1120;
  font-weight: 600;
}
.sev-CRITICAL { background: #f56c6c; color: #fff; }
.sev-MAJOR { background: #e6a23c; color: #fff; }
.sev-WARNING { background: #f7ba2a; }
.sev-MINOR { background: #409eff; color: #fff; }
.sev-INFO { background: #67c23a; color: #fff; }

.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
@media (max-width: 1100px) { .two-col { grid-template-columns: 1fr; } }
.col { min-width: 0; }
.col-title { font-size: 11px; color: #6b7f99; margin-bottom: 4px; letter-spacing: 0.04em; }
.incident-row, .rca-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 0;
  border-bottom: 1px solid rgba(120, 160, 210, 0.1);
}
.incident-body, .rca-body { flex: 1; min-width: 0; }
.incident-title, .rca-name {
  font-size: 12px;
  color: #dbe6f5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.incident-meta, .rca-reason {
  font-size: 11px;
  color: #6b7f99;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.phase { font-size: 11px; flex-shrink: 0; }
.phase-pending { color: #f89898; }
.phase-in_progress { color: #e6a23c; }
.phase-closed { color: #67c23a; }
.rca-score {
  flex-shrink: 0;
  width: 34px;
  height: 30px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 12px;
}
.rca-score.high { background: rgba(245, 108, 108, 0.18); color: #f89898; }
.rca-score.mid { background: rgba(230, 162, 60, 0.18); color: #e6a23c; }
.rca-score.low { background: rgba(120, 160, 210, 0.14); color: #9fb3cd; }

/* 处置流水 */
.ss-footer {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(15, 28, 48, 0.86);
  border: 1px solid rgba(120, 160, 210, 0.16);
}
.actions { display: flex; flex-direction: column; gap: 5px; max-height: 128px; overflow: auto; }
.action-row { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #c5d4e8; }
.action-src { padding: 1px 7px; border-radius: 3px; font-size: 11px; }
.action-src.auto { background: rgba(230, 162, 60, 0.16); color: #e6a23c; }
.action-src.manual { background: rgba(64, 158, 255, 0.16); color: #79bbff; }
.action-msg { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.action-at { color: #6b7f99; }
.muted { font-size: 12px; color: #6b7f99; }
</style>
