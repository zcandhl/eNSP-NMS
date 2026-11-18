<template>
  <div class="nms-page pjm-page" v-loading="loading">
    <div class="nms-page-header">
      <div class="nms-page-title-block">
        <h1 class="nms-page-title">工程管理</h1>
        <p class="nms-page-subtitle">{{ subtitle }}</p>
      </div>
      <div class="nms-page-actions">
        <el-button :loading="loading" @click="reload">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建工程</el-button>
      </div>
    </div>

    <div class="pjm-body">
      <!-- 左：工程目录 -->
      <aside class="pjm-list nms-panel">
        <div class="pl-head">
          <span>工程目录</span>
          <em>{{ projects.length }}</em>
        </div>
        <div class="pl-rows">
          <button
            v-for="p in cards"
            :key="p.id"
            type="button"
            class="pl-row"
            :class="{ active: p.id === activeId }"
            @click="activeId = p.id"
          >
            <div class="pl-name">
              <i class="pl-dot" :class="p.stageLevel"></i>
              <b :title="p.name">{{ p.name }}</b>
              <span v-if="p.id === projectStore.projectId" class="pl-cur">当前</span>
            </div>
            <div class="pl-building" :title="p.building">{{ p.building || '未填写建筑与规模' }}</div>
            <div class="pl-meta">
              <span>{{ p.deviceCount }} 设备 · {{ p.linkCount }} 链路</span>
              <em :class="p.stageLevel">{{ p.phaseText }}</em>
            </div>
          </button>
          <div v-if="!cards.length && !loading" class="pl-empty">还没有工程，先建一个</div>
        </div>
        <button type="button" class="pl-add" @click="openCreate">
          <el-icon><Plus /></el-icon>
          新建工程
        </button>
      </aside>

      <!-- 右：工程档案 + 建设流程 -->
      <section v-if="active" class="pjm-detail nms-panel">
        <div class="pd-head">
          <div class="pd-title-block">
            <div class="pd-title-line">
              <h2>{{ active.name }}</h2>
              <span v-if="isCurrent" class="pd-flag">当前工作工程</span>
              <span v-else class="pd-flag dim">未选中</span>
            </div>
            <div class="pd-building">{{ active.building || '未填写建筑与规模' }}</div>
          </div>
          <div class="pd-actions">
            <el-button v-if="!isCurrent" type="primary" @click="setCurrent">设为当前工程</el-button>
            <el-button @click="openEdit(active)">编辑档案</el-button>
            <el-button type="danger" plain @click="doDelete(active)">删除</el-button>
          </div>
        </div>

        <div class="pd-orgs">
          <div class="org">
            <span>建设单位</span>
            <b>{{ active.owner || '—' }}</b>
          </div>
          <div class="org">
            <span>承建单位</span>
            <b>{{ active.contractor || '—' }}</b>
          </div>
          <div class="org">
            <span>验收单位</span>
            <b>{{ active.acceptanceOrg || '—' }}</b>
          </div>
        </div>

        <!-- 阶段流水线：建档 → 纳管 → 组网 → 方案 → 执行 → 报告 -->
        <div class="pd-section-title">
          <span>建设到验收</span>
          <em>工程是根，设备、拓扑、验收方案依次挂上去</em>
        </div>

        <ol class="flow">
          <li
            v-for="(s, i) in steps"
            :key="s.key"
            class="flow-step"
            :class="[s.state, { last: i === steps.length - 1 }]"
            @click="runStep(s)"
          >
            <i v-if="i !== steps.length - 1" class="fs-line"></i>
            <div class="fs-rail">
              <span class="fs-dot">
                <el-icon v-if="s.state === 'done'"><Check /></el-icon>
                <template v-else>{{ i + 1 }}</template>
              </span>
            </div>
            <div class="fs-body">
              <div class="fs-name">{{ s.label }}</div>
              <div class="fs-metric">{{ s.metric }}</div>
            </div>
          </li>
        </ol>

        <div class="pd-next" :class="nextStep ? nextStep.state : 'all-done'">
          <template v-if="nextStep">
            <div class="nx-label">下一步 · {{ nextStep.label }}</div>
            <div class="nx-text">{{ nextStep.hint }}</div>
            <el-button type="primary" @click="runStep(nextStep)">{{ nextStep.action }}</el-button>
          </template>
          <template v-else>
            <div class="nx-label">全流程已完成</div>
            <div class="nx-text">本工程已完成验收并出具报告，可在报告页导出交付文档。</div>
            <el-button type="primary" plain @click="goReport">查看报告</el-button>
          </template>
        </div>

        <div class="pd-kpis">
          <div class="kpi">
            <b>{{ active.deviceCount }}</b>
            <span>纳管设备</span>
          </div>
          <div class="kpi">
            <b>{{ active.linkCount }}</b>
            <span>组网链路</span>
          </div>
          <div class="kpi">
            <b>{{ active.planCount }}</b>
            <span>验收方案</span>
          </div>
          <div class="kpi">
            <b>{{ active.batchCount }}</b>
            <span>执行批次</span>
          </div>
          <div class="kpi">
            <b :class="{ ok: active.doneCount && active.lastRate >= 90, bad: active.doneCount && active.lastRate < 60 }">
              {{ active.doneCount ? active.lastRate + '%' : '—' }}
            </b>
            <span>最近通过率</span>
          </div>
        </div>

        <div class="pd-note">
          <el-icon><InfoFilled /></el-icon>
          <span>
            顶栏工程切换器与本页共用同一个"当前工程"。切换后，设备、告警、性能、拓扑、验收全线跟随，
            不会把两栋楼的设备混在一起。
          </span>
        </div>
      </section>

      <section v-else class="pjm-empty nms-panel">
        <el-empty description="工程是整条链路的起点：先建工程档案，再纳管设备、组网、做验收">
          <el-button type="primary" @click="openCreate">新建第一个工程</el-button>
        </el-empty>
      </section>
    </div>

    <!-- 新建 / 编辑工程 -->
    <el-drawer
      v-model="formVisible"
      :title="editingId ? '编辑工程档案' : '新建工程'"
      size="460px"
      direction="rtl"
      :close-on-click-modal="false"
    >
      <el-form :model="form" label-width="92px" size="default" class="pj-form">
        <el-form-item label="工程名称" required>
          <el-input v-model="form.name" placeholder="如：三期工程综合实训大楼" maxlength="100" />
        </el-form-item>
        <el-form-item label="建筑/范围">
          <el-input v-model="form.building" placeholder="如：综合实训大楼（6 层 / 约 3.5 万㎡）" maxlength="200" />
        </el-form-item>
        <el-form-item label="建设单位">
          <el-input v-model="form.owner" placeholder="如：学校信息中心" maxlength="100" />
        </el-form-item>
        <el-form-item label="承建单位">
          <el-input v-model="form.contractor" maxlength="100" />
        </el-form-item>
        <el-form-item label="验收单位">
          <el-input v-model="form.acceptanceOrg" maxlength="100" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>
        <div class="pf-tip">
          工程是数据的根：设备按"所属工程"归集，拓扑、告警、性能、验收方案、批次与报告都挂在工程下。
          删除工程会连带删除其名下设备（含告警/拓扑/性能数据）与方案、历史批次，且不可恢复。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveForm">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Check, InfoFilled } from '@element-plus/icons-vue'
import { acceptanceApi, mockMode } from '@/api/acceptance'
import { useProjectStore } from '@/stores/project'

const router = useRouter()
const route = useRoute()
const projectStore = useProjectStore()
const { projects } = storeToRefs(projectStore)

const loading = ref(false)
const saving = ref(false)
const stats = ref(new Map()) // projectId -> { planCount, batchCount, doneCount, lastRate }
const formVisible = ref(false)
const editingId = ref(null)
const form = ref({})
const activeId = ref(null)

const isCurrent = computed(() => activeId.value != null && activeId.value === projectStore.projectId)

/* ── 阶段定义：真实项目里"一栋楼"从建档到交付的顺序 ── */
const STEPS = [
  { key: 'archive', label: '工程建档', hint: '完善建筑、参建单位等档案信息，报告封面与工程概况取自这里。' },
  { key: 'devices', label: '设备纳管', hint: '把本工程设备录入系统（支持发现批量入库）；设备归属工程后，拓扑与验收都会自动带出。' },
  { key: 'topology', label: '组网拓扑', hint: '核对设备间物理连接，链路齐全后验收的连通性、冗余类检查才有真实依据。' },
  { key: 'plan', label: '验收方案', hint: '按标准模板定义检查项与判定准则，方案是本次验收的"考卷"。' },
  { key: 'run', label: '验收执行', hint: '在验收工作台逐项实测，证据与原始回显随批次冻结存档。' },
  { key: 'report', label: '出具报告', hint: '批次通过后生成验收报告，导出 Word 交付甲方。' }
]

const cards = computed(() =>
  projects.value.map((p) => {
    const s = stats.value.get(p.id) || { planCount: 0, batchCount: 0, doneCount: 0, lastRate: 0 }
    const merged = { ...p, ...s }
    const raw = buildRaw(merged)
    const firstUndone = raw.findIndex((x) => !x.done)
    const finished = firstUndone === -1 || (firstUndone === STEPS.length - 1 && raw[firstUndone].ready)
    merged.phaseText = finished ? '全流程完成' : (firstUndone <= 0 ? '待启动' : '进行中：' + STEPS[firstUndone].label)
    merged.stageLevel = finished ? 'ok' : (firstUndone <= 1 ? 'warn' : 'info')
    return merged
  })
)

const active = computed(() => cards.value.find((c) => c.id === activeId.value) || null)

/* 每步的完成判定：只看工程内真实数据，不做"点了就算完成"的假状态 */
function buildRaw(p) {
  const deviceCount = p.deviceCount || 0
  const linkCount = p.linkCount || 0
  const planCount = p.planCount || 0
  const batchCount = p.batchCount || 0
  const doneCount = p.doneCount || 0
  return [
    { key: 'archive', done: true, metric: '已建立', action: '完善档案', run: () => openEdit(p) },
    {
      key: 'devices',
      done: deviceCount > 0,
      metric: deviceCount ? `${deviceCount} 台` : '未纳管',
      action: deviceCount ? '管理设备' : '录入设备',
      run: () => goDevices()
    },
    {
      key: 'topology',
      done: linkCount > 0,
      metric: linkCount ? `${linkCount} 条` : '无链路',
      action: '查看拓扑',
      run: () => go('/topology')
    },
    {
      key: 'plan',
      done: planCount > 0,
      metric: planCount ? `${planCount} 个` : '未设计',
      action: planCount ? '查看方案' : '设计方案',
      run: () => go('/acceptance/plan')
    },
    {
      key: 'run',
      done: doneCount > 0,
      metric: batchCount ? `${batchCount} 批 / ${doneCount} 批完成` : '未执行',
      action: doneCount ? '查看执行' : '开始验收',
      run: () => go('/acceptance/workbench')
    },
    {
      key: 'report',
      done: false,
      ready: doneCount > 0,
      metric: doneCount ? `${p.lastRate || 0}% 通过` : '待执行',
      action: '出具报告',
      run: () => goReport()
    }
  ]
}

const steps = computed(() => {
  if (!active.value) return []
  const raw = buildRaw(active.value)
  const firstUndone = raw.findIndex((x) => !x.done)
  return raw.map((s, i) => {
    const def = STEPS[i]
    let state = 'todo'
    if (s.done) state = 'done'
    else if (s.ready) state = 'ready'
    else if (i === firstUndone) state = 'current'
    return { ...def, ...s, state }
  })
})

/* 引导条：永远指向"第一个还没做完的事"；仅剩报告时提示可出具 */
const nextStep = computed(() => {
  if (!active.value) return null
  const list = steps.value
  const idx = list.findIndex((s) => s.state === 'current')
  if (idx >= 0) return list[idx]
  const ready = list.find((s) => s.state === 'ready')
  return ready || null
})

const subtitle = computed(() => {
  if (!projects.value.length) return '工程是整条链路的起点：建档 → 纳管设备 → 组网 → 验收方案 → 执行 → 报告'
  const devTotal = cards.value.reduce((n, c) => n + (c.deviceCount || 0), 0)
  const linkTotal = cards.value.reduce((n, c) => n + (c.linkCount || 0), 0)
  const doneTotal = cards.value.filter((c) => c.phaseText === '全流程完成').length
  const parts = [
    `${projects.value.length} 个工程`,
    `${devTotal} 台设备`,
    `${linkTotal} 条链路`,
    doneTotal ? `${doneTotal} 个已交付` : '暂无已交付工程'
  ]
  if (mockMode.value) parts.push('演示数据')
  return parts.join(' · ')
})

function ts(v) {
  if (!v) return NaN
  if (Array.isArray(v)) return new Date(...v.slice(0, 6).map((x, i) => (i === 1 ? x - 1 : x))).getTime()
  return new Date(String(v).replace(' ', 'T')).getTime()
}

function rateOf(b) {
  const items = b?.items || []
  const pass = items.filter((i) => i.status === 'PASS').length
  return items.length ? Math.round((pass * 100) / items.length) : 0
}

async function reload() {
  loading.value = true
  try {
    await projectStore.load(true)
    const map = new Map()
    await Promise.all(
      projects.value.map(async (p) => {
        const s = { planCount: 0, batchCount: 0, doneCount: 0, lastRate: 0 }
        try {
          const plans = (await acceptanceApi.listPlans(p.id)) || []
          s.planCount = plans.length
          const groups = await Promise.all(
            plans.map((pl) => acceptanceApi.listBatches(pl.id).catch(() => []))
          )
          const batches = groups
            .flat()
            .filter((b) => b && b.status === 'DONE')
            .sort((a, b) => (ts(a.startedAt) || 0) - (ts(b.startedAt) || 0))
          s.doneCount = batches.length
          s.batchCount = groups.flat().length
          if (batches.length) s.lastRate = rateOf(batches[batches.length - 1])
        } catch (e) {
          /* 统计失败按 0 处理，不阻塞列表 */
        }
        map.set(p.id, s)
      })
    )
    stats.value = map
    if (!activeId.value || !projects.value.some((p) => p.id === activeId.value)) {
      activeId.value = projectStore.projectId || projects.value[0]?.id || null
    }
  } catch (e) {
    if (!mockMode.value) console.error('加载工程失败', e)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.value = { name: '', building: '', owner: '', contractor: '', acceptanceOrg: '', note: '' }
  formVisible.value = true
}

function openEdit(p) {
  editingId.value = p.id
  form.value = {
    name: p.name,
    building: p.building || '',
    owner: p.owner || '',
    contractor: p.contractor || '',
    acceptanceOrg: p.acceptanceOrg || '',
    note: p.note || ''
  }
  formVisible.value = true
}

async function saveForm() {
  if (!form.value.name?.trim()) {
    ElMessage.warning('工程名称不能为空')
    return
  }
  saving.value = true
  const isNew = !editingId.value
  try {
    let created = null
    if (isNew) {
      created = await acceptanceApi.createProject(form.value)
      ElMessage.success('工程已创建，并设为当前工程')
    } else {
      await acceptanceApi.updateProject(editingId.value, form.value)
      ElMessage.success('工程档案已更新')
    }
    formVisible.value = false
    await reload()
    if (isNew) {
      const newId = created?.id
      if (newId) {
        projectStore.switchTo(Number(newId))
        activeId.value = Number(newId)
      }
    }
  } catch (e) {
    ElMessage.error('保存失败：' + (e?.response?.data?.message || e?.message || e))
  } finally {
    saving.value = false
  }
}

async function doDelete(p) {
  try {
    await ElMessageBox.confirm(
      `删除工程「${p.name}」？将一并删除：${p.deviceCount || 0} 台纳管设备（含其告警、拓扑、性能数据与配置备份）、${p.planCount || 0} 个验收方案及 ${p.batchCount || 0} 个历史批次。此操作不可恢复。`,
      '删除工程',
      { type: 'error', confirmButtonText: '全部删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await acceptanceApi.deleteProject(p.id)
    ElMessage.success('工程已删除')
    activeId.value = null
    await reload()
  } catch (e) {
    ElMessage.error('删除失败：' + (e?.response?.data?.message || e?.message || e))
  }
}

function setCurrent() {
  if (!active.value) return
  projectStore.switchTo(active.value.id)
  ElMessage.success(`已切换到「${active.value.name}」，设备、告警、拓扑、验收已跟随`)
}

/* 任意一步：先把该工程切成全局上下文，再跳目标页，避免"跳过去看到的还是别的工程" */
function runStep(step) {
  if (!step || !active.value) return
  if (step.state === 'todo') return
  if (!isCurrent.value) setCurrent()
  step.run && step.run()
}

function go(path) {
  router.push(path)
}

function goDevices() {
  const needAdd = (active.value?.deviceCount || 0) === 0
  // 未纳管时带 intent=entry：设备页先让用户选择录入方式，而非直接弹添加表单
  router.push({ path: '/devices', query: needAdd ? { intent: 'entry' } : undefined })
}

function goReport() {
  router.push('/acceptance/report')
}

// 顶栏切换工程时，本页跟随定位到该工程
watch(
  () => projectStore.projectId,
  (id) => {
    if (id && cards.value.some((c) => c.id === id)) activeId.value = id
  }
)

// 顶栏「新建工程」入口：/projects?create=1
watch(
  () => route.query.create,
  (v) => {
    if (v === '1' || v === 'true') {
      openCreate()
      router.replace({ path: '/projects' })
    }
  }
)

onMounted(async () => {
  await reload()
  if (route.query.create === '1' || route.query.create === 'true') {
    openCreate()
    router.replace({ path: '/projects' })
  }
})
</script>

<style scoped lang="scss">
.pjm-page {
  height: calc(100vh - 96px);
  min-height: 560px;
  box-sizing: border-box;
}

.pjm-body {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  gap: 16px;
}

/* ── 左：工程目录 ── */
.pjm-list {
  flex: 0 0 296px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.pl-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--nms-border-soft);
  font-size: 13px;
  font-weight: 600;
  color: var(--nms-text);

  em {
    margin-left: auto;
    font-style: normal;
    font-size: 12px;
    font-weight: 600;
    color: var(--nms-primary);
    background: var(--nms-primary-soft, #eaf2fd);
    border-radius: 9px;
    padding: 1px 8px;
  }
}

.pl-rows {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
}

.pl-row {
  display: block;
  width: 100%;
  text-align: left;
  border: 1px solid transparent;
  background: transparent;
  border-radius: 8px;
  padding: 11px 12px;
  cursor: pointer;
  font: inherit;
  color: inherit;
  transition: background 0.15s, border-color 0.15s;

  & + .pl-row {
    margin-top: 4px;
  }

  &:hover {
    background: var(--nms-bg-elevated);
  }

  &.active {
    background: var(--nms-primary-soft, #eaf2fd);
    border-color: rgba(43, 125, 233, 0.35);
  }
}

.pl-name {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;

  b {
    font-size: 14px;
    font-weight: 600;
    color: var(--nms-text);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.pl-dot {
  flex: 0 0 auto;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c3ccd8;

  &.ok {
    background: #1f9d6a;
  }

  &.warn {
    background: #d48806;
  }

  &.info {
    background: var(--nms-primary);
  }
}

.pl-cur {
  flex: 0 0 auto;
  font-size: 10px;
  line-height: 1;
  padding: 2px 5px;
  border-radius: 4px;
  color: #fff;
  background: var(--nms-primary);
}

.pl-building {
  margin-top: 4px;
  font-size: 12px;
  color: var(--nms-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pl-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--nms-text-secondary);

  em {
    margin-left: auto;
    font-style: normal;
    font-size: 11px;
    padding: 1px 6px;
    border-radius: 4px;
    background: var(--nms-bg-elevated);
    color: var(--nms-text-secondary);
    white-space: nowrap;

    &.ok {
      color: #1f9d6a;
      background: rgba(31, 157, 106, 0.1);
    }

    &.warn {
      color: #a86500;
      background: rgba(212, 136, 6, 0.12);
    }

    &.info {
      color: var(--nms-primary);
      background: var(--nms-primary-soft, #eaf2fd);
    }
  }
}

.pl-empty {
  padding: 28px 12px;
  text-align: center;
  font-size: 13px;
  color: var(--nms-text-muted);
}

.pl-add {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 8px;
  padding: 9px;
  border: 1px dashed var(--nms-border);
  border-radius: 8px;
  background: transparent;
  color: var(--nms-primary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  font-family: inherit;

  &:hover {
    background: var(--nms-primary-soft, #eaf2fd);
    border-color: var(--nms-primary);
  }
}

/* ── 右：工程档案 + 流程 ── */
.pjm-detail {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: 20px 22px;
}

.pd-head {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}

.pd-title-block {
  min-width: 0;
  flex: 1;
}

.pd-title-line {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;

  h2 {
    margin: 0;
    font-size: 19px;
    font-weight: 650;
    color: var(--nms-text);
    letter-spacing: -0.01em;
  }
}

.pd-flag {
  flex: 0 0 auto;
  font-size: 11px;
  line-height: 1;
  padding: 3px 7px;
  border-radius: 4px;
  background: var(--nms-primary);
  color: #fff;

  &.dim {
    background: var(--nms-bg-elevated);
    color: var(--nms-text-muted);
  }
}

.pd-building {
  margin-top: 5px;
  font-size: 13px;
  color: var(--nms-text-secondary);
}

.pd-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.pd-orgs {
  display: flex;
  gap: 10px;
  margin-top: 16px;
  flex-wrap: wrap;
}

.org {
  flex: 1 1 180px;
  min-width: 0;
  padding: 9px 12px;
  border: 1px solid var(--nms-border-soft);
  border-radius: 7px;
  background: var(--nms-bg-elevated);

  span {
    display: block;
    font-size: 11px;
    color: var(--nms-text-muted);
  }

  b {
    display: block;
    margin-top: 3px;
    font-size: 13px;
    font-weight: 500;
    color: var(--nms-text);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.pd-section-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 22px 0 12px;

  span {
    font-size: 14px;
    font-weight: 600;
    color: var(--nms-text);
  }

  em {
    font-style: normal;
    font-size: 12px;
    color: var(--nms-text-muted);
  }
}

/* 阶段流水线 */
.flow {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 0;
  list-style: none;
  margin: 0;
  padding: 0;
}

.flow-step {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  min-width: 0;
  cursor: pointer;

  &.todo {
    cursor: default;
  }
}

.fs-rail {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
}

.fs-dot {
  position: relative;
  z-index: 1;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  background: #eef2f7;
  color: var(--nms-text-muted);
  border: 1px solid #dde5ee;

  .flow-step.done & {
    background: #1f9d6a;
    border-color: #1f9d6a;
    color: #fff;
  }

  .flow-step.current & {
    background: var(--nms-primary);
    border-color: var(--nms-primary);
    color: #fff;
    box-shadow: 0 0 0 4px rgba(43, 125, 233, 0.14);
  }

  .flow-step.ready & {
    background: #fff;
    border-color: var(--nms-primary);
    color: var(--nms-primary);
    box-shadow: 0 0 0 4px rgba(43, 125, 233, 0.12);
  }
}

.fs-line {
  /* 连线走圆点水平轴：本列圆点右缘 → 下一列圆点左缘（right 为负值跨到下一列），文字在轴线下方不重叠 */
  position: absolute;
  top: 10.5px;
  left: calc(50% + 14px);
  right: calc(-50% + 14px);
  height: 3px;
  border-radius: 2px;
  background: #e4eaf2;

  .flow-step.done & {
    background: linear-gradient(90deg, #1f9d6a 0%, #35b57f 100%);
    box-shadow: 0 1px 4px rgba(31, 157, 106, 0.28);
  }
}

.fs-body {
  min-width: 0;
  margin-top: 7px;
  padding: 0 6px;
}

.fs-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--nms-text-secondary);
  white-space: nowrap;

  .flow-step.done & {
    color: var(--nms-text);
  }

  .flow-step.current &,
  .flow-step.ready & {
    color: var(--nms-primary);
  }
}

.fs-metric {
  margin-top: 3px;
  font-size: 11px;
  color: var(--nms-text-muted);
  white-space: nowrap;
}

/* 下一步引导 */
.pd-next {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 20px;
  padding: 14px 16px;
  border-radius: 8px;
  border: 1px solid rgba(43, 125, 233, 0.22);
  background: linear-gradient(135deg, #f4f9ff 0%, #fdfeff 70%);
  flex-wrap: wrap;

  &.ready {
    border-color: rgba(43, 125, 233, 0.3);
  }

  &.all-done {
    border-color: rgba(31, 157, 106, 0.25);
    background: linear-gradient(135deg, #f3fbf7 0%, #fdfefd 70%);
  }
}

.nx-label {
  flex: 0 0 auto;
  font-size: 13px;
  font-weight: 650;
  color: var(--nms-primary);

  .all-done & {
    color: #1f9d6a;
  }
}

.nx-text {
  flex: 1 1 260px;
  min-width: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--nms-text-secondary);
}

/* 统计 */
.pd-kpis {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 10px;
  margin-top: 18px;
}

.kpi {
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--nms-bg-elevated);
  text-align: center;

  b {
    display: block;
    font-size: 20px;
    font-weight: 600;
    color: var(--nms-text);
    line-height: 1.3;

    &.ok {
      color: #1f9d6a;
    }

    &.bad {
      color: #d4380d;
    }
  }

  span {
    font-size: 12px;
    color: var(--nms-text-secondary);
  }
}

.pd-note {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--nms-border-soft);
  font-size: 12px;
  line-height: 1.6;
  color: var(--nms-text-muted);

  .el-icon {
    margin-top: 2px;
    flex: 0 0 auto;
  }
}

.pjm-empty {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  justify-content: center;
}

.pj-form {
  padding-right: 4px;
}

.pf-tip {
  margin: 4px 0 0 92px;
  padding: 8px 10px;
  background: var(--nms-bg-elevated);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--nms-text-secondary);
}

@media (max-width: 1340px) {
  .flow {
    grid-template-columns: repeat(3, 1fr);
    row-gap: 16px;
  }

  .fs-line {
    display: none;
  }

  .pd-kpis {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 1100px) {
  .pjm-body {
    flex-direction: column;
  }

  .pjm-list {
    flex: 0 0 auto;
    max-height: 240px;
  }
}
</style>
