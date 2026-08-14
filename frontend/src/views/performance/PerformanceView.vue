<template>
  <div class="nms-page performance-view">
    <div class="nms-page-header">
      <div class="nms-page-title-block">
        <h1 class="nms-page-title">性能监控</h1>
        <p class="nms-page-subtitle">
          {{ device?.name || device?.ipAddress || '请选择可采集设备' }}
          · SNMP 指标与趋势
        </p>
      </div>
      <div class="nms-page-actions">
        <el-tag v-if="device?.status === 'online'" type="success" effect="plain">设备在线</el-tag>
        <el-tag v-else-if="device?.status === 'offline'" type="danger" effect="plain">设备离线</el-tag>
        <el-tag v-else-if="device" type="info" effect="plain">状态未知</el-tag>
        <el-select v-model="selectedDeviceId" placeholder="选择可采集设备" @change="onDeviceChange" style="width: 280px">
          <el-option
            v-for="d in performanceDevices"
            :key="d.id"
            :label="`${d.name} (${d.ipAddress})`"
            :value="d.id"
          />
        </el-select>
        <el-button
          v-if="selectedDeviceId && auth.hasAnyPermission('aiops:read', 'alarms:read')"
          type="primary"
          plain
          @click="openOpsAssist"
        >运维辅助</el-button>
        <el-button
          v-if="selectedDeviceId && auth.hasAnyPermission('aiops:read', 'alarms:read')"
          @click="askExplainAnomaly"
        >解释异常</el-button>
        <el-button v-if="selectedDeviceId" type="primary" @click="loadPerformanceData" :icon="Refresh">刷新</el-button>
      </div>
    </div>

    <div class="nms-panel">
      <div class="nms-panel-body">
      <div class="perf-work-split">
      <div class="perf-work-main">
      <!-- 提示信息 -->
      <el-empty v-if="devices.length === 0" description="暂无设备，请先添加设备" style="padding: 60px 0;" />
      <el-empty
        v-else-if="performanceDevices.length === 0"
        description="当前设备均不支持 SNMP 性能采集（如虚拟 PC）"
        style="padding: 60px 0;"
      />
      <el-alert v-else-if="!selectedDeviceId" type="info" description="请在上方选择一个设备以查看性能数据" :closable="false" style="margin-bottom: 20px;" />
      <el-alert
        v-else-if="selectedDeviceId && device && !canCollectPerformance(device)"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 20px"
        title="该设备类型不支持 SNMP 性能采集（如虚拟 PC / Ping 监控），请选择交换机或路由器。"
      />

      <div v-if="selectedDeviceId && canCollectPerformance(device)" class="overview-section" v-loading="loading">
        <el-alert
          v-if="device?.status === 'offline'"
          type="warning"
          :closable="false"
          show-icon
          class="offline-alert"
          title="设备离线：停止性能采集。下方为最近一次采集结果（若有）。"
        />

        <div class="metric-grid">
          <div class="metric-tile" :class="metricTone(cpuUsage, 70, 85)">
            <div class="metric-tile-top">
              <div>
                <div class="metric-kicker">Processor</div>
                <div class="metric-label">CPU 使用率</div>
              </div>
              <span v-if="cpuAlert" class="metric-alert" :class="cpuAlert.level">
                {{ cpuAlert.level === 'danger' ? '严重' : '警告' }}
              </span>
            </div>
            <div class="metric-tile-main">
              <div
                class="ring"
                :style="ringStyle(cpuUsage, getCpuColor())"
              >
                <div class="ring-inner">
                  <div class="ring-value">{{ fmtPct(cpuUsage) }}</div>
                </div>
              </div>
              <div class="metric-side">
                <div class="metric-big" :style="{ color: getCpuColor() }">{{ fmtPct(cpuUsage) }}</div>
                <div class="metric-hint">
                  {{ metricSourceLabel(performanceData?.cpuSource) }}
                  <el-tag v-if="performanceData?.dataSource === 'simulated'" size="small" type="info" effect="plain" class="src-tag">仿真</el-tag>
                </div>
                <svg v-if="sparkCpu.length > 1" class="spark" viewBox="0 0 120 28" preserveAspectRatio="none">
                  <polyline
                    fill="none"
                    :stroke="getCpuColor()"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    :points="sparkPoints(sparkCpu)"
                  />
                </svg>
                <div v-else class="spark-empty">暂无趋势</div>
              </div>
            </div>
          </div>

          <div class="metric-tile" :class="metricTone(memoryUsage, 75, 90)">
            <div class="metric-tile-top">
              <div>
                <div class="metric-kicker">Memory</div>
                <div class="metric-label">内存使用率</div>
              </div>
              <span v-if="memoryAlert" class="metric-alert" :class="memoryAlert.level">
                {{ memoryAlert.level === 'danger' ? '严重' : '警告' }}
              </span>
            </div>
            <div class="metric-tile-main">
              <div class="ring" :style="ringStyle(memoryUsage, getMemoryColor())">
                <div class="ring-inner">
                  <div class="ring-value">{{ fmtPct(memoryUsage) }}</div>
                </div>
              </div>
              <div class="metric-side">
                <div class="metric-big" :style="{ color: getMemoryColor() }">{{ fmtPct(memoryUsage) }}</div>
                <div class="metric-hint">
                  {{ formatBytes(memoryUsed) }} / {{ formatBytes(memoryTotal) }}
                  · {{ metricSourceLabel(performanceData?.memorySource) }}
                </div>
                <svg v-if="sparkMem.length > 1" class="spark" viewBox="0 0 120 28" preserveAspectRatio="none">
                  <polyline
                    fill="none"
                    :stroke="getMemoryColor()"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    :points="sparkPoints(sparkMem)"
                  />
                </svg>
                <div v-else class="spark-empty">暂无趋势</div>
              </div>
            </div>
          </div>

          <div class="metric-tile" :class="tempTone">
            <div class="metric-tile-top">
              <div>
                <div class="metric-kicker">Thermal</div>
                <div class="metric-label">设备温度</div>
              </div>
              <span v-if="tempAlert" class="metric-alert" :class="tempAlert.level">
                {{ tempAlert.level === 'danger' ? '严重' : '警告' }}
              </span>
            </div>
            <div class="metric-tile-main">
              <div class="temp-meter">
                <div class="temp-track">
                  <div class="temp-fill" :style="{ height: tempFillPct + '%', background: getTemperatureColor() }" />
                </div>
                <div class="temp-bulb" :style="{ background: getTemperatureColor() }" />
              </div>
              <div class="metric-side">
                <div class="metric-big" :style="{ color: getTemperatureColor() }">
                  {{ temperature != null ? temperature.toFixed(1) + '°C' : '-' }}
                </div>
                <div class="metric-hint">阈值 60 / 70°C</div>
                <svg v-if="sparkTemp.length > 1" class="spark" viewBox="0 0 120 28" preserveAspectRatio="none">
                  <polyline
                    fill="none"
                    :stroke="getTemperatureColor()"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    :points="sparkPoints(sparkTemp, 20, 80)"
                  />
                </svg>
                <div v-else class="spark-empty">暂无趋势</div>
              </div>
            </div>
          </div>

          <div class="metric-tile hw-tile">
            <div class="metric-tile-top">
              <div>
                <div class="metric-kicker">Hardware</div>
                <div class="metric-label">硬件状态</div>
              </div>
              <el-tag v-if="!fanStatus && !powerStatus" size="small" type="info" effect="plain">未采集</el-tag>
            </div>
            <div class="hw-list">
              <div class="hw-item" :class="hwClass(fanStatus, 'fan')">
                <div class="hw-icon"><el-icon><Odometer /></el-icon></div>
                <div class="hw-meta">
                  <div class="hw-name">风扇</div>
                  <div class="hw-val">{{ fanLabel }}</div>
                </div>
                <span class="hw-dot" />
              </div>
              <div class="hw-item" :class="hwClass(powerStatus, 'power')">
                <div class="hw-icon"><el-icon><Lightning /></el-icon></div>
                <div class="hw-meta">
                  <div class="hw-name">电源</div>
                  <div class="hw-val">{{ powerLabel }}</div>
                </div>
                <span class="hw-dot" />
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="selectedDeviceId" class="section-block ports-section">
        <div class="section-head">
          <div>
            <h3>端口流量</h3>
            <p>接口累计字节与实时速率</p>
          </div>
          <el-tag size="small" effect="plain" type="info">{{ portMetrics.length }} 个端口</el-tag>
        </div>
        <el-table
          :data="portMetrics"
          class="perf-table"
          style="width: 100%"
          v-loading="loading"
          :header-cell-style="{ background: '#f3f6fa' }"
        >
          <el-table-column prop="portName" label="端口" min-width="160">
            <template #default="{ row }">
              <div class="port-name-cell">
                <span class="port-indicator" :class="getPortStatusClass(row)" />
                <span>{{ row.portName || '未知端口' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="入流量" min-width="140">
            <template #default="{ row }">
              <div class="traffic-cell in">
                <div class="traffic-bytes">{{ formatBytes(row.ifInOctets) }}</div>
                <div v-if="row.ifInRate" class="traffic-rate">{{ formatRate(row.ifInRate) }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="出流量" min-width="140">
            <template #default="{ row }">
              <div class="traffic-cell out">
                <div class="traffic-bytes">{{ formatBytes(row.ifOutOctets) }}</div>
                <div v-if="row.ifOutRate" class="traffic-rate">{{ formatRate(row.ifOutRate) }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <span class="nms-status" :class="row.portOperStatus === 'up' ? 'is-online theme-port' : 'is-offline'">
                <span class="nms-status-dot" />
                {{ row.portOperStatus || 'unknown' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="错误" width="160">
            <template #default="{ row }">
              <div class="err-cell">
                <template v-if="row.portErrorsIn || row.portErrorsOut">
                  <el-tag v-if="row.portErrorsIn" type="danger" size="small" effect="plain">入 {{ row.portErrorsIn }}</el-tag>
                  <el-tag v-if="row.portErrorsOut" type="danger" size="small" effect="plain">出 {{ row.portErrorsOut }}</el-tag>
                </template>
                <span v-else class="muted">-</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="丢包" width="160">
            <template #default="{ row }">
              <div class="err-cell">
                <template v-if="row.portDiscardsIn || row.portDiscardsOut">
                  <el-tag v-if="row.portDiscardsIn" type="warning" size="small" effect="plain">入 {{ row.portDiscardsIn }}</el-tag>
                  <el-tag v-if="row.portDiscardsOut" type="warning" size="small" effect="plain">出 {{ row.portDiscardsOut }}</el-tag>
                </template>
                <span v-else class="muted">-</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="selectedDeviceId" class="section-block alerts-section">
        <div class="section-head">
          <div>
            <h3>性能告警</h3>
            <p>当前设备未解决的性能阈值事件</p>
          </div>
        </div>
        <el-alert v-if="deviceAlerts.length === 0" type="success" :closable="false" show-icon title="当前无告警" />
        <div v-else class="alert-list">
          <div v-for="alert in deviceAlerts" :key="alert.id" class="alert-row">
            <div class="alert-main">
              <el-tag size="small" :type="getAlertType(alert.level)" effect="plain">{{ alert.level === 'danger' ? '严重' : '警告' }}</el-tag>
              <div>
                <div class="alert-msg">{{ alert.message }}</div>
                <div class="alert-sub">
                  {{ formatTime(alert.createdAt) }}
                  · 当前 {{ alert.currentValue?.toFixed(1) }} / 阈值 {{ alert.thresholdValue?.toFixed(1) }}
                </div>
              </div>
            </div>
            <div class="alert-actions">
              <el-tag :type="getAlertType(alert.status)" size="small" effect="plain">{{ getAlertStatusText(alert.status) }}</el-tag>
              <el-button
                v-if="alert.status === 'active'"
                v-permission="'alarms:handle'"
                size="small"
                type="primary"
                @click="acknowledgeAlert(alert.id)"
              >确认</el-button>
              <el-button
                v-if="alert.status === 'active' || alert.status === 'acknowledged'"
                v-permission="'alarms:handle'"
                size="small"
                @click="resolveAlert(alert.id)"
              >解决</el-button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="selectedDeviceId" class="section-block history-section">
        <div class="section-head">
          <div>
            <h3>历史趋势</h3>
            <p>CPU / 内存 / 温度采样曲线</p>
          </div>
          <el-radio-group v-model="historyTimeRange" size="small" @change="loadHistoryData">
            <el-radio-button label="1h">1小时</el-radio-button>
            <el-radio-button label="3h">3小时</el-radio-button>
            <el-radio-button label="6h">6小时</el-radio-button>
            <el-radio-button label="12h">12小时</el-radio-button>
          </el-radio-group>
        </div>
        <div class="chart-card" v-loading="historyLoading">
          <div class="chart-card-title">CPU & 内存</div>
          <div ref="cpuMemChartRef" class="chart-container" />
        </div>
        <div class="chart-card" v-loading="historyLoading">
          <div class="chart-card-title">温度</div>
          <div ref="tempChartRef" class="chart-container" />
        </div>
      </div>
      </div>

      <OpsInlineShell storage-key="performance" class="perf-work-side" />
      </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createVisibilityPoller } from '@/composables/useVisibilityPoller'
import { deviceApi, performanceApi } from '@/api/device'
import { canCollectPerformance } from '@/utils/deviceCapabilities'
import { askOpsAssistant, syncOpsAssistantFocus } from '@/composables/askOpsAssistant'
import { clearPageContext, setPageContext, usePageOpsBus } from '@/composables/pageOpsBus'
import { requestOpenOpsInline } from '@/composables/useOpsInlinePanel'
import OpsInlineShell from '@/components/ops-assistant/OpsInlineShell.vue'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'
import { Refresh, Odometer, Lightning } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { readThemePrimary, primaryAlpha, useThemeSync } from '@/composables/useThemeColors'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'

const route = useRoute()
const router = useRouter()
const { sink: pageSink } = usePageOpsBus()
const auth = useAuthStore()
const devices = ref([])
const selectedDeviceId = ref(null)
const performanceData = ref(null)
const portMetrics = ref([])
const deviceAlerts = ref([])
const loading = ref(false)
const historyLoading = ref(false)
const historyTimeRange = ref('1h')
const cpuMemChartRef = ref(null)
const tempChartRef = ref(null)
const sparkCpu = ref([])
const sparkMem = ref([])
const sparkTemp = ref([])
let cpuMemChart = null
let tempChart = null
const perfPoller = createVisibilityPoller(() => loadPerformanceData(), 10000)
const historyPoller = createVisibilityPoller(() => loadHistoryData(), 30000)
let isLoadingPerformance = false
let isLoadingHistory = false

const device = computed(() => devices.value.find(d => d.id === selectedDeviceId.value))
const performanceDevices = computed(() => devices.value.filter(d => canCollectPerformance(d)))
const cpuUsage = computed(() => performanceData.value?.cpuUsage)
const memoryUsage = computed(() => performanceData.value?.memoryUsage)

function metricSourceLabel(src) {
  if (src === 'snmp') return 'SNMP 真采'
  if (src === 'simulated') return '仿真回退'
  if (src === 'mixed') return '混合'
  return '来源未知'
}
const memoryUsed = computed(() => performanceData.value?.memoryUsed)
const memoryTotal = computed(() => performanceData.value?.memoryTotal)
const temperature = computed(() => performanceData.value?.temperature)
const fanStatus = computed(() => performanceData.value?.fanStatus)
const powerStatus = computed(() => performanceData.value?.powerStatus)

const cpuAlert = computed(() => deviceAlerts.value.find(a => a.metric === 'cpu' && a.status !== 'resolved'))
const memoryAlert = computed(() => deviceAlerts.value.find(a => a.metric === 'memory' && a.status !== 'resolved'))
const tempAlert = computed(() => deviceAlerts.value.find(a => a.metric === 'temperature' && a.status !== 'resolved'))

const tempFillPct = computed(() => {
  if (temperature.value == null) return 0
  return Math.max(4, Math.min(100, ((temperature.value - 10) / 70) * 100))
})

const tempTone = computed(() => {
  const val = temperature.value
  if (val == null) return ''
  if (val > 70) return 'tone-danger'
  if (val > 60) return 'tone-warn'
  return 'tone-ok'
})

const fanLabel = computed(() => {
  const s = fanStatus.value
  if (!s) return '未采集'
  if (s === 'normal' || s === 'low') return '正常'
  if (s === 'medium') return '偏高'
  if (s === 'high') return '异常'
  return s
})

const powerLabel = computed(() => {
  const s = powerStatus.value
  if (!s) return '未采集'
  if (s === 'normal') return '正常'
  return s
})

const primaryColor = () => readThemePrimary()
const themeStore = useThemeStore()
const { revision: themeRevision } = storeToRefs(themeStore)

/** 正常态用主题主色；告警阈值仍用语义橙/红 */
const cpuColor = computed(() => {
  void themeRevision.value
  const val = cpuUsage.value
  if (val == null) return primaryColor()
  if (val > 85) return '#d4380d'
  if (val > 70) return '#d48806'
  return primaryColor()
})

const memoryColor = computed(() => {
  void themeRevision.value
  const val = memoryUsage.value
  if (val == null) return primaryColor()
  if (val > 90) return '#d4380d'
  if (val > 75) return '#d48806'
  return primaryColor()
})

const temperatureColor = computed(() => {
  void themeRevision.value
  const val = temperature.value
  if (val == null) return '#8a96a8'
  if (val > 70) return '#d4380d'
  if (val > 60) return '#d48806'
  return primaryColor()
})

useThemeSync(() => {
  if (cpuMemChart || tempChart) {
    loadHistoryData()
  }
})

const fmtPct = (v) => (v != null && !Number.isNaN(v) ? `${Number(v).toFixed(1)}%` : '-')

const ringStyle = (val, color) => {
  const p = Math.max(0, Math.min(100, Number(val) || 0))
  return {
    background: `conic-gradient(${color} ${p * 3.6}deg, #e8eef5 0deg)`
  }
}

const metricTone = (val, warn, danger) => {
  if (val == null) return ''
  if (val > danger) return 'tone-danger'
  if (val > warn) return 'tone-warn'
  return 'tone-ok'
}

const sparkPoints = (arr, minY = 0, maxY = 100) => {
  const vals = (arr || []).filter((v) => v != null && !Number.isNaN(v))
  if (vals.length < 2) return ''
  const w = 120
  const h = 28
  const span = Math.max(1, maxY - minY)
  return vals
    .map((v, i) => {
      const x = (i / (vals.length - 1)) * w
      const y = h - ((Math.max(minY, Math.min(maxY, v)) - minY) / span) * (h - 2) - 1
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
}

const hwClass = (status, kind) => {
  if (!status) return 'is-unknown'
  if (kind === 'power') return status === 'normal' ? 'is-ok' : 'is-bad'
  if (status === 'high') return 'is-bad'
  if (status === 'medium') return 'is-warn'
  return 'is-ok'
}

const loadDevices = async () => {
  try {
    devices.value = await deviceApi.getDevices()
    const qid = route.query.deviceId != null ? Number(route.query.deviceId) : null
    const perfList = devices.value.filter(d => canCollectPerformance(d))
    if (qid && perfList.some(d => Number(d.id) === qid)) {
      selectedDeviceId.value = qid
      nextTick(() => {
        loadPerformanceData()
        loadHistoryData()
      })
    } else if (perfList.length > 0 && !selectedDeviceId.value) {
      selectedDeviceId.value = perfList[0].id
      nextTick(() => {
        loadPerformanceData()
        loadHistoryData()
      })
    } else if (!perfList.length) {
      selectedDeviceId.value = null
    }
  } catch (err) {
    ElMessage.error('加载设备列表失败')
  }
}

const loadPerformanceData = async () => {
  if (!selectedDeviceId.value || isLoadingPerformance) return
  isLoadingPerformance = true
  loading.value = true
  try {
    const data = await performanceApi.getLatestPerformance(selectedDeviceId.value)
    performanceData.value = data && data.portIndex == null ? data : null
    const ports = await performanceApi.getLatestPortMetrics(selectedDeviceId.value)
    portMetrics.value = Array.isArray(ports) ? ports : []
    await loadDeviceAlerts()
  } catch (err) {
    console.error('加载性能数据失败', err)
    ElMessage.error('加载性能数据失败')
  } finally {
    loading.value = false
    isLoadingPerformance = false
  }
}

const loadDeviceAlerts = async () => {
  if (!selectedDeviceId.value) return
  try {
    const list = await performanceApi.getDeviceAlerts(selectedDeviceId.value, 'active,acknowledged')
    deviceAlerts.value = Array.isArray(list) ? list : []
  } catch (err) {
    console.error('加载告警数据失败', err)
    deviceAlerts.value = []
  }
}

const acknowledgeAlert = async (alertId) => {
  try {
    await performanceApi.acknowledgeAlert(alertId)
    ElMessage.success('告警已进入处理中')
    await loadDeviceAlerts()
  } catch (err) {
    ElMessage.error('确认告警失败')
  }
}

const resolveAlert = async (alertId) => {
  try {
    await performanceApi.resolveAlert(alertId)
    ElMessage.success('告警已解决')
    await loadDeviceAlerts()
  } catch (err) {
    ElMessage.error('解决告警失败')
  }
}

const formatLocalDateTime = (d) => {
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

const loadHistoryData = async () => {
  if (!selectedDeviceId.value || isLoadingHistory) return
  isLoadingHistory = true
  historyLoading.value = true
  try {
    const now = new Date()
    let start = new Date()
    switch (historyTimeRange.value) {
      case '1h': start.setHours(now.getHours() - 1); break
      case '3h': start.setHours(now.getHours() - 3); break
      case '6h': start.setHours(now.getHours() - 6); break
      case '12h': start.setHours(now.getHours() - 12); break
    }
    const raw = await performanceApi.getPerformanceHistory(
      selectedDeviceId.value,
      formatLocalDateTime(start),
      formatLocalDateTime(now)
    )
    const systemData = Array.isArray(raw) ? raw : []
    sparkCpu.value = systemData.map((d) => d.cpuUsage).filter((v) => v != null).slice(-24)
    sparkMem.value = systemData.map((d) => d.memoryUsage).filter((v) => v != null).slice(-24)
    sparkTemp.value = systemData.map((d) => d.temperature).filter((v) => v != null).slice(-24)
    await nextTick()
    updateCpuMemChart(systemData)
    updateTempChart(systemData)
  } catch (err) {
    console.error('加载历史数据失败', err)
    updateCpuMemChart([])
    updateTempChart([])
  } finally {
    historyLoading.value = false
    isLoadingHistory = false
  }
}

const emptyChartOption = (title) => ({
  title: {
    text: title || '暂无历史数据',
    left: 'center',
    top: 'middle',
    textStyle: { color: '#8a96a8', fontSize: 13, fontWeight: 'normal' }
  },
  xAxis: { show: false },
  yAxis: { show: false },
  series: []
})

const updateCpuMemChart = (data) => {
  if (!cpuMemChartRef.value) return
  if (!cpuMemChart) {
    cpuMemChart = echarts.init(cpuMemChartRef.value)
  }
  if (!data.length) {
    cpuMemChart.setOption(emptyChartOption('暂无 CPU/内存历史数据'), true)
    return
  }
  const primary = primaryColor()
  const times = data.map(d => new Date(d.timestamp).toLocaleTimeString())
  const cpuValues = data.map(d => d.cpuUsage != null ? d.cpuUsage : null)
  const memValues = data.map(d => d.memoryUsage != null ? d.memoryUsage : null)
  const option = {
    color: [primary, '#64748b'],
    tooltip: { trigger: 'axis' },
    legend: { data: ['CPU使用率', '内存使用率'], top: 0, textStyle: { color: '#5c6b7f' } },
    grid: { left: 40, right: 20, top: 36, bottom: 28 },
    xAxis: {
      type: 'category',
      data: times,
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#d9e1ec' } },
      axisLabel: { color: '#8a96a8' }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { formatter: '{value}%', color: '#8a96a8' },
      splitLine: { lineStyle: { color: '#eef2f7' } }
    },
    series: [
      {
        name: 'CPU使用率',
        type: 'line',
        smooth: true,
        showSymbol: false,
        connectNulls: true,
        data: cpuValues,
        areaStyle: { color: primaryAlpha(0.14) },
        lineStyle: { width: 2, color: primary },
        itemStyle: { color: primary }
      },
      {
        name: '内存使用率',
        type: 'line',
        smooth: true,
        showSymbol: false,
        connectNulls: true,
        data: memValues,
        areaStyle: { color: 'rgba(100, 116, 139, 0.12)' },
        lineStyle: { width: 2, color: '#64748b' },
        itemStyle: { color: '#64748b' }
      }
    ]
  }
  cpuMemChart.setOption(option, true)
}

const updateTempChart = (data) => {
  if (!tempChartRef.value) return
  if (!tempChart) {
    tempChart = echarts.init(tempChartRef.value)
  }
  const hasTemp = data.some(d => d.temperature != null)
  if (!data.length || !hasTemp) {
    tempChart.setOption(emptyChartOption('暂无温度历史数据'), true)
    return
  }
  const times = data.map(d => new Date(d.timestamp).toLocaleTimeString())
  const tempValues = data.map(d => d.temperature != null ? d.temperature : null)
  const option = {
    tooltip: { trigger: 'axis' },
    legend: { data: ['温度'], top: 0, textStyle: { color: '#5c6b7f' } },
    grid: { left: 40, right: 20, top: 36, bottom: 28 },
    xAxis: {
      type: 'category',
      data: times,
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#d9e1ec' } },
      axisLabel: { color: '#8a96a8' }
    },
    yAxis: {
      type: 'value',
      min: 20,
      max: 80,
      axisLabel: { formatter: '{value}°C', color: '#8a96a8' },
      splitLine: { lineStyle: { color: '#eef2f7' } }
    },
    series: [
      {
        name: '温度',
        type: 'line',
        smooth: true,
        showSymbol: false,
        connectNulls: true,
        data: tempValues,
        areaStyle: { color: 'rgba(212, 56, 13, 0.1)' },
        itemStyle: { color: '#d4380d' },
        lineStyle: { color: '#d4380d', width: 2 },
        markLine: {
          symbol: 'none',
          data: [
            { yAxis: 60, name: '警告', lineStyle: { color: '#d48806', type: 'dashed' } },
            { yAxis: 70, name: '危险', lineStyle: { color: '#d4380d', type: 'dashed' } }
          ],
          label: { color: '#8a96a8', fontSize: 11 }
        }
      }
    ]
  }
  tempChart.setOption(option, true)
}

const onDeviceChange = () => {
  performanceData.value = null
  portMetrics.value = []
  deviceAlerts.value = []
  const d = device.value
  if (d?.id) {
    const cpu = cpuUsage.value
    const mem = memoryUsage.value
    syncOpsAssistantFocus({
      deviceId: d.id,
      title: `${d.name || d.ipAddress || d.id} · CPU ${cpu != null ? Number(cpu).toFixed(0) : '-'}% / MEM ${mem != null ? Number(mem).toFixed(0) : '-'}%`,
      deviceName: d.name || d.ipAddress || '',
      source: 'performance',
      scenario: 'PERFORMANCE',
      scenarioLabel: '性能'
    })
    setPageContext({
      page: 'performance',
      deviceId: d.id,
      title: `${d.name || d.ipAddress || d.id} · CPU ${cpu != null ? Number(cpu).toFixed(0) : '-'}% / MEM ${mem != null ? Number(mem).toFixed(0) : '-'}%`,
      deviceName: d.name || d.ipAddress || '',
      scenario: 'PERFORMANCE'
    })
  } else {
    setPageContext({ page: 'performance' })
  }
  loadPerformanceData()
  nextTick(() => {
    loadHistoryData()
  })
}

const askExplainAnomaly = () => {
  const d = device.value
  if (!d?.id) {
    ElMessage.warning('请先选择设备')
    return
  }
  const cpu = cpuUsage.value
  const mem = memoryUsage.value
  const temp = temperature.value
  askOpsAssistant({
    deviceId: d.id,
    title: `${d.name || d.ipAddress} · CPU ${cpu != null ? Number(cpu).toFixed(0) : '-'}% / MEM ${mem != null ? Number(mem).toFixed(0) : '-'}%`,
    deviceName: d.name || d.ipAddress || '',
    source: 'performance',
    scenario: 'PERFORMANCE',
    scenarioLabel: '性能',
    primaryToolLabel: '性能快照',
    recommendedTools: [
      { name: 'get_perf_snapshot', label: '性能快照', needConfirm: false, args: { deviceId: d.id } },
      { name: 'get_device_summary', label: '设备摘要', needConfirm: false, args: { deviceId: d.id } },
      { name: 'list_active_alarms_for_device', label: '活动告警', needConfirm: false, args: { deviceId: d.id } }
    ],
    expand: false,
    autoAsk: true,
    autoAskQuestion: `诊断性能：CPU=${cpu ?? '-'}% 内存=${mem ?? '-'}% 温度=${temp ?? '-'}`
  })
}

function openOpsAssist() {
  requestOpenOpsInline('performance')
}

const getCpuColor = () => cpuColor.value
const getMemoryColor = () => memoryColor.value
const getTemperatureColor = () => temperatureColor.value

const getPortStatusClass = (port) => {
  const oper = port.portOperStatus
  if (oper === 'up') return 'active'
  if (oper === 'down') return 'inactive'
  const hasTraffic = (port.ifInRate || 0) > 0 || (port.ifOutRate || 0) > 0
  return hasTraffic ? 'active' : 'inactive'
}

const getAlertType = (level) => {
  if (level === 'danger') return 'danger'
  if (level === 'warning') return 'warning'
  if (level === 'resolved') return 'success'
  if (level === 'acknowledged') return 'info'
  return 'primary'
}

const getAlertStatusText = (status) => {
  if (status === 'active') return '待处理'
  if (status === 'acknowledged') return '处理中'
  if (status === 'resolved') return '已解决'
  return status
}

const formatBytes = (bytes) => {
  if (bytes == null) return '-'
  if (bytes >= 1073741824) return (bytes / 1073741824).toFixed(2) + ' GB'
  if (bytes >= 1048576) return (bytes / 1048576).toFixed(2) + ' MB'
  if (bytes >= 1024) return (bytes / 1024).toFixed(2) + ' KB'
  return bytes.toLocaleString() + ' B'
}

const formatRate = (rate) => {
  if (!rate) return '-'
  if (rate > 1000000000) return (rate / 1000000000).toFixed(2) + ' Gbps'
  if (rate > 1000000) return (rate / 1000000).toFixed(2) + ' Mbps'
  if (rate > 1000) return (rate / 1000).toFixed(2) + ' Kbps'
  return rate.toFixed(2) + ' bps'
}

const formatTime = (timeStr) => {
  if (!timeStr) return ''
  return new Date(timeStr).toLocaleString()
}

onMounted(() => {
  setPageContext({ page: 'performance' })
  loadDevices()
  perfPoller.start()
  historyPoller.start()
  window.addEventListener('resize', onChartResize)
})

watch(() => route.query.deviceId, (id) => {
  if (id == null || !devices.value.length) return
  const qid = Number(id)
  if (!qid || Number(selectedDeviceId.value) === qid) return
  if (devices.value.some(d => Number(d.id) === qid)) {
    selectedDeviceId.value = qid
    onDeviceChange()
  }
})

const onChartResize = () => {
  cpuMemChart?.resize()
  tempChart?.resize()
}

onUnmounted(() => {
  clearPageContext()
  perfPoller.stop()
  historyPoller.stop()
  window.removeEventListener('resize', onChartResize)
  if (cpuMemChart) cpuMemChart.dispose()
  if (tempChart) tempChart.dispose()
})

/** 助手工具结果回写本页 */
watch(
  () => pageSink.token,
  async (token) => {
    if (!token || !pageSink.last) return
    const { result, tool, source } = pageSink.last
    if (source === 'performance') return
    if (!result || result.ok === false) return
    const detail = result.detail || {}
    const name = tool || result.tool || ''
    if (detail.navigate && detail.path) {
      if (source === 'assistant') return
      const { offerToolNavigate } = await import('@/composables/useSafeNavigate')
      await offerToolNavigate(router, route, {
        toolName: name,
        detail,
        onSamePage: async () => {
          await loadPerformanceData()
          await loadHistoryData()
        }
      })
      return
    }
    if (['inspect', 'refresh_device', 'refresh_offline', 'ping_check', 'probe_device'].includes(name)) {
      await loadPerformanceData()
      await loadHistoryData()
    }
  }
)
</script>

<style scoped>
.performance-view {
  min-height: 100%;
}

.perf-work-split {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: start;
  min-height: 420px;
}

.perf-work-main {
  min-width: 0;
}

.perf-work-side {
  /* OpsInlineShell */
}

@media (max-width: 1100px) {
  .perf-work-split {
    grid-template-columns: 1fr;
  }
}

.offline-alert {
  margin-bottom: 14px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 1200px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .metric-grid {
    grid-template-columns: 1fr;
  }
}

.metric-tile {
  background: #fff;
  border: 1px solid var(--nms-border-soft);
  border-radius: var(--nms-radius);
  box-shadow: var(--nms-shadow);
  padding: 14px 16px 16px;
  position: relative;
  overflow: hidden;
}

.metric-tile::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--nms-primary);
  opacity: 0.35;
}

.metric-tile.tone-ok::before { background: var(--nms-primary); opacity: 0.85; }
.metric-tile.tone-warn::before { background: var(--nms-warning); opacity: 0.9; }
.metric-tile.tone-danger::before { background: var(--nms-danger); opacity: 0.9; }

.metric-tile-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}

.metric-kicker {
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--nms-text-muted);
  margin-bottom: 2px;
}

.metric-label {
  font-size: 14px;
  font-weight: 650;
  color: var(--nms-text);
}

.metric-alert {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  font-weight: 600;
}
.metric-alert.warning {
  background: var(--nms-warning-soft);
  color: var(--nms-warning);
}
.metric-alert.danger {
  background: var(--nms-danger-soft);
  color: var(--nms-danger);
}

.metric-tile-main {
  display: flex;
  align-items: center;
  gap: 14px;
}

.ring {
  width: 78px;
  height: 78px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.ring-inner {
  width: 58px;
  height: 58px;
  border-radius: 50%;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: inset 0 0 0 1px var(--nms-border-soft);
}

.ring-value {
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--nms-text);
}

.metric-side {
  min-width: 0;
  flex: 1;
}

.metric-big {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}

.metric-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--nms-text-secondary);
}
.src-tag {
  margin-left: 6px;
  vertical-align: middle;
}

.spark {
  width: 100%;
  height: 28px;
  margin-top: 8px;
  display: block;
}

.spark-empty {
  margin-top: 10px;
  font-size: 11px;
  color: var(--nms-text-muted);
}

.temp-meter {
  width: 36px;
  display: flex;
  flex-direction: column;
  align-items: center;
  flex-shrink: 0;
}

.temp-track {
  width: 12px;
  height: 72px;
  border-radius: 8px;
  background: #eef2f7;
  overflow: hidden;
  display: flex;
  align-items: flex-end;
  border: 1px solid var(--nms-border-soft);
}

.temp-fill {
  width: 100%;
  border-radius: 8px 8px 0 0;
  transition: height 0.35s ease;
}

.temp-bulb {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  margin-top: -6px;
  box-shadow: 0 0 0 3px #fff;
}

.hw-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.hw-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--nms-bg-elevated);
  border: 1px solid var(--nms-border-soft);
}

.hw-icon {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  color: var(--nms-text-secondary);
  font-size: 18px;
}

.hw-meta {
  flex: 1;
  min-width: 0;
}

.hw-name {
  font-size: 12px;
  color: var(--nms-text-muted);
}

.hw-val {
  font-size: 14px;
  font-weight: 650;
  color: var(--nms-text);
}

.hw-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c0c8d4;
}

.hw-item.is-ok .hw-dot {
  background: var(--nms-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--nms-primary) 20%, transparent);
}
.hw-item.is-ok .hw-icon { color: var(--nms-primary); }

.hw-item.is-warn .hw-dot {
  background: var(--nms-warning);
  box-shadow: 0 0 0 3px rgba(212, 136, 6, 0.18);
}
.hw-item.is-warn .hw-icon { color: var(--nms-warning); }

.hw-item.is-bad .hw-dot {
  background: var(--nms-danger);
  box-shadow: 0 0 0 3px rgba(212, 56, 13, 0.18);
}
.hw-item.is-bad .hw-icon { color: var(--nms-danger); }

.section-block {
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid var(--nms-border-soft);
}

.section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.section-head h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  color: var(--nms-text);
}

.section-head p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--nms-text-muted);
}

.perf-table {
  --el-table-border-color: var(--nms-border-soft);
  font-size: 13px;
}

.port-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 500;
}

.port-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.port-indicator.active {
  background: var(--nms-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--nms-primary) 20%, transparent);
}
.port-indicator.inactive {
  background: #c0c8d4;
}

.traffic-cell .traffic-bytes {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.traffic-cell.in .traffic-bytes { color: var(--nms-primary); }
.traffic-cell.out .traffic-bytes { color: var(--nms-warning); }
.traffic-rate {
  margin-top: 2px;
  font-size: 12px;
  color: var(--nms-text-muted);
  font-variant-numeric: tabular-nums;
}

.err-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.muted {
  color: var(--nms-text-muted);
  font-size: 12px;
}

.alert-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.alert-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--nms-border-soft);
  border-radius: var(--nms-radius-sm);
  background: var(--nms-bg-elevated);
  flex-wrap: wrap;
}

.alert-main {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-width: 0;
}

.alert-msg {
  font-weight: 600;
  color: var(--nms-text);
  font-size: 13px;
}

.alert-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--nms-text-muted);
}

.alert-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.chart-card {
  border: 1px solid var(--nms-border-soft);
  border-radius: var(--nms-radius);
  background: #fff;
  padding: 12px 14px 8px;
  margin-bottom: 12px;
}

.chart-card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--nms-text-secondary);
  margin-bottom: 4px;
}

.chart-container {
  width: 100%;
  height: 300px;
}

/* 端口 up 状态跟随主题主色（覆盖全局成功绿） */
.theme-port.is-online {
  color: var(--nms-primary);
}
.theme-port.is-online .nms-status-dot {
  background: var(--nms-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--nms-primary) 20%, transparent);
}
</style>
