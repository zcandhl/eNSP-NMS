<template>
  <div class="aiops-auto" v-loading="loading">
    <div class="auto-bar">
      <div class="stats">
        <span>近24h 运行 <b>{{ status.last24hTotal ?? 0 }}</b></span>
        <span>成功率 <b>{{ status.last24hSuccessRate != null ? status.last24hSuccessRate + '%' : '-' }}</b></span>
        <span>LLM <b>{{ status.last24hLlm ?? 0 }}</b></span>
        <span>规则回退 <b>{{ status.last24hRules ?? 0 }}</b></span>
        <el-tag size="small" :type="status.paused ? 'danger' : 'success'">
          {{ status.paused ? '已暂停' : '运行中' }}
        </el-tag>
        <el-tag v-if="status.circuitOpen" size="small" type="warning">熔断中</el-tag>
      </div>
      <div class="actions">
        <el-button size="small" :type="status.paused ? 'success' : 'danger'" @click="togglePause">
          {{ status.paused ? '恢复自动' : '暂停自动' }}
        </el-button>
        <el-button size="small" type="primary" :loading="running" @click="runCycle">跑一轮</el-button>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-table :data="runs" size="small" stripe empty-text="暂无运行记录">
      <el-table-column prop="startedAt" label="时间" width="160">
        <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
      </el-table-column>
      <el-table-column prop="planSource" label="来源" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="sourceType(row.planSource)">{{ sourceText(row.planSource) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="结果" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'success' ? 'success' : (row.status === 'failed' ? 'danger' : 'info')">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="alarmId" label="告警" width="80" />
      <el-table-column prop="stepsRan" label="步数" width="64" />
      <el-table-column prop="reason" label="理由" min-width="200" show-overflow-tooltip />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
          <el-button
            v-if="row.alarmId"
            link
            type="primary"
            @click="$router.push({ path: '/aiops/workbench', query: { alarmId: row.alarmId, deviceId: row.deviceId } })"
          >处置</el-button>
          <el-button link type="warning" @click="retry(row)">重试</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        layout="prev, pager, next"
        :total="total"
        @current-change="load"
      />
    </div>

    <el-drawer v-model="detailVisible" title="运行详情" size="420px">
      <p><b>来源</b> {{ sourceText(detail?.run?.planSource) }}</p>
      <p><b>理由</b> {{ detail?.run?.reason }}</p>
      <div v-for="s in (detail?.steps || [])" :key="s.id" class="step">
        <el-tag size="small" :type="s.ok ? 'success' : 'danger'">{{ s.seq }}. {{ s.tool }}</el-tag>
        <span>{{ s.message }}</span>
        <span class="ms">{{ s.elapsedMs }}ms</span>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, inject } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiopsApi } from '@/api/device'

const hubRefresh = inject('aiopsHubRefresh', null)
const loading = ref(false)
const running = ref(false)
const status = ref({})
const runs = ref([])
const page = ref(1)
const size = 20
const total = ref(0)
const detailVisible = ref(false)
const detail = ref(null)

function formatTime(v) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}
function sourceType(s) {
  return ({ llm: 'success', rules: 'info', breaker: 'warning' })[s] || 'info'
}
function sourceText(s) {
  return ({ llm: 'LLM', rules: '规则', breaker: '熔断/规则' })[s] || s || '-'
}

async function load() {
  loading.value = true
  try {
    const [st, list] = await Promise.all([
      aiopsApi.getUnattendedStatus(),
      aiopsApi.listUnattendedRuns({ page: page.value - 1, size })
    ])
    status.value = st || {}
    runs.value = Array.isArray(list?.content) ? list.content : []
    total.value = Number(list?.totalElements) || 0
  } catch {
    runs.value = []
  } finally {
    loading.value = false
  }
}

async function togglePause() {
  const next = !status.value.paused
  try {
    if (next) {
      await ElMessageBox.confirm('暂停后将不再自动处置新事件，确认？', '暂停无人值守')
    }
    const res = await aiopsApi.pauseUnattended(next)
    ElMessage.success(res?.message || '已更新')
    await load()
    hubRefresh?.()
  } catch { /* cancel */ }
}

async function runCycle() {
  running.value = true
  try {
    const res = await aiopsApi.runUnattended({})
    ElMessage.success(res?.message || '已触发')
    await load()
    hubRefresh?.()
  } catch {
    ElMessage.error('执行失败')
  } finally {
    running.value = false
  }
}

async function showDetail(row) {
  try {
    detail.value = await aiopsApi.getUnattendedRun(row.id)
    detailVisible.value = true
  } catch {
    ElMessage.error('加载详情失败')
  }
}

async function retry(row) {
  try {
    await ElMessageBox.confirm('将按当前策略重新规划并执行安全工具', '重试')
    const res = await aiopsApi.retryUnattendedRun(row.id)
    ElMessage.success(res?.message || '已重试')
    await load()
  } catch { /* cancel */ }
}

onMounted(load)
</script>

<style scoped>
.auto-bar {
  display: flex; justify-content: space-between; align-items: center;
  flex-wrap: wrap; gap: 10px; margin-bottom: 12px;
}
.stats { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; font-size: 13px; }
.stats b { font-size: 15px; }
.actions { display: flex; gap: 8px; }
.pager { margin-top: 12px; display: flex; justify-content: flex-end; }
.step {
  display: flex; gap: 8px; align-items: flex-start; padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-extra-light); font-size: 13px;
}
.step .ms { margin-left: auto; color: var(--el-text-color-secondary); font-size: 12px; }
</style>
