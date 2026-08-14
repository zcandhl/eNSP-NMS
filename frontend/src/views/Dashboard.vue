<template>
  <div class="nms-page dashboard" v-loading="pageLoading">
    <div class="nms-page-header">
      <div class="nms-page-title-block">
        <h1 class="nms-page-title">运维概览</h1>
        <p class="nms-page-subtitle">
          欢迎回来，{{ auth.displayName }}
          <el-tag v-if="auth.roles[0]" size="small" type="info" effect="plain" style="margin-left: 8px;">{{ roleLabel }}</el-tag>
        </p>
      </div>
      <div class="nms-page-actions">
        <span class="dash-time">{{ nowText }}</span>
        <el-button size="small" :loading="pageLoading" @click="refreshAll">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <el-row :gutter="16" class="kpi-row">
      <el-col v-if="auth.hasPermission('devices:read')" :xs="12" :sm="8" :md="6">
        <el-card class="kpi-card clickable" shadow="hover" @click="goTo('/devices')">
          <div class="kpi-body">
            <div class="kpi-icon device"><el-icon><Box /></el-icon></div>
            <div class="kpi-info">
              <div class="kpi-value">{{ deviceCount }}</div>
              <div class="kpi-label">设备总数</div>
              <div class="kpi-sub">在线 {{ onlineCount }} · 离线 {{ offlineCount }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col v-if="auth.hasPermission('alarms:read')" :xs="12" :sm="8" :md="6">
        <el-card class="kpi-card clickable" shadow="hover" @click="goTo('/alarms')">
          <div class="kpi-body">
            <div class="kpi-icon alarm"><el-icon><Warning /></el-icon></div>
            <div class="kpi-info">
              <div class="kpi-value danger">{{ alarmStats.activeCount || 0 }}</div>
              <div class="kpi-label">待处理告警</div>
              <div class="kpi-sub">
                严重 {{ alarmStats.criticalActiveCount || 0 }}
                · 超期 {{ alarmStats.overdueActiveCount || 0 }}
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col v-if="auth.hasAnyPermission('aiops:read', 'alarms:read')" :xs="12" :sm="8" :md="6">
        <el-card class="kpi-card clickable" shadow="hover" @click="goTo('/aiops/overview')">
          <div class="kpi-body">
            <div class="kpi-icon ack"><el-icon><DataAnalysis /></el-icon></div>
            <div class="kpi-info">
              <div class="kpi-value" :class="healthScoreClass">{{ healthScore ?? '-' }}</div>
              <div class="kpi-label">全网健康分</div>
              <div class="kpi-sub">风险设备 {{ riskDeviceCount }} · 智能运维</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col v-if="auth.hasPermission('alarms:read')" :xs="12" :sm="8" :md="6">
        <el-card class="kpi-card clickable" shadow="hover" @click="goTo('/alarms')">
          <div class="kpi-body">
            <div class="kpi-icon ack"><el-icon><Bell /></el-icon></div>
            <div class="kpi-info">
              <div class="kpi-value">{{ alarmStats.acknowledgedCount || 0 }}</div>
              <div class="kpi-label">处理中</div>
              <div class="kpi-sub">今日新增 {{ alarmStats.todayCount || 0 }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col v-if="auth.hasPermission('configs:read')" :xs="12" :sm="8" :md="6">
        <el-card class="kpi-card clickable" shadow="hover" @click="goTo('/configs')">
          <div class="kpi-body">
            <div class="kpi-icon config"><el-icon><DocumentCopy /></el-icon></div>
            <div class="kpi-info">
              <div class="kpi-value warning">{{ configHealth.neverBackedUp || 0 }}</div>
              <div class="kpi-label">未备份设备</div>
              <div class="kpi-sub">
                超期 {{ configHealth.staleOverDays || 0 }}
                · 计划失败 {{ configHealth.scheduleFailed || 0 }}
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col v-if="auth.hasPermission('topology:read')" :xs="12" :sm="8" :md="6">
        <el-card class="kpi-card clickable" shadow="hover" @click="goTo('/topology')">
          <div class="kpi-body">
            <div class="kpi-icon topo"><el-icon><Connection /></el-icon></div>
            <div class="kpi-info">
              <div class="kpi-value">{{ linkCount }}</div>
              <div class="kpi-label">拓扑链路</div>
              <div class="kpi-sub">节点 {{ nodeCount }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card
      v-if="auth.hasAnyPermission('aiops:read', 'alarms:read') && aiopsStory.headline"
      shadow="never"
      class="aiops-strip"
      :class="{ risk: aiopsStory.hasRisk }"
    >
      <div class="aiops-strip-main">
        <div>
          <div class="aiops-strip-label">
            智能运维
            <el-tag size="small" :type="aiopsOpsMode === 'unattended' ? 'warning' : 'success'" effect="plain">
              {{ aiopsOpsMode === 'unattended' ? '无人值守' : '人工运维' }}
            </el-tag>
          </div>
          <div class="aiops-strip-headline">{{ aiopsStory.headline }}</div>
          <div class="aiops-strip-detail">{{ aiopsStory.detail }}</div>
        </div>
        <div class="aiops-strip-actions">
          <el-button type="primary" @click="goTo('/aiops/overview')">智能中心</el-button>
          <el-button @click="goTo('/aiops/screen')">智能大屏</el-button>
          <el-button :loading="inspectingDash" @click="runDashInspect">一键巡检</el-button>
          <el-button
            v-if="auth.hasAnyPermission('aiops:read', 'alarms:read')"
            @click="askDashAnalyze"
          >解读态势</el-button>
        </div>
      </div>
      <div v-if="aiopsChips.length" class="aiops-strip-chips">
        <span v-for="c in aiopsChips" :key="c.label" class="aiops-chip">
          <em>{{ c.value }}</em>{{ c.label }}
        </span>
      </div>
      <div v-if="aiopsInsights.length" class="aiops-strip-tags">
        <el-tag
          v-for="ins in aiopsInsights.slice(0, 4)"
          :key="ins.code + ins.title"
          size="small"
          :type="insightTagType(ins.level)"
          effect="plain"
          class="aiops-tag"
          @click="goTo(ins.link || '/aiops/overview')"
        >{{ ins.title }}</el-tag>
      </div>
    </el-card>

    <el-row v-if="auth.hasPermission('alarms:read')" :gutter="16" class="section-row">
      <el-col :xs="24" :md="14">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>近 24 小时告警趋势</span>
              <el-text type="info" size="small">本周 {{ alarmStats.weekCount || 0 }} · 本月 {{ alarmStats.monthCount || 0 }}</el-text>
            </div>
          </template>
          <div ref="trendChartRef" class="trend-chart" />
        </el-card>
      </el-col>
      <el-col :xs="24" :md="10">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>最新待处理告警</span>
              <el-button link type="primary" @click="goTo('/alarms')">查看全部</el-button>
            </div>
          </template>
          <el-table :data="recentAlarms" size="small" stripe empty-text="暂无待处理告警">
            <el-table-column prop="severity" label="级别" width="72">
              <template #default="{ row }">
                <el-tag :type="severityType(row.severity)" size="small">{{ severityText(row.severity) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip />
            <el-table-column prop="deviceName" label="设备" width="100" show-overflow-tooltip />
            <el-table-column prop="occurredAt" label="时间" width="140">
              <template #default="{ row }">{{ formatTime(row.occurredAt) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="section-row">
      <el-col v-if="auth.hasPermission('configs:read')" :xs="24" :md="12">
        <el-card shadow="never">
          <template #header>
            <div class="card-header">
              <span>配置备份健康</span>
              <el-button link type="primary" @click="goTo('/configs')">配置管理</el-button>
            </div>
          </template>
          <div class="health-grid">
            <div class="health-item" @click="goTo('/configs')">
              <span class="health-num">{{ configHealth.deviceTotal || 0 }}</span>
              <span class="health-label">纳管设备</span>
            </div>
            <div class="health-item warn" @click="goTo('/configs')">
              <span class="health-num">{{ configHealth.neverBackedUp || 0 }}</span>
              <span class="health-label">从未备份</span>
            </div>
            <div class="health-item warn" @click="goTo('/configs')">
              <span class="health-num">{{ configHealth.staleOverDays || 0 }}</span>
              <span class="health-label">超期≥{{ configHealth.staleDays || 7 }}天</span>
            </div>
            <div class="health-item danger" @click="goTo('/configs')">
              <span class="health-num">{{ configHealth.scheduleFailed || 0 }}</span>
              <span class="health-label">计划失败</span>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="auth.hasPermission('configs:read') ? 12 : 24">
        <el-card shadow="never">
          <template #header>
            <span>快速操作</span>
          </template>
          <div class="quick-actions">
            <el-button v-permission="'devices:read'" type="primary" @click="goTo('/devices')">
              <el-icon><Box /></el-icon> 设备管理
            </el-button>
            <el-button v-permission="'alarms:read'" type="warning" @click="goTo('/alarms')">
              <el-icon><Warning /></el-icon> 告警管理
            </el-button>
            <el-button v-permission="'configs:read'" @click="goTo('/configs')">
              <el-icon><DocumentCopy /></el-icon> 配置管理
            </el-button>
            <el-button v-permission="'performance:read'" type="success" @click="goTo('/performance')">
              <el-icon><DataAnalysis /></el-icon> 性能监控
            </el-button>
            <el-button v-permission="'topology:read'" @click="goTo('/topology')">
              <el-icon><Connection /></el-icon> 拓扑视图
            </el-button>
            <el-button v-permission="'devices:discover'" @click="showDiscoverDialog = true">
              <el-icon><Search /></el-icon> 发现设备
            </el-button>
            <el-button
              v-if="auth.hasAnyPermission('audit:read', 'configs:read')"
              @click="goTo('/audit')"
            >
              <el-icon><Document /></el-icon> 日志中心
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>

  <el-dialog v-model="showDiscoverDialog" title="发现设备" width="500px">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
      title="将自动入库新发现设备。如需预览勾选，请到「设备管理」使用发现功能。"
    />
    <el-form :model="discoverForm" label-width="100px">
      <el-form-item label="网段">
        <el-input v-model="discoverForm.network" placeholder="例如: 192.168.56.0" />
      </el-form-item>
      <el-form-item label="Community">
        <el-input v-model="discoverForm.community" placeholder="默认 public" />
      </el-form-item>
      <el-form-item label="超时(秒)">
        <el-input-number v-model="discoverForm.timeout" :min="5" :max="120" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="showDiscoverDialog = false">取消</el-button>
      <el-button type="primary" :loading="discovering" @click="startDiscovery">开始发现</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import {
  Box, Warning, Connection, DataAnalysis, Search,
  DocumentCopy, Refresh, Bell, Document
} from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { deviceApi, alarmApi, configApi, aiopsApi } from '@/api/device'
import { topologyApi } from '@/api/topology'
import { ElMessage } from 'element-plus'
import { useDeviceStore } from '@/stores/device'
import { useAuthStore } from '@/stores/auth'
import { useThemeSync } from '@/composables/useThemeColors'
import { askOpsAssistant } from '@/composables/askOpsAssistant'

const router = useRouter()
const deviceStore = useDeviceStore()
const auth = useAuthStore()

const ROLE_LABELS = {
  ADMIN: '系统管理员',
  OPERATOR: '运维操作员',
  VIEWER: '监控只读',
  ALARM_DUTY: '告警值班员',
  CONFIG_ADMIN: '配置管理员'
}

const pageLoading = ref(false)
const nowText = ref('')
const linkCount = ref(0)
const nodeCount = ref(0)
const alarmStats = ref({})
const recentAlarms = ref([])
const healthScore = ref(null)
const riskDeviceCount = ref(0)
const aiopsStory = ref({})
const aiopsInsights = ref([])
const aiopsOpsMode = ref('manual')
const aiopsMeta = ref({})
const inspectingDash = ref(false)
const aiopsChips = computed(() => {
  const m = aiopsMeta.value || {}
  const corr = m.correlation || {}
  const ev = m.lastInspectEvidence || {}
  const rcaLen = Array.isArray(m.rca?.candidates) ? m.rca.candidates.length
    : (Array.isArray(m.rcaCandidates) ? m.rcaCandidates.length : 0)
  return [
    { label: '代表事件', value: m.incidentTotal ?? corr.representativeCount ?? '-' },
    { label: '风暴组', value: corr.stormGroups ?? ev.stormGroups ?? 0 },
    { label: '基线异常', value: Array.isArray(m.recentAnomalies) ? m.recentAnomalies.length : (ev.baselineAnomalies ?? 0) },
    { label: '根因候选', value: rcaLen }
  ]
})
const insightTagType = (level) => ({
  success: 'success',
  warning: 'warning',
  danger: 'danger',
  info: 'info'
}[level] || 'info')
const healthScoreClass = computed(() => {
  const s = healthScore.value
  if (s == null) return ''
  if (s >= 85) return ''
  if (s >= 70) return 'warning'
  return 'danger'
})
const configHealth = ref({
  deviceTotal: 0,
  neverBackedUp: 0,
  staleOverDays: 0,
  scheduleFailed: 0,
  staleDays: 7
})
const showDiscoverDialog = ref(false)
const discovering = ref(false)
const discoverForm = ref({
  network: '192.168.56.0',
  timeout: 30,
  community: 'public'
})

const trendChartRef = ref(null)
let trendChart = null
let clockTimer = null

const deviceCount = computed(() => deviceStore.totalCount)
const onlineCount = computed(() => deviceStore.onlineCount)
const offlineCount = computed(() => Math.max(deviceCount.value - onlineCount.value, 0))
const roleLabel = computed(() => ROLE_LABELS[auth.roles[0]] || auth.roles[0] || '')

function updateClock() {
  nowText.value = new Date().toLocaleString('zh-CN', { hour12: false })
}

function formatTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 16)
}

function severityType(s) {
  const map = { CRITICAL: 'danger', MAJOR: 'warning', WARNING: 'warning', MINOR: 'info', INFO: '' }
  return map[s] || 'info'
}

function severityText(s) {
  const map = { CRITICAL: '严重', MAJOR: '重要', WARNING: '警告', MINOR: '次要', INFO: '提示' }
  return map[s] || s || '-'
}

function goTo(path) {
  router.push(path)
}

async function loadDevices() {
  if (!auth.hasPermission('devices:read')) return
  await deviceStore.fetchDevices(true)
}

async function loadAlarmData() {
  if (!auth.hasPermission('alarms:read')) return
  const [stats, alarms] = await Promise.all([
    alarmApi.getAlarmStats(),
    alarmApi.queryAlarms({ status: ['ACTIVE'], page: 0, size: 8, sort: 'occurredAt,desc' })
  ])
  alarmStats.value = stats || {}
  recentAlarms.value = Array.isArray(alarms?.content) ? alarms.content : []
  await nextTick()
  renderTrendChart()
}

async function loadConfigHealth() {
  if (!auth.hasPermission('configs:read')) return
  const res = await configApi.getBackupHealth()
  configHealth.value = res || configHealth.value
}

async function loadTopologyStats() {
  if (!auth.hasPermission('topology:read')) return
  try {
    const [links, topo] = await Promise.all([
      topologyApi.getLinks(),
      topologyApi.getFullTopology()
    ])
    linkCount.value = Array.isArray(links) ? links.length : 0
    nodeCount.value = Array.isArray(topo?.nodes) ? topo.nodes.length : 0
  } catch {
    linkCount.value = 0
    nodeCount.value = 0
  }
}

function renderTrendChart() {
  if (!trendChartRef.value || !auth.hasPermission('alarms:read')) return
  if (!trendChart) {
    trendChart = echarts.init(trendChartRef.value)
  }
  const trend = Array.isArray(alarmStats.value.hourlyTrend) ? alarmStats.value.hourlyTrend : []
  const hours = trend.map(t => t.hour)
  const counts = trend.map(t => Number(t.count) || 0)
  trendChart.setOption({
    grid: { left: 40, right: 16, top: 16, bottom: 28 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: hours,
      boundaryGap: false,
      axisLabel: { fontSize: 11, color: '#909399' }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { type: 'dashed', color: '#ebeef5' } },
      axisLabel: { fontSize: 11, color: '#909399' }
    },
    series: [{
      name: '告警数',
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 4,
      data: counts,
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(245, 108, 108, 0.25)' },
          { offset: 1, color: 'rgba(245, 108, 108, 0.02)' }
        ])
      },
      lineStyle: { color: '#f56c6c', width: 2 },
      itemStyle: { color: '#f56c6c' }
    }]
  })
}

function handleResize() {
  trendChart?.resize()
}

useThemeSync(() => {
  renderTrendChart()
})

async function loadHealthScore() {
  if (!auth.hasAnyPermission('aiops:read', 'alarms:read')) return
  try {
    const ov = await aiopsApi.getOverview()
    const h = ov?.health || {}
    const score = h?.networkScore
    healthScore.value = score == null || score === '' ? null : Number(score)
    riskDeviceCount.value = Array.isArray(h?.riskDevices) ? h.riskDevices.length : 0
    aiopsStory.value = ov?.story || {}
    aiopsInsights.value = Array.isArray(ov?.insights) ? ov.insights : []
    aiopsOpsMode.value = ov?.llmOpsMode === 'unattended' ? 'unattended' : 'manual'
    aiopsMeta.value = {
      incidentTotal: ov?.incidentTotal,
      correlation: ov?.correlation || {},
      lastInspectEvidence: ov?.lastInspectEvidence || {},
      recentAnomalies: ov?.recentAnomalies || [],
      rca: ov?.rca || {}
    }
  } catch {
    try {
      const h = await aiopsApi.getHealth()
      const score = h?.networkScore
      healthScore.value = score == null || score === '' ? null : Number(score)
      riskDeviceCount.value = Array.isArray(h?.riskDevices) ? h.riskDevices.length : 0
    } catch {
      healthScore.value = null
      riskDeviceCount.value = 0
    }
    aiopsStory.value = {}
    aiopsInsights.value = []
    aiopsOpsMode.value = 'manual'
    aiopsMeta.value = {}
  }
}

async function runDashInspect() {
  inspectingDash.value = true
  try {
    const res = await aiopsApi.inspect()
    const { saveInspectReport } = await import('@/composables/inspectReportStore')
    saveInspectReport(res)
    ElMessage.success({
      message: res.summary || '智能巡检完成，报告已写入智能运维中心',
      duration: 4000
    })
    await loadHealthScore()
  } catch {
    ElMessage.error('智能巡检失败')
  } finally {
    inspectingDash.value = false
  }
}

function askDashAnalyze() {
  const headline = aiopsStory.value?.headline || ''
  const detail = aiopsStory.value?.detail || ''
  askOpsAssistant({
    source: 'dashboard',
    scenario: 'GENERIC',
    scenarioLabel: '态势',
    primaryToolLabel: '网络态势',
    recommendedTools: [
      { name: 'get_network_overview', label: '网络态势', needConfirm: false, args: {} },
      { name: 'inspect', label: '智能巡检重算', needConfirm: false, args: {} }
    ],
    autoAsk: true,
    autoAskQuestion: `诊断全网态势：${headline || '无头条'}。${String(detail || '').slice(0, 120)}`
  })
}

async function refreshAll() {
  pageLoading.value = true
  try {
    const tasks = [loadDevices()]
    if (auth.hasPermission('alarms:read')) tasks.push(loadAlarmData())
    if (auth.hasAnyPermission('aiops:read', 'alarms:read')) tasks.push(loadHealthScore())
    if (auth.hasPermission('configs:read')) tasks.push(loadConfigHealth())
    if (auth.hasPermission('topology:read')) tasks.push(loadTopologyStats())
    await Promise.allSettled(tasks)
  } finally {
    pageLoading.value = false
  }
}

async function startDiscovery() {
  discovering.value = true
  try {
    const devices = await deviceApi.discoverDevices(
      discoverForm.value.network,
      discoverForm.value.timeout,
      discoverForm.value.community || 'public'
    )
    ElMessage.success(`发现了 ${devices.length} 台设备`)
    showDiscoverDialog.value = false
    deviceStore.invalidate()
    await loadDevices()
  } catch {
    ElMessage.error('设备发现失败')
  } finally {
    discovering.value = false
  }
}

onMounted(async () => {
  updateClock()
  clockTimer = setInterval(updateClock, 30_000)
  window.addEventListener('resize', handleResize)
  await refreshAll()
})

onUnmounted(() => {
  if (clockTimer) clearInterval(clockTimer)
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
  trendChart = null
})
</script>

<style scoped>
.dashboard {
  min-height: 100%;
}

.dash-time {
  font-size: 13px;
  color: var(--nms-text-muted);
  font-variant-numeric: tabular-nums;
}

.kpi-row,
.section-row {
  margin-bottom: 0;
}

:deep(.kpi-card) {
  border-radius: var(--nms-radius);
  border-color: var(--nms-border-soft);
}

.aiops-strip {
  margin-bottom: 16px;
  background: linear-gradient(135deg, #eef6fc 0%, #fafcff 100%);
  border: 1px solid #cfe0f0;
}
.aiops-strip.risk {
  background: linear-gradient(135deg, #fff7e6 0%, #fff1f0 100%);
  border-color: #ffd8bf;
}
.aiops-strip-main {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  flex-wrap: wrap;
}
.aiops-strip-label {
  font-size: 12px;
  color: var(--nms-primary);
  font-weight: 600;
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.aiops-strip-headline {
  font-size: 16px;
  font-weight: 700;
  margin-bottom: 4px;
}
.aiops-strip-detail {
  font-size: 13px;
  color: #909399;
  max-width: 640px;
  line-height: 1.45;
}
.aiops-strip-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.aiops-strip-chips {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.aiops-chip {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 6px;
  background: #f5f7fa;
  font-size: 12px;
  color: #606266;
}
.aiops-chip em {
  font-style: normal;
  font-weight: 700;
  font-size: 16px;
  color: #303133;
}
.aiops-strip-tags {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.aiops-tag {
  cursor: pointer;
}

.kpi-card {
  margin-bottom: 16px;
  border: none;
}

.kpi-card.clickable {
  cursor: pointer;
  transition: transform 0.15s ease;
}

.kpi-card.clickable:hover {
  transform: translateY(-2px);
}

.kpi-body {
  display: flex;
  align-items: center;
  gap: 16px;
}

.kpi-icon {
  width: 52px;
  height: 52px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  color: #fff;
  flex-shrink: 0;
}

.kpi-icon.device { background: linear-gradient(135deg, #667eea, #764ba2); }
.kpi-icon.alarm { background: linear-gradient(135deg, #eb3349, #f45c43); }
.kpi-icon.ack { background: linear-gradient(135deg, #f7971e, #ffd200); }
.kpi-icon.config { background: linear-gradient(135deg, #11998e, #38ef7d); }
.kpi-icon.topo { background: linear-gradient(135deg, #4facfe, #00f2fe); }

.kpi-value {
  font-size: 28px;
  font-weight: 700;
  color: #303133;
  line-height: 1.2;
}

.kpi-value.danger { color: #f56c6c; }
.kpi-value.warning { color: #e6a23c; }

.kpi-label {
  font-size: 14px;
  color: #606266;
  margin-top: 2px;
}

.kpi-sub {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.trend-chart {
  height: 220px;
}

.health-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.health-item {
  text-align: center;
  padding: 16px 8px;
  border-radius: 8px;
  background: #f5f7fa;
  cursor: pointer;
  transition: background 0.15s;
}

.health-item:hover {
  background: #ecf5ff;
}

.health-item.warn .health-num { color: #e6a23c; }
.health-item.danger .health-num { color: #f56c6c; }

.health-num {
  display: block;
  font-size: 26px;
  font-weight: 700;
  color: #303133;
}

.health-label {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.quick-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

@media (max-width: 768px) {
  .health-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .dash-header {
    flex-direction: column;
  }
}
</style>
