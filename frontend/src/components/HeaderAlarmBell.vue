<template>
  <el-popover
    v-if="auth.hasPermission('alarms:read')"
    v-model:visible="panelVisible"
    placement="bottom-end"
    :width="380"
    trigger="click"
    :hide-after="0"
    popper-class="header-alarm-popper"
    @show="onOpen"
  >
    <template #reference>
      <button
        type="button"
        class="alarm-strip"
        :class="stripClass"
        title="告警摘要"
        @mouseenter="cancelClose"
        @mouseleave="scheduleClose"
      >
        <span class="alarm-strip-label">告警</span>
        <span class="alarm-strip-metrics">
          <span class="metric">
            <span class="metric-k">待处理</span>
            <span class="metric-v">{{ activeCount }}</span>
          </span>
          <span class="metric-sep" />
          <span class="metric is-critical">
            <span class="metric-k">严重</span>
            <span class="metric-v">{{ criticalCount }}</span>
          </span>
          <span v-if="majorCount > 0" class="metric-sep" />
          <span v-if="majorCount > 0" class="metric is-major">
            <span class="metric-k">重要</span>
            <span class="metric-v">{{ majorCount }}</span>
          </span>
        </span>
      </button>
    </template>

    <div
      class="alarm-panel"
      @mouseenter="cancelClose"
      @mouseleave="scheduleClose"
    >
      <div class="alarm-panel-head">
        <div>
          <div class="alarm-panel-title">告警摘要</div>
          <div class="alarm-panel-sub">未关闭 · 近 24h 值班视角</div>
        </div>
        <el-button type="primary" link @click="goAlarms">打开告警中心</el-button>
      </div>

      <div class="alarm-kpi-row">
        <button type="button" class="alarm-kpi" @click="goAlarmsFilter('active')">
          <span class="alarm-kpi-n">{{ activeCount }}</span>
          <span class="alarm-kpi-l">待处理</span>
        </button>
        <button type="button" class="alarm-kpi crit" @click="goAlarmsFilter('critical')">
          <span class="alarm-kpi-n">{{ criticalCount }}</span>
          <span class="alarm-kpi-l">严重</span>
        </button>
        <button type="button" class="alarm-kpi maj" @click="goAlarmsFilter('major')">
          <span class="alarm-kpi-n">{{ majorCount }}</span>
          <span class="alarm-kpi-l">重要</span>
        </button>
        <button type="button" class="alarm-kpi" @click="goAlarmsFilter('ack')">
          <span class="alarm-kpi-n">{{ ackCount }}</span>
          <span class="alarm-kpi-l">处理中</span>
        </button>
      </div>

      <div class="alarm-list-label">最新待处理</div>
      <div v-loading="loading" class="alarm-list">
        <button
          v-for="item in recent"
          :key="item.id"
          type="button"
          class="alarm-item"
          @click="goAlarm(item)"
        >
          <span class="sev-tag" :class="sevClass(item.severity)">{{ sevLabel(item.severity) }}</span>
          <div class="alarm-item-body">
            <div class="alarm-item-title">{{ item.title || item.message || '告警' }}</div>
            <div class="alarm-item-meta">
              {{ item.deviceName || item.deviceIp || '未知设备' }}
              <span v-if="item.occurredAt"> · {{ formatTime(item.occurredAt) }}</span>
            </div>
          </div>
        </button>
        <div v-if="!loading && !recent.length" class="alarm-empty">当前无待处理告警</div>
      </div>
    </div>
  </el-popover>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { alarmApi } from '@/api/device'
import { useAuthStore } from '@/stores/auth'
import { createVisibilityPoller } from '@/composables/useVisibilityPoller'

const auth = useAuthStore()
const router = useRouter()

const panelVisible = ref(false)
let closeTimer = null

const activeCount = ref(0)
const criticalCount = ref(0)
const majorCount = ref(0)
const ackCount = ref(0)
const recent = ref([])
const loading = ref(false)

const stripClass = computed(() => {
  if (criticalCount.value > 0) return 'is-critical'
  if (activeCount.value > 0 || majorCount.value > 0) return 'is-warn'
  return 'is-clear'
})

const cancelClose = () => {
  if (closeTimer) {
    clearTimeout(closeTimer)
    closeTimer = null
  }
}

const scheduleClose = () => {
  cancelClose()
  closeTimer = setTimeout(() => {
    panelVisible.value = false
    closeTimer = null
  }, 160)
}

const closePanel = () => {
  cancelClose()
  panelVisible.value = false
}

const sevClass = (s) => {
  const v = String(s || '').toUpperCase()
  if (v === 'CRITICAL') return 'critical'
  if (v === 'MAJOR' || v === 'WARNING') return 'major'
  if (v === 'MINOR') return 'minor'
  return 'info'
}

const sevLabel = (s) => {
  const v = String(s || '').toUpperCase()
  if (v === 'CRITICAL') return '严重'
  if (v === 'MAJOR' || v === 'WARNING') return '重要'
  if (v === 'MINOR') return '次要'
  return '提示'
}

const formatTime = (t) => {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return String(t)
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const refreshStats = async () => {
  if (!auth.hasPermission('alarms:read')) return
  try {
    const stats = await alarmApi.getAlarmStats()
    activeCount.value = Number(stats?.activeCount || 0)
    criticalCount.value = Number(stats?.criticalActiveCount || 0)
    majorCount.value = Number(stats?.majorActiveCount || 0)
    ackCount.value = Number(stats?.acknowledgedCount || 0)
  } catch {
    /* 静默 */
  }
}

const loadRecent = async () => {
  if (!auth.hasPermission('alarms:read')) return
  loading.value = true
  try {
    const res = await alarmApi.queryAlarms({
      status: ['ACTIVE'],
      page: 0,
      size: 8,
      sort: 'occurredAt,desc'
    })
    recent.value = Array.isArray(res?.content) ? res.content : (Array.isArray(res) ? res : [])
  } catch {
    recent.value = []
  } finally {
    loading.value = false
  }
}

const onOpen = () => {
  cancelClose()
  loadRecent()
  refreshStats()
}

const goAlarms = () => {
  closePanel()
  router.push('/alarms')
}

const goAlarmsFilter = (kind) => {
  closePanel()
  if (kind === 'critical') {
    router.push({ path: '/alarms', query: { severity: 'CRITICAL' } })
  } else if (kind === 'major') {
    router.push({ path: '/alarms', query: { severity: 'MAJOR' } })
  } else if (kind === 'ack') {
    router.push({ path: '/alarms', query: { status: 'ACKNOWLEDGED' } })
  } else {
    router.push('/alarms')
  }
}

const goAlarm = (item) => {
  closePanel()
  const q = {}
  if (item?.deviceId != null) q.deviceId = String(item.deviceId)
  if (item?.id != null) q.alarmId = String(item.id)
  router.push({ path: '/alarms', query: q })
}

const poller = createVisibilityPoller(refreshStats, 30000)

onMounted(() => {
  refreshStats()
  poller.start()
})

onUnmounted(() => {
  cancelClose()
  poller.stop()
})
</script>

<style scoped>
.alarm-strip {
  appearance: none;
  display: inline-flex;
  align-items: stretch;
  height: 32px;
  padding: 0;
  border: 1px solid var(--nms-border-soft, #e8eef5);
  border-radius: 6px;
  background: #f7f8fb;
  cursor: pointer;
  font-family: inherit;
  overflow: hidden;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.alarm-strip:hover {
  border-color: #c5ced9;
  background: #fff;
}

.alarm-strip.is-critical {
  border-color: rgba(199, 58, 46, 0.35);
  background: rgba(199, 58, 46, 0.06);
}

.alarm-strip.is-warn {
  border-color: rgba(212, 136, 6, 0.35);
  background: rgba(212, 136, 6, 0.06);
}

.alarm-strip-label {
  display: inline-flex;
  align-items: center;
  padding: 0 10px;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.06em;
  color: #5a6a7e;
  background: rgba(255, 255, 255, 0.55);
  border-right: 1px solid var(--nms-border-soft, #e8eef5);
}

.alarm-strip.is-critical .alarm-strip-label {
  color: #c73a2e;
}

.alarm-strip-metrics {
  display: inline-flex;
  align-items: center;
  padding: 0 4px;
}

.metric {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  padding: 0 8px;
  white-space: nowrap;
}

.metric-k {
  font-size: 11px;
  color: #8a97a8;
}

.metric-v {
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  font-family: 'IBM Plex Mono', Consolas, monospace;
  color: #1a2332;
  line-height: 1;
}

.metric.is-critical .metric-v {
  color: #c73a2e;
}

.metric.is-major .metric-v {
  color: #d48806;
}

.metric-sep {
  width: 1px;
  height: 14px;
  background: #d5dde8;
}

.alarm-panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.alarm-panel-title {
  font-size: 13px;
  font-weight: 650;
  color: var(--nms-text, #1a2332);
}

.alarm-panel-sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--nms-text-secondary, #5c6b7f);
}

.alarm-kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 6px;
  margin-bottom: 12px;
}

.alarm-kpi {
  appearance: none;
  border: 1px solid var(--nms-border-soft, #e8eef5);
  border-radius: 6px;
  background: #f7f8fb;
  padding: 8px 4px;
  cursor: pointer;
  font-family: inherit;
  text-align: center;
}

.alarm-kpi:hover {
  background: #fff;
  border-color: #c5ced9;
}

.alarm-kpi-n {
  display: block;
  font-size: 16px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  font-family: 'IBM Plex Mono', Consolas, monospace;
  color: #1a2332;
  line-height: 1.1;
}

.alarm-kpi-l {
  display: block;
  margin-top: 2px;
  font-size: 10px;
  color: #8a97a8;
}

.alarm-kpi.crit .alarm-kpi-n { color: #c73a2e; }
.alarm-kpi.maj .alarm-kpi-n { color: #d48806; }

.alarm-list-label {
  font-size: 11px;
  font-weight: 600;
  color: #8a97a8;
  margin-bottom: 6px;
  letter-spacing: 0.02em;
}

.alarm-list {
  min-height: 72px;
  max-height: 280px;
  overflow-y: auto;
}

.alarm-item {
  appearance: none;
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 4px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  font-family: inherit;
}

.alarm-item:hover {
  background: rgba(26, 35, 50, 0.04);
}

.sev-tag {
  flex-shrink: 0;
  margin-top: 1px;
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 10px;
  font-weight: 650;
  line-height: 1.4;
  color: #fff;
  background: #909399;
}

.sev-tag.critical { background: #c73a2e; }
.sev-tag.major { background: #d48806; }
.sev-tag.minor { background: var(--nms-primary, #2f6fed); }
.sev-tag.info { background: #909399; }

.alarm-item-title {
  font-size: 12px;
  font-weight: 550;
  color: var(--nms-text, #1a2332);
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.alarm-item-meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--nms-text-secondary, #5c6b7f);
}

.alarm-empty {
  padding: 20px 8px;
  text-align: center;
  font-size: 12px;
  color: #8a97a8;
}
</style>
