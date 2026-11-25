/**
 * @file    AcceptancePlanDesign.vue
 * @module  视图页/验收
 * @desc    验收计划设计与检查项编排页。
 *
 * @author  ZC
 * @date    2026-11-22 * @version 1.0.0
 * @requires @element-plus/icons-vue, element-plus, pinia, vue, vue-router, @/api/acceptance, @/stores/auth, @/stores/project
 */

<template>
  <div class="nms-page plan-design" v-loading="loading">
    <div class="nms-page-header">
      <div class="nms-page-title-block">
        <h1 class="nms-page-title">方案设计</h1>
        <p class="nms-page-subtitle">{{ subtitle }}</p>
      </div>
      <div class="nms-page-actions">
        <el-select
          v-model="projectId"
          class="pd-sel"
          placeholder="选择工程"
          :disabled="!projects.length"
          @change="onProjectChange"
        >
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-select
          v-model="planId"
          class="pd-sel pd-sel-plan"
          placeholder="选择方案"
          :disabled="!plans.length"
          @change="pickPlan"
        >
          <el-option
            v-for="p in plans"
            :key="p.id"
            :label="planLabel(p)"
            :value="p.id"
          />
        </el-select>
        <el-button @click="openCreate">新建方案</el-button>
        <el-button :disabled="!planId" @click="doDeletePlan">删除方案</el-button>
        <el-button type="primary" :loading="saving" :disabled="!planDetail" @click="doSave()">
          保存方案
        </el-button>
      </div>
    </div>

    <div class="pd-grid">
      <aside class="nms-panel pd-plans">
        <div class="pd-plans-head">
          <span class="pd-plans-title">方案列表</span>
          <span class="pd-plans-count">{{ plans.length }}</span>
        </div>
        <div class="pd-plans-body">
          <div
            v-for="p in plans"
            :key="p.id"
            class="pd-plan"
            :class="{ active: p.id === planId }"
            @click="pickPlan(p.id)"
          >
            <div class="pd-plan-line">
              <span class="pd-plan-name" :title="p.name">{{ p.name }}</span>
              <span v-if="!nameHasVer(p)" class="pd-plan-ver">v{{ p.version }}</span>
            </div>
            <div class="pd-plan-meta">
              {{ (p.checks || []).length }} 项检查 · 启用 {{ enabledOf(p) }}
            </div>
          </div>
          <div v-if="!plans.length" class="pd-empty">
            当前工程还没有方案，新建一份即可开始
          </div>
        </div>
        <div class="pd-plans-foot">
          <el-button text @click="openCreate">+ 新建方案</el-button>
        </div>
      </aside>

      <section class="nms-panel pd-main">
        <div class="nms-panel-toolbar">
          <el-input
            v-model="keyword"
            class="pd-search"
            placeholder="搜索编号或标题"
            clearable
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-button :disabled="!selection.length" @click="setEnabled(true)">启用所选</el-button>
          <el-button :disabled="!selection.length" @click="setEnabled(false)">停用所选</el-button>
          <el-button @click="addCheck">+ 新增检查项</el-button>
          <span v-if="dirty" class="pd-dirty">有未保存的修改</span>
        </div>

        <el-table
          v-if="filteredChecks.length"
          :data="filteredChecks"
          class="pd-table"
          row-key="id"
          :row-style="{ height: '52px' }"
          @selection-change="(rows) => (selection = rows)"
          @row-click="openDrawer"
        >
          <el-table-column type="selection" width="46" />
          <el-table-column label="编号" width="90">
            <template #default="{ row }">
              <span class="pd-code">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column label="检查项" min-width="180">
            <template #default="{ row }">
              <div class="pd-title-cell">
                <span class="pd-title" :class="{ off: !row.enabled }">{{ row.title }}</span>
                <el-tag v-if="row.needConfirm" size="small" type="warning" effect="plain">
                  需确认
                </el-tag>
                <el-tag v-if="row.manual" size="small" type="info" effect="plain">手工判定</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="类型" min-width="130">
            <template #default="{ row }">
              <span class="pd-type">{{ typeLabel(row.type) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="责任人" min-width="110">
            <template #default="{ row }">
              <span :class="{ 'pd-mine': row.owner === auth.user?.username }">
                {{ memberName(row.owner) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="级别" min-width="72">
            <template #default="{ row }">
              <span class="pd-sev" :class="`sev-${row.severity}`">
                {{ severityLabel(row.severity) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="64" align="center">
            <template #default="{ row }">
              <span @click.stop>
                <el-switch v-model="row.enabled" size="small" />
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="122" align="center" fixed="right">
            <template #default="{ row, $index }">
              <span class="pd-ops" @click.stop>
                <el-tooltip content="上移" placement="top">
                  <button
                    type="button"
                    class="op-btn"
                    :disabled="$index === 0"
                    @click="move(row, -1)"
                  >
                    <el-icon><ArrowUp /></el-icon>
                  </button>
                </el-tooltip>
                <el-tooltip content="下移" placement="top">
                  <button
                    type="button"
                    class="op-btn"
                    :disabled="$index === filteredChecks.length - 1"
                    @click="move(row, 1)"
                  >
                    <el-icon><ArrowDown /></el-icon>
                  </button>
                </el-tooltip>
                <el-tooltip content="删除" placement="top">
                  <button type="button" class="op-btn is-danger" @click="removeCheck(row)">
                    <el-icon><Delete /></el-icon>
                  </button>
                </el-tooltip>
              </span>
            </template>
          </el-table-column>
        </el-table>

        <div v-else class="pd-table-empty">
          {{ checks.length ? '没有匹配的检查项' : '方案里还没有检查项，点「+ 新增检查项」开始' }}
        </div>

        <div class="nms-panel-footer pd-footer">
          <span class="pd-sum">
            共 {{ checks.length }} 项 · 启用 {{ enabledCount }} 项
            <em v-if="planDetail">· {{ planLabel(planDetail) }}</em>
          </span>
          
          <span v-if="dirty" class="pd-save-state is-dirty">有未保存的修改</span>
          <span v-else-if="savedAt" class="pd-save-state">已于 {{ savedAtText }} 保存</span>
          <span v-else class="pd-save-state">尚未修改</span>
        </div>
      </section>
    </div>

    <el-drawer
      v-model="drawerVisible"
      :size="520"
      :title="selectedCheck ? `检查项 ${selectedCheck.code}` : '检查项'"
      destroy-on-close
    >
      <CheckItemForm
        v-if="selectedCheck"
        :check="selectedCheck"
        :check-types="checkTypes"
        :members="members"
        :members-loading="membersLoading"
        in-drawer
      />
      <template #footer>
        <div class="pd-drawer-foot">
          <el-button :loading="dryRunning" @click="doDryRun">试运行该项</el-button>
          <div class="pd-drawer-foot-right">
            <el-button @click="drawerVisible = false">关闭</el-button>
            
            <el-button type="primary" :loading="saving" :disabled="!planDetail" @click="doSave()">
              保存
            </el-button>
          </div>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="createVisible" title="新建验收方案" width="440px">
      <el-form label-position="top">
        <el-form-item label="方案名称">
          <el-input v-model="createForm.name" placeholder="例如：三期工程综合实训大楼验收" />
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="createForm.version" class="pd-ver-input" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="createForm.template">同时生成标准六项检查模板</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rawVisible" title="试运行结果（不影响正式批次）" width="640px">
      <div v-if="rawItem" class="pd-raw-head">
        <el-tag :type="rawItem.status === 'PASS' ? 'success' : 'danger'" effect="dark" size="small">
          {{ rawItem.status }}
        </el-tag>
        <span class="pd-raw-title">{{ rawItem.code }} · {{ rawItem.title }}</span>
        <span class="pd-raw-time">{{ rawItem.durationMs }} ms</span>
      </div>
      <p v-if="rawItem?.message" class="pd-raw-msg">{{ rawItem.message }}</p>
      <pre class="pd-raw">{{ rawText }}</pre>
      <template #footer>
        <el-button @click="rawVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, ArrowUp, Delete, Search } from '@element-plus/icons-vue'
import CheckItemForm from './components/CheckItemForm.vue'
import { acceptanceApi, mockMode } from '@/api/acceptance'
import { useProjectStore } from '@/stores/project'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()

const loading = ref(false)
const saving = ref(false)
const dryRunning = ref(false)
const creating = ref(false)

const savedAt = ref(null)
const savedAtText = computed(() => {
  const d = savedAt.value
  if (!d) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
})

const projectStore = useProjectStore()
const { projects } = storeToRefs(projectStore)
const plans = ref([])
const planDetail = ref(null)
const checkTypes = ref([])
const members = ref([])
const membersLoading = ref(false)

const projectId = ref(null)
const planId = ref(null)
const keyword = ref('')
const selection = ref([])

const drawerVisible = ref(false)
const selectedCheckId = ref(null)

const createVisible = ref(false)
const createForm = ref({ name: '', version: '1.0', template: true })

const rawVisible = ref(false)
const rawItem = ref(null)

// 保存时的基线快照，用来判断"有未保存的修改"
const snapshot = ref('')

const checks = computed(() => planDetail.value?.checks || [])

const enabledCount = computed(() => checks.value.filter((c) => c.enabled).length)

const filteredChecks = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return checks.value
  return checks.value.filter(
    (c) =>
      String(c.code || '').toLowerCase().includes(kw) ||
      String(c.title || '').toLowerCase().includes(kw)
  )
})

const selectedCheck = computed(
  () => checks.value.find((c) => c.id === selectedCheckId.value) || null
)

const dirty = computed(() => !!planDetail.value && JSON.stringify(checks.value) !== snapshot.value)

const VER_TAIL = /\s*v?\d+(\.\d+){0,2}\s*$/i
const nameHasVer = (p) => !!p && VER_TAIL.test(String(p.name || ''))
const planLabel = (p) => (!p ? '' : nameHasVer(p) ? p.name : `${p.name} v${p.version}`)

const subtitle = computed(() => {
  const pname = projects.value.find((p) => p.id === projectId.value)?.name
  if (!planDetail.value) return `${pname || '当前工程'} · 选择或新建一个验收方案`
  const parts = [pname, planLabel(planDetail.value)]
  parts.push(`${checks.value.length} 项 / 启用 ${enabledCount.value} 项`)
  if (mockMode.value) parts.push('离线数据')
  if (dirty.value) parts.push('未保存')
  return parts.filter(Boolean).join(' · ')
})

const rawText = computed(() => {
  if (!rawItem.value) return ''
  const { status, code, title, message, durationMs, ...rest } = rawItem.value
  return JSON.stringify(rest, null, 2)
})

function typeLabel(type) {
  const t = checkTypes.value.find((x) => x.type === type)
  return t ? t.label : type
}

const SEVERITY_TEXT = { critical: '致命', high: '高', medium: '中', low: '低' }

function severityLabel(v) {
  return SEVERITY_TEXT[v] || '中'
}

function memberName(username) {
  const m = members.value.find((x) => x.username === username)
  return m ? m.realName : username
}

function enabledOf(plan) {
  return (plan.checks || []).filter((c) => c.enabled).length
}

async function loadProjects() {
  await projectStore.load(true)
  if (projectId.value && projects.value.some((p) => p.id === projectId.value)) return
  // 支持从「工程管理」页带 ?projectId= 跳过来；否则沿用全局当前工程
  const want = Number(route.query.projectId) || projectStore.projectId || null
  if (want && projects.value.some((p) => p.id === want)) {
    projectId.value = want
  } else if (projects.value.length) {
    projectId.value = projects.value[0].id
  }
  if (projectId.value) projectStore.switchTo(projectId.value)
}

async function loadPlans() {
  if (!projectId.value) {
    plans.value = []
    return
  }
  plans.value = await acceptanceApi.listPlans(projectId.value)
}

async function loadPlanDetail(id) {
  if (!id) {
    planDetail.value = null
    snapshot.value = ''
    return
  }
  const plan = await acceptanceApi.getPlan(id)
  // 兜底：后端历史数据可能缺id/seq，前端补上，避免表格row-key冲突
  let seq = 0
  ;(plan.checks || []).forEach((c) => {
    seq += 1
    if (c.id == null) c.id = Date.now() + seq
    if (c.seq == null) c.seq = seq
  })
  planDetail.value = plan
  snapshot.value = JSON.stringify(plan.checks || [])
}

async function reload(keepPlanId) {
  loading.value = true
  try {
    await loadProjects()
    await loadPlans()
    const target = plans.value.some((p) => p.id === keepPlanId)
      ? keepPlanId
      : plans.value[0]?.id || null
    planId.value = target
    await loadPlanDetail(target)
  } catch (e) {
    ElMessage.error('加载失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

async function onProjectChange() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('当前方案有未保存的修改，切换工程将丢弃这些修改，继续？', '提示', {
        type: 'warning',
        confirmButtonText: '继续',
        cancelButtonText: '取消'
      })
    } catch {
      return
    }
  }
  if (projectId.value) projectStore.switchTo(projectId.value)
  await reload(null)
}

async function pickPlan(id) {
  if (id === planId.value) return
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('当前方案有未保存的修改，切换方案将丢弃，继续？', '提示', {
        type: 'warning',
        confirmButtonText: '继续',
        cancelButtonText: '取消'
      })
    } catch {
      return
    }
  }
  planId.value = id
  loading.value = true
  try {
    await loadPlanDetail(id)
  } catch (e) {
    ElMessage.error('加载方案失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

async function loadMeta() {
  membersLoading.value = true
  try {
    const [types, mem] = await Promise.all([acceptanceApi.checkTypes(), acceptanceApi.members()])
    checkTypes.value = types || []
    members.value = mem || []
  } catch (e) {
    // 元数据失败不阻塞页面，下拉为空即可
    console.warn('检查类型/成员加载失败', e)
  } finally {
    membersLoading.value = false
  }
}

function openDrawer(row) {
  selectedCheckId.value = row.id
  drawerVisible.value = true
}

function addCheck() {
  if (!planDetail.value) {
    ElMessage.warning('先选择或新建一个方案')
    return
  }
  const maxSeq = Math.max(0, ...checks.value.map((c) => c.seq || 0))
  const meta = checkTypes.value.find((t) => t.type === 'CUSTOM_COMMAND') || checkTypes.value[0]
  const params = {}
  ;(meta?.fields || []).forEach((f) => {
    params[f.key] = f.default
  })
  const prefix = checks.value[0]?.code?.slice(0, 1) || 'A'
  const item = {
    id: Date.now(),
    seq: maxSeq + 1,
    code: `${prefix}-${String(maxSeq + 1).padStart(2, '0')}`,
    title: '新检查项',
    type: meta?.type || 'CUSTOM_COMMAND',
    owner: auth.user?.username || 'player4',
    severity: 'medium',
    enabled: true,
    allowFinding: true,
    needConfirm: false,
    params,
    assertExpr: meta?.assertHint || 'custom.matched == true'
  }
  checks.value.push(item)
  selectedCheckId.value = item.id
  drawerVisible.value = true
}

function move(row, delta) {
  const idx = checks.value.indexOf(row)
  const target = idx + delta
  if (idx < 0 || target < 0 || target >= checks.value.length) return
  const [moved] = checks.value.splice(idx, 1)
  checks.value.splice(target, 0, moved)
}

async function removeCheck(row) {
  try {
    await ElMessageBox.confirm(`删除检查项「${row.title}」？删除后立即保存。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  const idx = checks.value.indexOf(row)
  if (idx < 0) return
  checks.value.splice(idx, 1)
  if (selectedCheckId.value === row.id) {
    selectedCheckId.value = null
    drawerVisible.value = false
  }
  // 删除直接落库，不必再点一次保存
  const ok = await doSave(true)
  if (ok) {
    ElMessage.success(`已删除「${row.title}」并保存`)
  }
}

function setEnabled(v) {
  selection.value.forEach((row) => {
    row.enabled = v
  })
  ElMessage.success(`已${v ? '启用' : '停用'} ${selection.value.length} 项，保存后生效`)
}

async function doSave(silent = false) {
  if (!planDetail.value) return false
  if (!enabledCount.value) {
    ElMessage.warning('至少要保留一个启用的检查项')
    return false
  }
  saving.value = true
  try {
    await acceptanceApi.savePlan(planId.value, {
      name: planDetail.value.name,
      checks: checks.value
    })
    snapshot.value = JSON.stringify(checks.value)
    savedAt.value = new Date()
    plans.value = await acceptanceApi.listPlans(projectId.value)
    if (!silent) {
      ElMessage.success(mockMode.value ? '已保存（离线模式，仅前端内存）' : '方案已保存')
    }
    return true
  } catch (e) {
    ElMessage.error('保存失败：' + (e?.message || e))
    return false
  } finally {
    saving.value = false
  }
}

function openCreate() {
  if (!projectId.value) {
    ElMessage.warning('先选择一个工程')
    return
  }
  createForm.value = { name: '', version: '1.0', template: true }
  createVisible.value = true
}

async function doCreate() {
  const name = createForm.value.name.trim()
  if (!name) {
    ElMessage.warning('请填写方案名称')
    return
  }
  creating.value = true
  try {
    const plan = await acceptanceApi.createPlan({
      projectId: projectId.value,
      name,
      version: createForm.value.version || '1.0',
      template: createForm.value.template
    })
    createVisible.value = false
    await loadPlans()
    planId.value = plan.id
    await loadPlanDetail(plan.id)
    ElMessage.success(createForm.value.template ? '已创建方案（含标准六项模板）' : '已创建空方案')
  } catch (e) {
    ElMessage.error('创建失败：' + (e?.message || e))
  } finally {
    creating.value = false
  }
}

async function doDeletePlan() {
  const plan = plans.value.find((p) => p.id === planId.value)
  if (!plan) return
  try {
    await ElMessageBox.confirm(
      `删除方案「${planLabel(plan)}」？该方案的历史批次会一并清除，工程本身不受影响。`,
      '删除方案',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await acceptanceApi.deletePlan(plan.id)
    ElMessage.success('方案已删除')
    await reload(null)
  } catch (e) {
    ElMessage.error('删除失败：' + (e?.message || e))
  }
}

async function doDryRun() {
  if (!selectedCheck.value) return
  dryRunning.value = true
  try {
    const r = await acceptanceApi.dryRun(planId.value, selectedCheck.value.id)
    rawItem.value = r
    rawVisible.value = true
    ElMessage.success(`试运行完成：${r.status}（未生成正式批次）`)
  } catch (e) {
    ElMessage.error('试运行失败：' + (e?.message || e))
  } finally {
    dryRunning.value = false
  }
}

onMounted(async () => {
  await loadMeta()
  await reload(null)
})
</script>

<style scoped>
.pd-sel {
  width: 190px;
}

.pd-sel-plan {
  width: 220px;
}

.plan-design {
  height: calc(100vh - 96px);
  min-height: 560px;
  box-sizing: border-box;
}

.pd-grid {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 16px;
  align-items: stretch;
}

.pd-plans {
  flex: 0 0 auto;
  width: 300px;
  display: flex;
  flex-direction: column;
}

.pd-plans-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--nms-border-soft);
  font-size: 15px;
  font-weight: 600;
  color: var(--nms-text);
}

.pd-plans-count {
  margin-left: auto;
  font-size: 13px;
  font-weight: 400;
  color: var(--nms-text-muted);
}

.pd-plans-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
}

.pd-plan {
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid transparent;
  margin-bottom: 6px;
}

.pd-plan:hover {
  background: var(--nms-bg-elevated);
}

.pd-plan.active {
  background: #eef5ff;
  border-color: #cfe2fb;
}

.pd-plan-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pd-plan-name {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--nms-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pd-plan-ver {
  flex: 0 0 auto;
  font-size: 12px;
  color: var(--nms-text-muted);
}

.pd-plan-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--nms-text-secondary);
}

.pd-empty {
  padding: 16px 12px;
  font-size: 13px;
  color: var(--nms-text-muted);
  line-height: 1.6;
}

.pd-plans-foot {
  padding: 8px;
  border-top: 1px solid var(--nms-border-soft);
}

.pd-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.pd-search {
  width: 220px;
}

.pd-dirty {
  margin-left: auto;
  font-size: 13px;
  color: var(--nms-warning);
}

.pd-table {
  width: 100%;
  flex: 1;
  min-height: 0;
}

.pd-table :deep(.el-table__inner-wrapper) {
  height: 100%;
}

.pd-table :deep(.el-table__body-wrapper) {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.pd-table :deep(.el-table__header th) {
  font-size: 14px;
  font-weight: 600;
  color: var(--nms-text-secondary);
}

.pd-table :deep(.el-table__body td) {
  font-size: 14px;
}

.pd-table :deep(.el-table__row) {
  cursor: pointer;
}

.pd-code {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  color: var(--nms-text-secondary);
}

.pd-title-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.pd-title {
  font-size: 14px;
  color: var(--nms-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pd-title.off {
  color: var(--nms-text-muted);
  text-decoration: line-through;
}

.pd-type {
  font-size: 13px;
  color: var(--nms-text-secondary);
}

.pd-mine {
  color: var(--nms-primary);
  font-weight: 600;
}

.pd-sev {
  font-size: 13px;
}

.sev-critical {
  color: var(--nms-danger);
}

.sev-high {
  color: #e67700;
}

.sev-medium {
  color: var(--nms-warning);
}

.sev-low {
  color: var(--nms-text-muted);
}

.pd-ops {
  display: inline-flex;
  align-items: center;
  gap: 4px;

  
  .op-btn {
    width: 28px;
    height: 28px;
    padding: 0;
    border: none;
    background: transparent;
    border-radius: 4px;
    color: var(--nms-text);
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    font-size: 15px;

    &:hover:not(:disabled) {
      background: var(--nms-bg);
    }
    &:disabled {
      opacity: 0.35;
      cursor: default;
    }
    &.is-danger:hover {
      color: var(--nms-danger);
      background: var(--el-color-danger-light-9);
    }
  }
}

.pd-del:hover {
  color: var(--nms-danger);
}

.pd-table-empty {
  padding: 60px 20px;
  text-align: center;
  font-size: 14px;
  color: var(--nms-text-muted);
}

.pd-footer {
  padding: 12px 16px;
  border-top: 1px solid var(--nms-border-soft);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  
  padding-right: 92px;
}

.pd-save-state {
  flex: 0 0 auto;
  font-size: 13px;
  color: var(--nms-text-muted);
}

.pd-save-state.is-dirty {
  color: var(--nms-warning);
}

.pd-sum {
  margin-right: auto;
  font-size: 13px;
  color: var(--nms-text-secondary);
}

.pd-sum em {
  font-style: normal;
  color: var(--nms-text-muted);
}

.pd-drawer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.pd-drawer-foot-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pd-ver-input {
  width: 140px;
}

.pd-raw-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.pd-raw-title {
  font-size: 14px;
  color: var(--nms-text);
}

.pd-raw-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--nms-text-muted);
}

.pd-raw-msg {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--nms-text-secondary);
  line-height: 1.6;
}

.pd-raw {
  margin: 0;
  max-height: 340px;
  overflow: auto;
  padding: 12px 14px;
  border-radius: 8px;
  background: #f7f9fc;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--nms-text);
}
</style>
