<template>
  <div class="aiops-overview" v-loading="loading">
    <div class="ov-kpi">
      <div class="kpi">
        <span class="n" :class="health.level">{{ health.networkScore ?? '-' }}</span>
        <span class="l">健康分</span>
      </div>
      <div class="kpi">
        <span class="n">{{ pendingCount }}</span>
        <span class="l">待处理事件</span>
      </div>
      <div class="kpi">
        <span class="n">{{ (rca.candidates || []).length }}</span>
        <span class="l">可疑根因</span>
      </div>
      <div class="kpi">
        <span class="n">{{ corr.stormGroups ?? 0 }}</span>
        <span class="l">风暴组</span>
      </div>
      <div class="kpi grow">
        <div class="story">{{ story.headline || '运维态势正常' }}</div>
        <div class="story-d">{{ story.detail }}</div>
        <div class="ov-actions">
          <el-button size="small" :loading="exporting" @click="exportHealthReport">导出健康报表</el-button>
        </div>
      </div>
    </div>

    <div v-if="reportCardVisible" class="evidence-card report-card">
      <div class="ev-head">
        <span>最近巡检报告</span>
        <el-tag size="small" type="info">{{ evidence.engineLabel || '规则引擎' }}</el-tag>
      </div>
      <p v-if="localReport?.summary" class="rpt-sum">{{ localReport.summary }}</p>
      <div class="ev-metrics">
        <span>代表事件 {{ evidence.representativeCount ?? '-' }}</span>
        <span>风暴 {{ evidence.stormGroups ?? '-' }}</span>
        <span>抑制 {{ evidence.suppressedCount ?? '-' }}</span>
        <span>基线异常 {{ evidence.baselineAnomalies ?? '-' }}</span>
      </div>
      <ol v-if="reportLines.length" class="rpt-lines">
        <li v-for="(line, i) in reportLines" :key="i">{{ line }}</li>
      </ol>
    </div>

    <el-row :gutter="12">
      <el-col :xs="24" :md="12">
        <el-card shadow="never">
          <template #header>
            <div class="card-h">
              <span>待处理事件</span>
              <el-button link type="primary" @click="$router.push('/aiops/workbench')">去处置</el-button>
            </div>
          </template>
          <div v-for="row in pending.slice(0, 8)" :key="row.id" class="inc-row" @click="goWorkbench(row)">
            <el-tag size="small" :type="sevType(row.severity)">{{ sevText(row.severity) }}</el-tag>
            <span class="t">{{ row.title }}</span>
            <span class="m">{{ row.deviceName }}</span>
          </div>
          <el-empty v-if="!pending.length" description="暂无待处理" :image-size="48" />
        </el-card>
      </el-col>
      <el-col :xs="24" :md="12">
        <el-card shadow="never">
          <template #header>
            <div class="card-h">
              <span>洞察</span>
              <el-button link type="primary" @click="$router.push('/aiops/automation')">自动化运营</el-button>
            </div>
          </template>
          <el-tag
            v-for="ins in insights.slice(0, 6)"
            :key="ins.code + ins.title"
            class="ins-tag"
            size="small"
            :type="ins.level === 'danger' ? 'danger' : (ins.level === 'warning' ? 'warning' : 'info')"
            effect="plain"
            @click="$router.push(ins.link || '/aiops/workbench')"
          >{{ ins.title }}</el-tag>
          <el-empty v-if="!insights.length" description="暂无洞察" :image-size="48" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, inject, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { aiopsApi } from '@/api/device'
import { loadInspectReport } from '@/composables/inspectReportStore'

const router = useRouter()
const loading = ref(false)
const exporting = ref(false)
const overview = ref({})
const localReport = ref(loadInspectReport())
const refreshToken = inject('aiopsOverviewRefreshToken', ref(0))
const hubReport = inject('aiopsLastInspectReport', null)

const health = computed(() => overview.value.health || {})
const story = computed(() => overview.value.story || {})
const rca = computed(() => overview.value.rca || {})
const corr = computed(() => overview.value.correlation || {})
const evidence = computed(() =>
  localReport.value?.evidence
  || overview.value.lastInspectEvidence
  || {}
)
const reportLines = computed(() => {
  if (Array.isArray(localReport.value?.lines) && localReport.value.lines.length) {
    return localReport.value.lines
  }
  return []
})
const reportCardVisible = computed(() =>
  reportLines.value.length > 0 || Object.keys(evidence.value || {}).length > 0
)
const insights = computed(() => Array.isArray(overview.value.insights) ? overview.value.insights : [])
const incidents = computed(() => Array.isArray(overview.value.incidents) ? overview.value.incidents : [])
const pending = computed(() => incidents.value.filter((r) => r.phase === 'pending' || r.status === 'ACTIVE'))
const pendingCount = computed(() => pending.value.length)

function sevType(s) {
  return ({ CRITICAL: 'danger', MAJOR: 'warning', WARNING: 'warning', MINOR: 'info' })[s] || 'info'
}
function sevText(s) {
  return ({ CRITICAL: '严重', MAJOR: '重要', WARNING: '警告', MINOR: '次要', INFO: '提示' })[s] || s || '-'
}
function goWorkbench(row) {
  router.push({ path: '/aiops/workbench', query: row?.id ? { alarmId: row.id } : {} })
}

async function exportHealthReport() {
  exporting.value = true
  try {
    const data = await aiopsApi.getHealthReport()
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `nms-health-report-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.json`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('健康报表已导出')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

async function load() {
  loading.value = true
  try {
    overview.value = (await aiopsApi.getOverview()) || {}
    localReport.value = (hubReport && hubReport.value) || loadInspectReport()
  } catch {
    overview.value = {}
  } finally {
    loading.value = false
  }
}

watch(refreshToken, () => {
  localReport.value = (hubReport && hubReport.value) || loadInspectReport()
  load()
})

onMounted(load)
</script>

<style scoped>
.ov-kpi { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 12px; }
.kpi {
  min-width: 88px; padding: 10px 12px; border-radius: 8px;
  background: var(--el-fill-color-light); text-align: center;
}
.kpi.grow { flex: 1; text-align: left; min-width: 200px; }
.kpi .n { display: block; font-size: 22px; font-weight: 700; }
.kpi .n.warning, .kpi .n.fair { color: #e6a23c; }
.kpi .n.danger, .kpi .n.poor { color: #f56c6c; }
.kpi .l { font-size: 12px; color: var(--el-text-color-secondary); }
.story { font-weight: 600; font-size: 14px; }
.story-d { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 4px; }
.ov-actions { margin-top: 8px; }
.evidence-card {
  margin-bottom: 12px; padding: 10px 12px; border-radius: 8px;
  background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-lighter);
}
.ev-head { display: flex; justify-content: space-between; font-weight: 600; margin-bottom: 6px; }
.ev-metrics { display: flex; flex-wrap: wrap; gap: 12px; font-size: 12px; }
.rpt-sum { margin: 0 0 8px; font-size: 13px; line-height: 1.5; }
.rpt-lines {
  margin: 10px 0 0; padding-left: 18px; font-size: 12px; line-height: 1.55;
  color: var(--el-text-color-regular);
}
.rpt-lines li { margin-bottom: 4px; }
.card-h { display: flex; justify-content: space-between; align-items: center; }
.inc-row {
  display: flex; gap: 8px; align-items: center; padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-extra-light); cursor: pointer; font-size: 13px;
}
.inc-row .t { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.inc-row .m { color: var(--el-text-color-secondary); font-size: 12px; }
.ins-tag { margin: 0 8px 8px 0; cursor: pointer; }
</style>
