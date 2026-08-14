<template>
  <div class="smart-screen" v-loading="loading">
    <header class="ss-header">
      <div class="ss-brand">
        <h1>智能运维大屏</h1>
        <span class="ss-clock">{{ clock }}</span>
      </div>
      <div class="ss-badges">
        <div class="ss-score">
          <span class="ss-score-n">{{ health.networkScore ?? '-' }}</span>
          <span class="ss-score-l">健康分</span>
        </div>
        <el-tag :type="opsMode === 'unattended' ? 'warning' : 'success'" effect="dark" size="large">
          {{ opsMode === 'unattended' ? '无人值守' : '人工运维' }}
        </el-tag>
        <el-tag :type="llmEnabled ? 'success' : 'info'" effect="plain" size="large">
          LLM {{ llmEnabled ? '开' : '关' }}
        </el-tag>
        <el-button size="small" @click="$router.push('/aiops/overview')">返回智能中心</el-button>
        <el-button size="small" @click="$router.push('/aiops/workbench')">事件处置</el-button>
        <el-button size="small" @click="askScreenBrief">助手解读</el-button>
        <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
      </div>
    </header>

    <div class="ss-brief">{{ brief || '加载态势简报…' }}</div>

    <div class="ss-grid">
      <section class="ss-panel">
        <h3>事件流</h3>
        <div class="ss-wall">
          <div class="ss-metric">
            <span class="n">{{ data.pendingCount ?? 0 }}</span>
            <span class="l">待处理</span>
          </div>
          <div class="ss-metric">
            <span class="n">{{ data.inProgressCount ?? 0 }}</span>
            <span class="l">处理中</span>
          </div>
          <div class="ss-metric">
            <span class="n">{{ data.incidentTotal ?? 0 }}</span>
            <span class="l">代表事件</span>
          </div>
        </div>
        <div class="ss-list">
          <div v-for="row in incidents" :key="row.id" class="ss-row">
            <el-tag size="small" :type="severityType(row.severity)">{{ severityText(row.severity) }}</el-tag>
            <span class="ss-row-title">{{ row.title }}</span>
            <span class="ss-row-meta">{{ row.deviceName || '-' }}</span>
          </div>
          <el-empty v-if="!incidents.length" description="暂无代表事件" :image-size="48" />
        </div>
      </section>

      <section class="ss-panel ss-center">
        <h3>收敛与根因</h3>
        <div class="ss-wall dense">
          <div class="ss-metric">
            <span class="n">{{ corr.representativeCount ?? '-' }}</span>
            <span class="l">代表事件</span>
          </div>
          <div class="ss-metric">
            <span class="n">{{ corr.stormGroups ?? 0 }}</span>
            <span class="l">风暴组</span>
          </div>
          <div class="ss-metric">
            <span class="n">{{ corr.suppressedCount ?? 0 }}</span>
            <span class="l">已抑制</span>
          </div>
          <div class="ss-metric">
            <span class="n">{{ corr.secondaryMarked ?? 0 }}</span>
            <span class="l">连带</span>
          </div>
        </div>
        <h4 class="ss-sub">可疑根因 Top5</h4>
        <div class="ss-list">
          <div v-for="c in rca" :key="c.deviceId" class="ss-row rca">
            <span class="ss-score-mini">{{ c.score }}</span>
            <div>
              <div class="ss-row-title">{{ c.name }}</div>
              <div class="ss-row-meta">{{ c.reason }}</div>
            </div>
          </div>
          <el-empty v-if="!rca.length" description="暂无可疑根因" :image-size="48" />
        </div>
      </section>

      <section class="ss-panel">
        <h3>基线与容量</h3>
        <div class="ss-list">
          <div v-for="(a, i) in anomalies" :key="i" class="ss-row">
            <el-tag size="small" type="warning">基线</el-tag>
            <span class="ss-row-title">{{ a.deviceName || a.name || a.metric || '异常' }}</span>
            <span class="ss-row-meta">{{ a.message || a.detail || formatAnomaly(a) }}</span>
          </div>
          <div v-for="(t, i) in trends" :key="'t' + i" class="ss-row">
            <el-tag size="small" type="info">趋势</el-tag>
            <span class="ss-row-title">{{ t.deviceName || t.name || '容量' }}</span>
            <span class="ss-row-meta">{{ t.message || '-' }}</span>
          </div>
          <el-empty
            v-if="!anomalies.length && !trends.length"
            description="暂无基线异常或容量趋势"
            :image-size="48"
          />
        </div>
        <div v-if="evidence && Object.keys(evidence).length" class="ss-evidence">
          <div class="ss-evidence-title">最近巡检</div>
          <div class="ss-evidence-body">
            {{ evidence.engineLabel || '规则引擎' }} ·
            代表 {{ evidence.representativeCount ?? '-' }} ·
            基线异常 {{ evidence.baselineAnomalies ?? '-' }}
          </div>
        </div>
      </section>
    </div>

    <footer class="ss-footer">
      <h3>最近处置</h3>
      <div class="ss-actions">
        <div v-for="(a, i) in recentActions" :key="i" class="ss-action">
          <el-tag size="small" :type="a.source === 'unattended' ? 'warning' : 'info'">
            {{ a.source === 'unattended' ? '自动' : '人工' }}
          </el-tag>
          <span>{{ a.message }}</span>
          <span class="ss-action-at">{{ formatTime(a.at) }}</span>
        </div>
        <span v-if="!recentActions.length" class="ss-muted">暂无自动/人工处置记录</span>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { aiopsApi } from '@/api/device'
import { askOpsAssistant } from '@/composables/askOpsAssistant'

const loading = ref(false)
const data = ref({})
const clock = ref('')
let timer = null
let clockTimer = null

const health = computed(() => data.value.health || {})
const opsMode = computed(() => data.value.llmOpsMode === 'unattended' ? 'unattended' : 'manual')
const llmEnabled = computed(() => !!data.value.llmEnabled)
const brief = computed(() => data.value.brief || '')
const incidents = computed(() => Array.isArray(data.value.incidents) ? data.value.incidents : [])
const corr = computed(() => data.value.correlation || {})
const rca = computed(() => Array.isArray(data.value.rcaCandidates) ? data.value.rcaCandidates : [])
const anomalies = computed(() => Array.isArray(data.value.recentAnomalies) ? data.value.recentAnomalies : [])
const trends = computed(() => Array.isArray(data.value.capacityTrends) ? data.value.capacityTrends : [])
const evidence = computed(() => data.value.lastInspectEvidence || {})
const recentActions = computed(() => Array.isArray(data.value.recentActions) ? data.value.recentActions : [])

function severityType(s) {
  const map = { CRITICAL: 'danger', MAJOR: 'warning', WARNING: 'warning', MINOR: 'info', INFO: '' }
  return map[s] || 'info'
}
function severityText(s) {
  const map = { CRITICAL: '紧急', MAJOR: '重要', WARNING: '警告', MINOR: '次要', INFO: '提示' }
  return map[s] || s || '-'
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
function formatTime(v) {
  if (!v) return ''
  return String(v).replace('T', ' ').slice(0, 19)
}
function formatAnomaly(a) {
  if (!a) return '-'
  const parts = [a.metric, a.value != null ? `值 ${a.value}` : null, a.mean != null ? `均值 ${a.mean}` : null]
  return parts.filter(Boolean).join(' · ') || '-'
}

async function refresh() {
  loading.value = true
  try {
    data.value = (await aiopsApi.getScreenBrief()) || {}
  } catch {
    data.value = {}
  } finally {
    loading.value = false
  }
}

function tickClock() {
  clock.value = new Date().toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  tickClock()
  clockTimer = setInterval(tickClock, 1000)
  refresh()
  timer = setInterval(refresh, 30000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<style scoped>
.smart-screen {
  min-height: calc(100vh - 56px);
  padding: 16px 20px 24px;
  background: linear-gradient(160deg, #0b1220 0%, #132038 45%, #0e1a2e 100%);
  color: #e8eef7;
}
.ss-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.ss-brand h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.04em;
}
.ss-clock {
  display: block;
  margin-top: 4px;
  font-size: 13px;
  color: #8fa3bf;
}
.ss-badges {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.ss-score {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 4px 12px;
  border-radius: 8px;
  background: rgba(64, 158, 255, 0.12);
  border: 1px solid rgba(64, 158, 255, 0.35);
}
.ss-score-n {
  font-size: 22px;
  font-weight: 700;
  color: #79bbff;
  line-height: 1.1;
}
.ss-score-l { font-size: 11px; color: #8fa3bf; }
.ss-brief {
  margin-bottom: 14px;
  padding: 10px 14px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 14px;
  line-height: 1.5;
  color: #c5d4e8;
}
.ss-grid {
  display: grid;
  grid-template-columns: 1.1fr 1.2fr 1fr;
  gap: 12px;
  min-height: 420px;
}
@media (max-width: 1100px) {
  .ss-grid { grid-template-columns: 1fr; }
}
.ss-panel {
  background: rgba(15, 28, 48, 0.85);
  border: 1px solid rgba(120, 160, 210, 0.18);
  border-radius: 10px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.ss-panel h3 {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: #9ec5ff;
}
.ss-sub {
  margin: 12px 0 8px;
  font-size: 12px;
  color: #8fa3bf;
  font-weight: 600;
}
.ss-wall {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.ss-wall.dense .ss-metric { min-width: 72px; }
.ss-metric {
  flex: 1;
  min-width: 88px;
  text-align: center;
  padding: 10px 6px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
}
.ss-metric .n {
  display: block;
  font-size: 26px;
  font-weight: 700;
  color: #fff;
  line-height: 1.15;
}
.ss-metric .l {
  font-size: 11px;
  color: #8fa3bf;
}
.ss-list {
  flex: 1;
  overflow: auto;
  max-height: 360px;
}
.ss-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  font-size: 13px;
}
.ss-row.rca { align-items: center; }
.ss-row-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ss-row-meta {
  font-size: 12px;
  color: #8fa3bf;
  max-width: 45%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ss-score-mini {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(245, 108, 108, 0.2);
  color: #f89898;
  font-weight: 700;
  font-size: 13px;
  flex-shrink: 0;
}
.ss-evidence {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 12px;
  color: #8fa3bf;
}
.ss-evidence-title { font-weight: 600; color: #9ec5ff; margin-bottom: 4px; }
.ss-footer {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 10px;
  background: rgba(15, 28, 48, 0.85);
  border: 1px solid rgba(120, 160, 210, 0.18);
}
.ss-footer h3 {
  margin: 0 0 8px;
  font-size: 14px;
  color: #9ec5ff;
}
.ss-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 120px;
  overflow: auto;
}
.ss-action {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #c5d4e8;
}
.ss-action-at { margin-left: auto; color: #6b7f99; }
.ss-muted { font-size: 12px; color: #6b7f99; }
</style>
