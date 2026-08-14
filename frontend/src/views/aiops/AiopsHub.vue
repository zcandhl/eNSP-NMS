<template>
  <div class="nms-page aiops-hub">
    <div class="nms-page-header">
      <div class="nms-page-title-block">
        <h1 class="nms-page-title">智能运维中心</h1>
        <p class="nms-page-subtitle">规则感知 · LLM 编排 · 白名单执行 · 可审计</p>
      </div>
      <div class="nms-page-actions">
        <el-tag :type="opsMode === 'unattended' ? 'warning' : 'success'" effect="plain">
          {{ opsMode === 'unattended' ? '无人值守' : '人工运维' }}
        </el-tag>
        <el-tag v-if="paused" type="danger" effect="plain">已暂停</el-tag>
        <el-tag v-if="circuitOpen" type="warning" effect="plain">LLM 熔断</el-tag>
        <el-button size="small" @click="$router.push('/aiops/screen')">智能大屏</el-button>
        <el-button size="small" type="primary" :loading="inspecting" @click="runInspect">智能巡检</el-button>
        <el-button size="small" :disabled="!lastReport" @click="reportOpen = true">巡检报告</el-button>
        <el-button size="small" @click="askPatrolAnalyze">解读报告</el-button>
      </div>
    </div>
    <div class="nms-panel hub-panel">
      <el-menu
        :default-active="active"
        mode="horizontal"
        router
        class="hub-nav"
      >
        <el-menu-item index="/aiops/overview">态势总览</el-menu-item>
        <el-menu-item index="/aiops/workbench">事件处置</el-menu-item>
        <el-menu-item index="/aiops/automation">自动化运营</el-menu-item>
        <el-menu-item index="/aiops/policy">策略护栏</el-menu-item>
      </el-menu>
      <div class="hub-body nms-panel-body">
        <router-view />
      </div>
    </div>

    <!-- 巡检报告留在智能运维本页，不灌进运维助手 -->
    <el-drawer
      v-model="reportOpen"
      title="智能巡检报告"
      direction="rtl"
      size="420px"
      append-to-body
    >
      <template v-if="lastReport">
        <p class="rpt-meta">
          {{ lastReport.generatedAt ? formatTime(lastReport.generatedAt) : '' }}
          <el-tag size="small" type="success" effect="plain">本页报告</el-tag>
        </p>
        <p v-if="lastReport.summary" class="rpt-summary">{{ lastReport.summary }}</p>
        <div v-if="lastReport.evidence && Object.keys(lastReport.evidence).length" class="rpt-metrics">
          <span>代表事件 {{ lastReport.evidence.representativeCount ?? '-' }}</span>
          <span>风暴 {{ lastReport.evidence.stormGroups ?? '-' }}</span>
          <span>抑制 {{ lastReport.evidence.suppressedCount ?? '-' }}</span>
          <span>基线异常 {{ lastReport.evidence.baselineAnomalies ?? '-' }}</span>
        </div>
        <ol v-if="lastReport.lines?.length" class="rpt-lines">
          <li v-for="(line, i) in lastReport.lines" :key="i">{{ line }}</li>
        </ol>
        <el-empty v-else description="暂无明细行" :image-size="48" />
        <div class="rpt-actions">
          <el-button type="primary" @click="goWorkbenchFromReport">去事件处置</el-button>
          <el-button @click="askPatrolAnalyze">用助手解读</el-button>
        </div>
      </template>
      <el-empty v-else description="尚未执行巡检" :image-size="64" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { aiopsApi } from '@/api/device'
import { askOpsAssistant } from '@/composables/askOpsAssistant'
import { saveInspectReport, loadInspectReport } from '@/composables/inspectReportStore'

const route = useRoute()
const router = useRouter()
const active = computed(() => {
  const p = route.path
  if (p.startsWith('/aiops/workbench')) return '/aiops/workbench'
  if (p.startsWith('/aiops/automation')) return '/aiops/automation'
  if (p.startsWith('/aiops/policy')) return '/aiops/policy'
  return '/aiops/overview'
})

const opsMode = ref('manual')
const paused = ref(false)
const circuitOpen = ref(false)
const inspecting = ref(false)
const reportOpen = ref(false)
const lastReport = ref(loadInspectReport())
const overviewRefreshToken = ref(0)

async function refreshHubStatus() {
  try {
    const [pol, st] = await Promise.all([
      aiopsApi.getPolicy().catch(() => ({})),
      aiopsApi.getUnattendedStatus().catch(() => ({}))
    ])
    opsMode.value = pol?.llmOpsMode === 'unattended' ? 'unattended' : 'manual'
    paused.value = !!(pol?.unattendedPaused ?? st?.paused)
    circuitOpen.value = !!st?.circuitOpen
  } catch { /* ignore */ }
}

function formatTime(v) {
  if (!v) return ''
  return String(v).replace('T', ' ').slice(0, 19)
}

async function runInspect() {
  inspecting.value = true
  try {
    const res = await aiopsApi.inspect()
    lastReport.value = saveInspectReport(res)
    reportOpen.value = true
    ElMessage.success(res?.summary || '巡检完成')
    await refreshHubStatus()
    overviewRefreshToken.value = Date.now()
    // 巡检结果在本页报告；不自动打开运维助手
    if (!route.path.startsWith('/aiops/overview') && !route.path.startsWith('/aiops/workbench')) {
      router.push('/aiops/overview')
    }
  } catch {
    ElMessage.error('巡检失败')
  } finally {
    inspecting.value = false
  }
}

function goWorkbenchFromReport() {
  reportOpen.value = false
  router.push('/aiops/workbench')
}

/** 用户主动要求时，才把报告摘要交给助手解读 */
function askPatrolAnalyze() {
  const rpt = lastReport.value
  const brief = rpt?.summary
    || (Array.isArray(rpt?.lines) ? rpt.lines.slice(0, 4).join('；') : '')
    || '尚无本页巡检报告，请先点「智能巡检」。'
  askOpsAssistant({
    source: 'patrol',
    primaryToolLabel: '网络态势',
    recommendedTools: [
      { name: 'get_network_overview', label: '网络态势', needConfirm: false, args: {} },
      { name: 'inspect', label: '重算关联', needConfirm: false, args: {} }
    ],
    autoAsk: true,
    autoAskQuestion: `根据本页巡检报告解读优先风险：${String(brief).slice(0, 280)}`
  })
}

provide('aiopsHubRefresh', refreshHubStatus)
provide('aiopsOverviewRefreshToken', overviewRefreshToken)
provide('aiopsLastInspectReport', lastReport)
onMounted(refreshHubStatus)

defineExpose({ refreshHubStatus })
</script>

<style scoped>
.aiops-hub { display: flex; flex-direction: column; gap: 16px; min-height: calc(100vh - 100px); }
.hub-panel { display: flex; flex-direction: column; flex: 1; min-height: 0; }
.hub-nav {
  border-bottom: 1px solid var(--nms-border-soft) !important;
  padding: 0 8px;
  background: var(--nms-bg-elevated);
}
.hub-body { flex: 1; min-height: 0; overflow: auto; }
.rpt-meta {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 12px; color: var(--el-text-color-secondary); margin: 0 0 10px;
}
.rpt-summary { font-size: 14px; line-height: 1.5; margin: 0 0 12px; }
.rpt-metrics {
  display: flex; flex-wrap: wrap; gap: 10px; font-size: 12px;
  margin-bottom: 14px; padding: 8px 10px; border-radius: 8px;
  background: var(--el-fill-color-light);
}
.rpt-lines {
  margin: 0; padding-left: 18px; font-size: 13px; line-height: 1.65;
  color: var(--el-text-color-regular);
}
.rpt-lines li { margin-bottom: 6px; }
.rpt-actions { margin-top: 20px; display: flex; gap: 8px; flex-wrap: wrap; }
</style>
