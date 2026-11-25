/**
 * @file    AcceptanceWorkbench.vue
 * @module  视图页/验收
 * @desc    验收执行工作台。
 *
 * @author  ZC
 * @date    2026-11-22 * @version 1.0.0
 * @requires @element-plus/icons-vue, element-plus, pinia, vue, vue-router, @/api/acceptance, @/stores/auth, @/stores/project
 */

<template>
  <div class="nms-page acceptance-page">
    <div class="nms-page-header">
      <div class="nms-page-title-block">
        <h1 class="nms-page-title">验收工作台</h1>
        <p class="nms-page-subtitle">预实施验证 · 交付核验 · 验收取证 —— 同一套检查项复用到底</p>
      </div>
      <div class="nms-page-actions acc-actions">
        <div class="act-group">
          <el-tag size="small" effect="plain" class="who-tag" :title="`登录账号：${auth.user?.username || '—'}`">
            <el-icon class="who-icon"><UserFilled /></el-icon>
            {{ auth.displayName }}
          </el-tag>
          <el-tag v-if="mockMode" size="small" type="warning" effect="plain" class="mock-tag">
            离线数据
          </el-tag>
        </div>
        <div class="act-group">
          <el-tag size="small" effect="plain" class="proj-tag" :title="projects.find((p) => p.id === projectId)?.name || ''">
            {{ projects.find((p) => p.id === projectId)?.name || '未选工程' }}
          </el-tag>
        </div>
        <div class="act-group">
          <el-tooltip content="工程在顶栏切换；方案与检查项在「方案设计」页维护" placement="bottom">
            <el-select v-model="planId" size="small" class="sel-plan" @change="onPlanChange">
              <el-option
                v-for="p in plans"
                :key="p.id"
                :label="/v?\\d+(\\.\\d+){0,2}\\s*$/i.test(p.name || '') ? p.name : `${p.name} · v${p.version}`"
                :value="p.id"
              />
            </el-select>
          </el-tooltip>
        </div>
        <div class="act-group">
          <el-button size="small" :loading="dryRunning" :disabled="!selectedCheck" @click="doDryRun">试运行</el-button>
          <el-button size="small" type="primary" :loading="running" @click="doRun">运行</el-button>
        </div>
        <div class="act-group">
          <el-badge :value="globalRunning.length" :hidden="!globalRunning.length" class="run-badge">
            <el-button
              size="small"
              :type="globalRunning.length ? 'warning' : 'default'"
              plain
              @click="openRunningDialog"
            >
              运行中
            </el-button>
          </el-badge>
          <el-tooltip content="查看完整使用引导：流程、状态含义、常见问题" placement="top">
            <el-button size="small" circle @click="helpVisible = true">
              <el-icon><QuestionFilled /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </div>
    </div>

    
    <div class="guide-strip">
      <el-steps :active="activeStep" align-center finish-status="success" class="guide-steps">
        <el-step title="① 选工程与方案" />
        <el-step title="② 检查项就绪" />
        <el-step title="③ 试运行 / 运行" />
        <el-step title="④ 出具报告" />
      </el-steps>
      <div class="guide-hint">
        <el-icon class="gh-icon"><InfoFilled /></el-icon>
        {{ nextHint }}
        <el-button
          v-if="batch?.batchId && batch.status === 'DONE'"
          link
          type="primary"
          size="small"
          class="gh-help"
          @click="goReport"
        >去出具报告 ›</el-button>
        <el-button link type="primary" size="small" class="gh-help" @click="helpVisible = true">查看引导</el-button>
      </div>
    </div>

    <div class="acc-layout">
      <aside class="acc-left">
        <div class="list-head">
          <span class="lh-title">
            本轮检查项
            <span class="lh-count">{{ enabledCount }}/{{ checks.length }}</span>
          </span>
          <el-button text size="small" class="lh-add" @click="goPlanDesign">去方案设计</el-button>
        </div>

        <div class="check-list">
          <div
            v-for="c in checks"
            :key="c.id"
            class="check-card"
            :class="{ active: c.id === selectedCheckId, off: !c.enabled }"
            @click="selectCheck(c)"
          >
            <div class="cc-top">
              <span class="cc-code">{{ c.code }}</span>
              <span class="cc-title" :title="c.title">{{ c.title }}</span>
              <span class="cc-state" :class="stateTone(c)">{{ stateOf(c) }}</span>
            </div>
            <div class="cc-bottom">
              <span class="cc-owner" :class="{ mine: c.owner === auth.user?.username }">
                {{ memberName(c.owner) }}<em v-if="c.owner === auth.user?.username" class="mine-badge">我</em>
              </span>
              <span class="cc-sev" :class="`sev-${c.severity}`">{{ severityLabel(c.severity) }}</span>
              <span v-if="!c.enabled" class="cc-off">方案中已停用</span>
            </div>
          </div>

          <el-empty v-if="!checks.length" description="该方案还没有检查项" :image-size="56" />
        </div>
        <div class="list-foot">
          只读清单 · 参数与判定准则在「方案设计」页维护
        </div>
      </aside>

      <main class="acc-right">
        
        <div v-if="!planId" class="no-plan-tip">
          <el-icon><InfoFilled /></el-icon>
          <span>当前工程还没有验收方案，执行台无对象可跑。</span>
          <el-button link type="primary" size="small" @click="goPlanDesign">去方案设计页新建</el-button>
          <span class="npt-or">（工程档案与建设流程见左侧「工程管理」）</span>
        </div>

        <div class="batch-bar">
          <span class="bb-label">批次</span>
          <el-select v-model="batchId" size="small" class="bb-sel" placeholder="选择批次" @change="onBatchChange">
            <el-option
              v-for="b in batchOptions"
              :key="b.batchId"
              :label="`${b.no}　${batchStatusText(b)}`"
              :value="b.batchId"
            />
          </el-select>
          <span v-if="planMeta" class="bb-meta">
            {{ planMeta.projectName }} · 报告编号规则 {{ planDetail?.reportNoRule }}
          </span>
        </div>

        <el-tabs v-model="rightTab" class="acc-tabs">
          <el-tab-pane label="运行结果" name="result">
            <div class="result-wrap">
              <RunResultPanel
                :batch="batch"
                :members="members"
                @view-raw="openRaw"
                @confirm="doConfirm"
                @stop="doStop"
                @void="doVoid"
                @delete="doDelete"
              />
            </div>
          </el-tab-pane>
          
          <el-tab-pane label="数字孪生" name="twin" lazy>
            <div class="twin-wrap">
              <TwinTopology :batch="batch" />
            </div>
          </el-tab-pane>
        </el-tabs>
      </main>
    </div>

    
    <el-drawer v-model="detailVisible" :title="detailTitle" size="460px" direction="rtl">
      <div v-if="selectedCheck" class="ci-detail">
        <div class="cid-head">
          <el-tag size="small" effect="plain">{{ checkTypeLabel(selectedCheck.type) }}</el-tag>
          <span class="cid-code">{{ selectedCheck.code }}</span>
          <span class="cc-sev" :class="`sev-${selectedCheck.severity}`">{{ severityLabel(selectedCheck.severity) }}</span>
          <el-tag v-if="selectedCheck.needConfirm" size="small" type="warning" effect="plain">需人工确认</el-tag>
        </div>
        <h3 class="cid-title">{{ selectedCheck.title }}</h3>

        <div class="cid-kv">
          <div class="cid-row"><span>责任人</span><b>{{ memberName(selectedCheck.owner) }}</b></div>
          <div class="cid-row"><span>方案状态</span><b>{{ selectedCheck.enabled ? '启用' : '停用（本批不执行）' }}</b></div>
          <div class="cid-row">
            <span>本批结果</span>
            <b :class="stateTone(selectedCheck)">{{ stateOf(selectedCheck) }}</b>
          </div>
        </div>

        <div class="cid-sec">
          <h4>判定准则</h4>
          <p class="cid-rule" :title="'判定表达式：' + (selectedCheck.assertExpr || '—')">
            {{ judgeText(selectedCheck) || selectedCheck.assertExpr || '—' }}
          </p>
        </div>

        <div class="cid-sec">
          <h4>采集参数</h4>
          <div v-if="paramRows.length" class="cid-params">
            <div v-for="r in paramRows" :key="r.k" class="cid-row">
              <span>{{ r.k }}</span><b class="mono">{{ r.v }}</b>
            </div>
          </div>
          <p v-else class="cid-empty">该检查项没有额外参数</p>
        </div>

        <div class="cid-sec">
          <h4>本批实测</h4>
          <template v-if="currentItem">
            <p class="cid-msg">{{ currentItem.message || '—' }}</p>
            <p class="cid-metric mono">{{ briefMetrics(currentItem.metrics, currentItem.type) }}</p>
            <el-button size="small" :disabled="!currentItem.rawOutput" @click="openRaw(currentItem)">
              查看原始回显
            </el-button>
          </template>
          <p v-else class="cid-empty">该检查项在本批次中没有执行记录</p>
        </div>

        <div class="cid-tip">
          此处只读。修改参数、判定准则或启停，请到
          <el-button link type="primary" size="small" @click="goPlanDesign">方案设计</el-button>
          页操作。
        </div>
      </div>
    </el-drawer>

    <el-drawer v-model="rawVisible" :title="rawTitle" size="520px" direction="rtl">
      <div v-if="rawItem" class="raw-body">
        <div class="raw-meta">
          <el-tag size="small" :type="rawItem.status === 'PASS' ? 'success' : 'warning'" effect="plain">
            {{ rawItem.status }}
          </el-tag>
          <span class="rm-code">{{ rawItem.code }}</span>
          <span class="rm-dur" v-if="rawItem.durationMs">{{ rawItem.durationMs }} ms</span>
        </div>
        <pre class="raw-pre">{{ rawItem.rawOutput || '（该检查项未返回原始输出）' }}</pre>
        <div class="raw-note">
          原始输出为设备回显原文，报告「六、分项测试结果」的实测数据即取自此内容，不做改写。
        </div>
      </div>
    </el-drawer>

    
    <el-dialog v-model="runningVisible" title="运行中的验收（全部方案）" width="760px">
      <el-table :data="globalRunning" size="small" border empty-text="当前没有正在执行的验收批次">
        <el-table-column prop="no" label="报告编号" width="170" />
        <el-table-column label="工程 / 方案" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.projectName || '—' }} /
            {{ /v?\d+(\.\d+){0,2}\s*$/i.test(row.planName || '') ? row.planName : `${row.planName} v${row.planVersion}` }}
          </template>
        </el-table-column>
        <el-table-column label="进度" width="170">
          <template #default="{ row }">
            <div class="gr-progress">
              <el-progress
                :percentage="row.progress?.total ? Math.round((row.progress.done / row.progress.total) * 100) : 0"
                :stroke-width="8"
              />
              <span class="gr-num">{{ row.progress?.done ?? 0 }}/{{ row.progress?.total ?? 0 }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="正在执行" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.progress?.current || '—' }}</template>
        </el-table-column>
        <el-table-column prop="startedAt" label="开始时间" width="165" />
        <el-table-column label="操作" width="70" align="center">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="goToRunningBatch(row)">前往</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="run-dialog-tip">每 4 秒自动刷新；点击"前往"切换到对应方案并选中该批次继续跟踪。</div>
    </el-dialog>

    
    <el-drawer v-model="helpVisible" title="验收工作台 · 使用引导" size="600px" direction="rtl">
      <div class="help-body">
        <div class="help-sec">
          <div class="hs-title">一句话理解</div>
          <p class="hs-p">
            验收工作台就像工程交付时的"质检流水线"：把要验收的内容拆成一张
            <b>检查项清单</b>，每项自动到设备上采集真实数据并判定通过与否，
            全部完成后自动装配成一份<b>验收报告</b>。方案定义"验收什么"，批次记录"某一次验收的结果"。
          </p>
        </div>

        <div class="help-sec">
          <div class="hs-title">标准流程（四步）</div>
          <div class="hs-steps">
            <div class="hs-step"><b>① 选工程与方案</b>：顶部两个下拉框，先选工程，再选验收方案。方案是检查项的载体。</div>
            <div class="hs-step"><b>② 检查项就绪</b>：左栏是检查项清单。点选某一项，左下表单可改参数、负责人；开关控制是否参与本次验收；负责人才是"我的"任务。</div>
            <div class="hs-step"><b>③ 试运行 → 运行</b>：先点「试运行」单独验证当前选中项（不产生批次）；没问题后点「运行」，系统创建批次并后台逐项采集，右侧结果区逐项点亮。</div>
            <div class="hs-step"><b>④ 出具报告</b>：本页只负责跑出结果。全部跑完后到左侧菜单「验收报告」页，按批次生成并下载 Word 验收报告，分项数据取自设备真实回显。</div>
          </div>
        </div>

        <div class="help-sec">
          <div class="hs-title">状态标签含义</div>
          <div class="hs-tags">
            <div class="hs-tag"><el-tag size="small" type="success" effect="plain">PASS</el-tag>检查通过，实测达标</div>
            <div class="hs-tag"><el-tag size="small" type="warning" effect="plain">FINDING</el-tag>发现待整改项（不阻断验收，但要写进报告）</div>
            <div class="hs-tag"><el-tag size="small" type="danger" effect="plain">FAIL</el-tag>检查不通过</div>
            <div class="hs-tag"><el-tag size="small" type="info" effect="plain">PENDING</el-tag>等待人工确认（如故障预演项，需人执行后录入结论）</div>
            <div class="hs-tag"><el-tag size="small" type="info" effect="plain">已跳过</el-tag>被终止或人工跳过，未执行</div>
            <div class="hs-tag"><el-tag size="small" effect="plain">排队</el-tag>正在排队等待采集</div>
          </div>
        </div>

        <div class="help-sec">
          <div class="hs-title">结果区怎么用</div>
          <p class="hs-p">
            点结果列表的任意一行展开详情：结论说明、关键指标、问题清单。
            「查看原始输出」可看到设备回显原文——报告里的实测数据就是它，不作任何改写，这是验收数据可追溯的关键。
          </p>
        </div>

        <div class="help-sec">
          <div class="hs-title">跨方案跟踪</div>
          <p class="hs-p">
            顶栏「运行中的验收」按钮可以看到<b>所有方案</b>正在执行的批次及实时进度；
            点「前往」会自动切换到对应方案继续跟踪，多人同时验收时互不影响。
          </p>
        </div>

        <div class="help-sec">
          <div class="hs-title">常见问题</div>
          <div class="hs-qa">
            <p><b>顶部出现"离线数据"标签？</b>说明后端未启动或接口不通，当前展示的是离线缓存数据，不是真实采集。请确认后端在 8080 运行后刷新页面。</p>
            <p><b>检查项采集失败？</b>先在设备管理页确认目标设备在线、可远程管理；AC-1 走 Telnet（23 端口），其余设备走 SSH。</p>
            <p><b>批次停在"等待人工确认"？</b>这是正常环节（如故障预演项）：点开该项 → 确认执行/录入人工实测结论，批次才会继续收尾。</p>
            <p><b>报告页按钮灰色？</b>只有状态为"已完成"的批次才能出具报告；中断/失败的批次可删除后重跑。</p>
          </div>
        </div>
      </div>
    </el-drawer>

    
    <el-dialog
      v-model="confirmDialog"
      :title="`人工确认 · ${confirmTarget?.code || ''} ${confirmTarget?.title || ''}`"
      width="480px"
      :close-on-click-modal="false"
    >
      <div class="confirm-dlg">
        <p class="cd-tip">
          该项会中断接口或影响业务，请由负责人现场实测后录入结论；实测说明将写入批次记录与验收报告。
        </p>
        <el-form label-width="88px" size="small">
          <el-form-item label="实测结论">
            <el-radio-group v-model="confirmForm.decision">
              <el-radio value="pass">实测通过</el-radio>
              <el-radio value="fail">实测不通过</el-radio>
              <el-radio value="skip">跳过（未执行）</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="实测说明" :required="confirmForm.decision !== 'skip'">
            <el-input
              v-model="confirmForm.note"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              placeholder="如：已人工断开 GE0/0/1 验证倒换，VRRP 主备切换正常，切换耗时约 3s"
            />
          </el-form-item>
          <el-form-item label="执行人">
            <span class="cd-op">{{ auth.user?.realName || auth.user?.username || '-' }}</span>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button size="small" @click="confirmDialog = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitConfirm">记录结论</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="runDialog" title="本次验收" width="420px" :close-on-click-modal="false">
      <div class="run-dlg">
        <p class="rd-line">
          将执行 <strong>{{ enabledCount }}</strong> 项检查，串行采集，预计
          <strong>{{ Math.max(1, Math.round(enabledCount * 1.1)) }}</strong> 秒。
        </p>
        <p class="rd-tip">
          结果按项实时点亮；带「需人工确认」的项会停在 PENDING，等待人工确认后才计入结论。
        </p>
      </div>
      <template #footer>
        <el-button size="small" @click="runDialog = false">取消</el-button>
        <el-button size="small" type="primary" :loading="running" @click="startRun">开始</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { InfoFilled, UserFilled } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import RunResultPanel from './components/RunResultPanel.vue'
import TwinTopology from './components/TwinTopology.vue'
import { acceptanceApi, mockMode } from '@/api/acceptance'
import { describeCheck, metricsText } from './components/judgeRule'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'

const projectStore = useProjectStore()
const { projects, projectId } = storeToRefs(projectStore)
const plans = ref([])
const members = ref([])
const membersLoading = ref(false)
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

// owner（username）→ 真实姓名；未匹配时原样显示
function memberName(username) {
  const m = members.value.find((x) => x.username === username)
  return m ? m.realName : username
}
const checkTypes = ref([])
const planId = ref(null)
const planDetail = ref(null)
const selectedCheckId = ref(null)

const batches = ref([])
const batchId = ref(null)
const batch = ref({ items: [] })

const running = ref(false)
const dryRunning = ref(false)
const runDialog = ref(false)

const rawVisible = ref(false)
const rawItem = ref(null)
let pollTimer = null

// 右栏视图：运行结果 / 数字孪生
const rightTab = ref('result')

const detailVisible = ref(false)
const detailTitle = computed(() => {
  const c = checks.value.find((x) => x.id === selectedCheckId.value)
  return c ? `${c.code} · ${c.title}` : '检查项详情'
})

function selectCheck(check) {
  selectedCheckId.value = check.id
  detailVisible.value = true
}

const currentItem = computed(() =>
  (batch.value?.items || []).find((i) => i.checkId === selectedCheck.value?.id) || null
)

const paramRows = computed(() => {
  const check = selectedCheck.value
  const params = check?.params
  if (!params || typeof params !== 'object') return []
  const fields = checkTypes.value.find((t) => t.type === check.type)?.fields || []
  return Object.entries(params)
    .filter(([, v]) => v !== null && v !== undefined)
    .map(([k, v]) => {
      const f = fields.find((x) => x.key === k)
      return { k: f?.label || k, v: paramValueText(f, v) }
    })
})

function paramValueText(field, v) {
  const optLabel = (x) => {
    const o = (field?.options || []).find((op) => String(op.value) === String(x))
    return o ? o.label : null
  }
  if (Array.isArray(v)) return v.map((x) => optLabel(x) ?? String(x)).join('、')
  if (typeof v === 'object') return JSON.stringify(v)
  return optLabel(v) ?? String(v)
}

const ITEM_STATE = {
  PASS: '通过',
  FINDING: '待整改',
  FAIL: '不通过',
  SKIPPED: '已跳过',
  PENDING: '待确认',
  WAITING: '排队中',
  RUNNING: '执行中'
}

function stateOf(check) {
  const it = (batch.value?.items || []).find((i) => i.checkId === check.id)
  if (it) return ITEM_STATE[it.status] || it.status
  if (!check.enabled) return '未启用'
  return '未执行'
}

function stateTone(check) {
  const it = (batch.value?.items || []).find((i) => i.checkId === check.id)
  if (!it) return 'st-muted'
  if (it.status === 'PASS') return 'st-ok'
  if (it.status === 'FINDING') return 'st-warn'
  if (it.status === 'FAIL') return 'st-danger'
  if (it.status === 'RUNNING' || it.status === 'WAITING') return 'st-info'
  return 'st-muted'
}

function checkTypeLabel(type) {
  return checkTypes.value.find((t) => t.type === type)?.label || type || '—'
}

function judgeText(check) {
  return describeCheck(check, checkTypes.value)
}

function briefMetrics(metrics, type) {
  return metricsText(type, metrics)
}

const globalRunning = ref([])
const runningVisible = ref(false)
let globalTimer = null

const helpVisible = ref(false)

// 当前处于四步流程的哪一步：0选方案1检查项2运行3出报告
const activeStep = computed(() => {
  if (!planId.value) return 0
  if (!enabledCount.value) return 1
  if (batch.value?.status === 'DONE' || batch.value?.status === 'VOIDED') return 3
  return 2
})

// 下一步提示：告诉用户"现在该做什么"
const nextHint = computed(() => {
  if (!planId.value) return '第一步：在右上角选择工程与验收方案（没有方案请到「方案设计」页新建）'
  if (!enabledCount.value) return '该方案没有启用的检查项，请到「方案设计」页开启后再运行'
  const st = batch.value?.status
  if (st === 'RUNNING') {
    const cur = batch.value?.progress?.current
    return cur ? `验收执行中：正在「${cur}」，可展开结果区逐项查看实测数据` : '验收执行中，右侧结果区将逐项点亮'
  }
  if (st === 'DONE') {
    return (batch.value?.items || []).some((i) => i.status === 'FINDING' || i.status === 'FAIL')
      ? '验收完成且存在待整改项：展开 FINDING/FAIL 项查看详情，整改情况写入报告页'
      : '验收完成且全部通过：到「验收报告」页出具 Word 报告，或直接发起下一轮验收'
  }
  if (st === 'INTERRUPTED' || st === 'FAILED') return '上次批次已中断/失败：可删除后重新点「运行」'
  if (st === 'VOIDED') return '当前批次已作废：选择其他批次查看，或重新发起验收'
  if (batch.value?.batchId) return '方案已就绪：建议先「试运行」验证单项，再点「运行」开始整体验收'
  return '方案已就绪：先「试运行」验证单项，再点「运行」开始整体验收'
})

async function refreshGlobalRunning() {
  try {
    globalRunning.value = await acceptanceApi.listRunningBatches()
  } catch {
    
  }
}

function openRunningDialog() {
  runningVisible.value = true
  refreshGlobalRunning()
}

async function goToRunningBatch(row) {
  runningVisible.value = false
  try {
    if (row.planProjectId && row.planProjectId !== projectId.value) {
      projectId.value = row.planProjectId
      plans.value = await acceptanceApi.listPlans(projectId.value)
    }
    planId.value = row.planId
    await loadPlanDetail()
    batchId.value = row.batchId
    batch.value = await acceptanceApi.getBatch(row.batchId)
    if (batch.value?.status === 'RUNNING') {
      running.value = true
      startPolling(row.batchId)
    }
  } catch (e) {
    ElMessage.error('跳转失败：' + (e?.message || e))
  }
}

// 本地调试辅助：?demo=run自动运行一次；?demo=inspect运行后展开FINDING项的原始输出
const demoMode = new URLSearchParams(window.location.search).get('demo') || ''

const checks = computed(() => planDetail.value?.checks || [])
const enabledCount = computed(() => checks.value.filter((c) => c.enabled).length)
const selectedCheck = computed(() => checks.value.find((c) => c.id === selectedCheckId.value) || null)

const planMeta = computed(() => {
  const p = projects.value.find((x) => x.id === projectId.value)
  return p ? { projectName: p.name } : null
})

const rawTitle = computed(() => (rawItem.value ? `原始输出 · ${rawItem.value.code} ${rawItem.value.title}` : '原始输出'))

const batchOptions = computed(() => {
  const list = [...batches.value]
  if (batch.value?.batchId && !list.some((b) => b.batchId === batch.value.batchId)) {
    list.unshift({ ...batch.value })
  }
  return list
})

function severityLabel(s) {
  return { critical: '致命', high: '高', medium: '中', low: '低' }[s] || s
}

function batchStatusText(b) {
  // 批次含待人工确认项且采集已结束时，不再笼统显示"运行中"
  if (b.status === 'RUNNING') {
    const items = b.items || []
    const hasWaiting = items.some((i) => i.status === 'WAITING')
    const hasPending = items.some((i) => i.status === 'PENDING')
    if (!hasWaiting && hasPending) return '等待人工确认'
    return '运行中'
  }
  return {
    DONE: '已完成',
    FAILED: '执行失败',
    INTERRUPTED: '已中断',
    VOIDED: '已作废'
  }[b.status] || '未知'
}

async function loadProjects(preferId = null) {
  const before = projectId.value
  if (preferId != null && preferId !== before) projectStore.switchTo(preferId)
  await projectStore.load(true)
  if (!projects.value.length) {
    resetPlanState()
    return
  }
  if (projectId.value === before) await loadPlans()
}

// 顶栏切换工程 → 重新加载该工程的方案与批次
watch(
  () => projectStore.projectId,
  async (id) => {
    if (!projects.value.length) await projectStore.load(true)
    if (id) await loadPlans()
    else resetPlanState()
  }
)

async function loadPlans() {
  plans.value = await acceptanceApi.listPlans(projectId.value)
  if (plans.value.length) {
    // 切工程时保留仍属于该工程的方案，否则取第一个
    planId.value = plans.value.some((p) => p.id === planId.value) ? planId.value : plans.value[0].id
    await loadPlanDetail()
  } else {
    resetPlanState()
  }
}

function resetPlanState() {
  planId.value = null
  planDetail.value = null
  selectedCheckId.value = null
  batches.value = []
  batchId.value = null
  batch.value = { items: [] }
}

async function loadPlanDetail() {
  const plan = await acceptanceApi.getPlan(planId.value)
  // 兜底：后端若只返回checksJson字符串，就地解析成checks数组
  if (!plan.checks && plan.checksJson) {
    try {
      plan.checks = JSON.parse(plan.checksJson)
    } catch (e) {
      plan.checks = []
    }
  }
  if (!Array.isArray(plan.checks)) plan.checks = []
  planDetail.value = plan
  const first = planDetail.value.checks[0]
  selectedCheckId.value = first ? first.id : null
  await loadBatches()
}

async function loadBatches() {
  const list = await acceptanceApi.listBatches(planId.value)
  batches.value = list || []
  // 运行刚结束时保留当前批次；首次进入优先选最近一次已收尾的批次
  const keep = batches.value.find((b) => b.batchId === batchId.value)
  const latestSettled = [...batches.value].reverse().find((b) => b.status !== 'RUNNING')
  const target = keep || latestSettled || batches.value[0]
  if (target) {
    batchId.value = target.batchId
    batch.value = await acceptanceApi.getBatch(target.batchId)
  } else {
    batchId.value = null
    batch.value = { items: [] }
  }
}

function goPlanDesign() {
  router.push('/acceptance/plan')
}

async function doDryRun() {
  if (!selectedCheck.value) {
    ElMessage.warning('先选择一个检查项')
    return
  }
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

function doRun() {
  if (!enabledCount.value) {
    ElMessage.warning('没有启用的检查项')
    return
  }
  runDialog.value = true
}

async function startRun() {
  running.value = true
  prevPending.value = 0
  try {
    const r = await acceptanceApi.runPlan(planId.value)
    runDialog.value = false
    batchId.value = r.batchId
    batch.value = await acceptanceApi.getBatch(r.batchId)
    startPolling(r.batchId)
  } catch (e) {
    running.value = false
    ElMessage.error('启动失败：' + (e?.message || e))
  }
}

function startPolling(id) {
  stopPolling()
  const tick = async () => {
    try {
      batch.value = await acceptanceApi.getBatch(id)
      const st = batch.value.status
      if (st !== 'RUNNING') {
        running.value = false
        stopPolling()
        await loadBatches()
        if (st === 'DONE') {
          const warn = (batch.value.items || []).some((i) => i.status === 'FINDING' || i.status === 'FAIL')
          ElMessage({
            type: warn ? 'warning' : 'success',
            message: warn ? '验收完成，存在待整改项，报告将列出问题清单' : '验收完成，全部通过'
          })
        } else if (st === 'INTERRUPTED') {
          ElMessage.warning(batch.value.statusMessage || '批次已中断，请重新发起验收')
        } else if (st === 'FAILED') {
          ElMessage.error(batch.value.statusMessage || '批次执行失败')
        }
        if (demoMode === 'inspect') {
          const target = (batch.value.items || []).find((i) => i.status === 'FINDING') || (batch.value.items || [])[0]
          if (target) openRaw(target)
        }
        return
      }
    } catch {
      running.value = false
      stopPolling()
      return
    }
    // 有项在等人工确认时放慢轮询，避免空转
    const pend = batch.value.progress?.pending || 0
    if (pend > prevPending.value) {
      const firstPending = (batch.value.items || []).find((i) => i.status === 'PENDING')
      notifyConfirm(firstPending)
    }
    prevPending.value = pend
    const waiting = pend > 0
    pollTimer = setTimeout(tick, waiting ? 1500 : 500)
  }
  tick()
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

async function doStop() {
  try {
    await ElMessageBox.confirm(
      '终止后未执行的检查项将标记为“已跳过”，已采集的结果保留。确定终止本次验收？',
      '停止运行',
      { type: 'warning', confirmButtonText: '终止批次', cancelButtonText: '继续运行' }
    )
  } catch {
    return
  }
  try {
    batch.value = await acceptanceApi.stopBatch(batchId.value)
    ElMessage.success('已发出终止指令，未执行项将标记跳过')
  } catch (e) {
    ElMessage.error('终止失败：' + (e?.message || e))
  }
}

// 人工确认：表单化
const confirmDialog = ref(false)
const confirmTarget = ref(null)
const confirmForm = reactive({ decision: 'pass', note: '' })

function doConfirm(item) {
  confirmTarget.value = item
  confirmForm.decision = 'pass'
  confirmForm.note = ''
  confirmDialog.value = true
}

async function submitConfirm() {
  const it = confirmTarget.value
  if (!it) return
  if ((confirmForm.decision === 'pass' || confirmForm.decision === 'fail') && !confirmForm.note.trim()) {
    ElMessage.warning('请填写实测说明，作为验收记录')
    return
  }
  try {
    batch.value = await acceptanceApi.confirmItem(batchId.value, it.checkId, {
      decision: confirmForm.decision,
      note: confirmForm.note.trim(),
      operator: auth.user?.username || ''
    })
    confirmDialog.value = false
    ElMessage.success('已记录人工确认')
  } catch (e) {
    ElMessage.error('确认失败：' + (e?.message || e))
  }
}

// 确认提醒：pending数量上升时弹通知；页面在后台时用浏览器通知
const prevPending = ref(0)

function notifyConfirm(item) {
  const no = batch.value?.no || ''
  const text = item ? `「${item.code} ${item.title}」等待人工确认` : '有检查项等待人工确认'
  ElNotification({
    title: '等待人工确认',
    message: `${no} ${text}，请在结果面板展开该项录入实测结论`,
    type: 'warning',
    duration: 10000
  })
  if (document.hidden && 'Notification' in window) {
    if (Notification.permission === 'granted') {
      new Notification('验收批次等待人工确认', { body: `${no} ${text}` })
    } else if (Notification.permission !== 'denied') {
      Notification.requestPermission().then((p) => {
        if (p === 'granted') new Notification('验收批次等待人工确认', { body: `${no} ${text}` })
      })
    }
  }
}

// 作废 / 删除：DONE只可作废，INTERRUPTED/FAILED可删除
async function doVoid() {
  try {
    const { value } = await ElMessageBox.prompt(
      '作废后该批次不可再出报告，记录与编号保留以备追溯。请填写作废原因：',
      '作废批次',
      {
        type: 'warning',
        confirmButtonText: '作废',
        cancelButtonText: '取消',
        inputPlaceholder: '如：数据有误，需重新验收',
        inputValidator: (v) => (!!v && !!v.trim()) || '请填写作废原因'
      }
    )
    batch.value = await acceptanceApi.voidBatch(batchId.value, value.trim())
    ElMessage.success('批次已作废')
    loadBatches()
  } catch {
    // 取消
  }
}

async function doDelete() {
  try {
    await ElMessageBox.confirm(
      '删除后该批次及其快照不可恢复。确定删除？',
      '删除批次',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await acceptanceApi.deleteBatch(batchId.value)
    ElMessage.success('批次已删除')
    await loadBatches()
  } catch {
    // 取消
  }
}

function openRaw(item) {
  rawItem.value = item
  rawVisible.value = true
}

function goReport() {
  router.push('/acceptance/report')
}

onMounted(async () => {
  checkTypes.value = await acceptanceApi.checkTypes()
  // 成员列表：失败不阻塞主流程
  try {
    membersLoading.value = true
    members.value = await acceptanceApi.members()
  } catch (e) {
    
  } finally {
    membersLoading.value = false
  }
  await loadProjects(Number(route.query.projectId) || null)
  // 进入页面时若选中的是执行中的批次，继续跟进度
  if (batch.value?.status === 'RUNNING' && batch.value?.batchId) {
    running.value = true
    startPolling(batch.value.batchId)
  }
  // 全局运行指示器：4秒一轮，跨方案跟踪所有执行中的验收批次
  refreshGlobalRunning()
  globalTimer = setInterval(refreshGlobalRunning, 4000)
  if (demoMode === 'run' || demoMode === 'inspect') {
    const r = await acceptanceApi.runPlan(planId.value)
    batchId.value = r.batchId
    batch.value = await acceptanceApi.getBatch(r.batchId)
    startPolling(r.batchId)
  }
})

onBeforeUnmount(() => {
  stopPolling()
  if (globalTimer) {
    clearInterval(globalTimer)
    globalTimer = null
  }
})
</script>

<style scoped lang="scss">
.acceptance-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.mock-tag {
  margin-right: 2px;
}
.who-tag {
  margin-right: 4px;
  .who-icon {
    margin-right: 3px;
    vertical-align: -2px;
  }
}
.run-badge {
  margin-right: 8px;
  :deep(.el-badge__content) {
    z-index: 2;
  }
}
.guide-strip {
  flex: 0 0 auto;
  padding: 6px 16px 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);

  .guide-steps {
    :deep(.el-step__title) {
      font-size: 12px;
      font-weight: 400;
    }
    :deep(.el-step__head.is-finish .el-step__line) {
      border-color: var(--el-color-success-light-5);
    }
  }
  .guide-hint {
    display: flex;
    align-items: center;
    gap: 5px;
    margin-top: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);

    .gh-icon {
      color: var(--el-color-primary);
      font-size: 13px;
    }
    .gh-help {
      padding: 0;
      margin-left: 2px;
    }
  }
}
.help-body {
  padding: 0 4px;

  .help-sec {
    margin-bottom: 18px;
  }
  .hs-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--el-color-primary);
    margin-bottom: 8px;
    padding-left: 8px;
    border-left: 3px solid var(--el-color-primary);
  }
  .hs-p {
    margin: 0;
    font-size: 12.5px;
    line-height: 1.8;
    color: var(--el-text-color-regular);
  }
  .hs-steps {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .hs-step {
    font-size: 12.5px;
    line-height: 1.7;
    color: var(--el-text-color-regular);
    padding: 8px 10px;
    background: var(--el-fill-color-lighter);
    border-radius: 6px;
  }
  .hs-tags {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .hs-tag {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    color: var(--el-text-color-regular);
  }
  .hs-qa {
    p {
      margin: 0 0 8px;
      font-size: 12px;
      line-height: 1.7;
      color: var(--el-text-color-regular);
    }
  }
}
.gr-progress {
  display: flex;
  align-items: center;
  gap: 6px;

  .el-progress {
    flex: 1;
  }
  .gr-num {
    font-size: 11px;
    color: var(--nms-text-muted, #909399);
    white-space: nowrap;
  }
}
.run-dialog-tip {
  margin-top: 10px;
  font-size: 12px;
  color: var(--nms-text-muted, #909399);
}
.mine-badge {
  display: inline-block;
  margin-left: 4px;
  padding: 0 4px;
  font-size: 10px;
  font-style: normal;
  line-height: 14px;
  color: #fff;
  background: var(--el-color-primary);
  border-radius: 3px;
}
.proj-tag {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sel-plan {
  width: 186px;
}

.acc-actions {
  gap: 0;
  align-items: center;
}
.acc-actions .act-group {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;

  & + .act-group {
    border-left: 1px solid var(--el-border-color-lighter);
  }
  &:last-child {
    padding-right: 0;
  }
}
.acc-actions :deep(.el-button:not(.is-circle)),
.acc-actions :deep(.el-select__wrapper) {
  height: 30px;
}
.acc-actions :deep(.el-button.is-circle) {
  width: 30px;
  height: 30px;
}
.proj-mgr .pm-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.proj-mgr .pm-tip {
  font-size: 12px;
  color: var(--el-color-danger);
  opacity: 0.85;
}
.proj-form .pf-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 6px;
}
.tpl-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.acc-layout {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 12px;
}
.acc-left {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
  overflow: hidden;
}
.list-head {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);

  .lh-title {
    font-size: 13px;
    font-weight: 500;
    color: var(--el-text-color-primary);
  }
  .lh-count {
    font-size: 11px;
    font-family: Consolas, Monaco, monospace;
    color: var(--el-text-color-secondary);
    margin-left: 6px;
  }
  .lh-add {
    font-size: 12px;
  }
}
.check-list {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.check-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 8px 10px 7px;
  cursor: pointer;
  background: var(--el-bg-color);
  transition: border-color 0.15s, background 0.15s;

  &:hover {
    border-color: var(--el-color-primary-light-5);
    background: var(--el-fill-color-lighter);
  }
  &.active {
    border-color: var(--el-color-primary);
    background: var(--el-color-primary-light-9);
  }
  &.off {
    opacity: 0.55;
  }

  .cc-top {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }
  .cc-code {
    flex: 0 0 auto;
    padding: 1px 6px;
    border-radius: 4px;
    background: var(--el-fill-color);
    font-family: Consolas, Monaco, monospace;
    font-size: 11.5px;
    line-height: 16px;
    color: var(--el-text-color-secondary);
  }
  .cc-title {
    flex: 1 1 auto;
    min-width: 0;
    font-size: 14px;
    color: var(--el-text-color-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .cc-sw {
    flex: 0 0 auto;
  }
  .cc-bottom {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 7px;
  }
  .cc-owner {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    &.mine {
      color: var(--el-color-primary);
    }
  }
  .cc-sev {
    font-size: 11px;
    padding: 0 6px;
    border-radius: 3px;
    line-height: 17px;

    &.sev-critical { color: var(--el-color-danger); background: var(--el-color-danger-light-9); }
    &.sev-high { color: var(--el-color-warning); background: var(--el-color-warning-light-9); }
    &.sev-medium { color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
    &.sev-low { color: var(--el-text-color-secondary); background: var(--el-fill-color); }
  }
  .cc-state {
    flex: 0 0 auto;
    font-size: 11px;
    padding: 0 6px;
    border-radius: 3px;
    line-height: 17px;
    background: var(--nms-bg-elevated);
    color: var(--nms-text-muted);
    white-space: nowrap;
  }
  .cc-state.st-ok { color: #0f7b4f; background: #eaf7f0; }
  .cc-state.st-warn { color: #b35c00; background: #fff5e6; }
  .cc-state.st-danger { color: #c00000; background: #fdecec; }
  .cc-state.st-info { color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
  .cc-off {
    margin-left: auto;
    font-size: 11.5px;
    color: var(--nms-text-muted);
  }
}

.ci-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.cid-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.cid-code {
  font-family: var(--nms-mono);
  font-size: 13px;
  font-weight: 600;
  color: var(--nms-text);
}
.cid-title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--nms-text);
  line-height: 1.4;
}
.cid-kv {
  border: 1px solid var(--nms-border-soft);
  border-radius: 6px;
  overflow: hidden;
}
.cid-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 8px 12px;
  font-size: 13px;
  border-bottom: 1px solid var(--nms-border-soft);

  &:last-child {
    border-bottom: none;
  }

  span {
    flex: 0 0 88px;
    color: var(--nms-text-secondary);
  }

  b {
    flex: 1;
    min-width: 0;
    font-weight: 500;
    word-break: break-all;
    color: var(--nms-text);
  }
}
.cid-sec {
  h4 {
    margin: 0 0 6px;
    font-size: 13px;
    font-weight: 600;
    color: var(--nms-text);
  }
}
.cid-rule {
  margin: 0;
  padding: 10px 12px;
  background: #f7f9fc;
  border: 1px solid var(--nms-border-soft);
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.7;
  color: #1d39c4;
  font-weight: 600;
}
.cid-msg {
  margin: 0 0 6px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--nms-text-secondary);
}
.cid-metric {
  margin: 0 0 8px;
  font-size: 12.5px;
  color: var(--nms-text-secondary);
  word-break: break-all;
}
.cid-empty {
  margin: 0;
  font-size: 12.5px;
  color: var(--nms-text-muted);
}
.cid-tip {
  margin-top: 6px;
  padding: 10px 12px;
  background: var(--nms-bg-elevated);
  border-radius: 6px;
  font-size: 12.5px;
  color: var(--nms-text-secondary);
  line-height: 1.6;
}
.mono {
  font-family: var(--nms-mono);
}
.list-foot {
  flex: 0 0 auto;
  padding: 6px 10px 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
  text-align: center;
}
.acc-right {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
  padding: 10px 12px 12px;
  overflow: hidden;
}
.no-plan-tip {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  padding: 8px 14px;
  margin-bottom: 8px;
  border: 1px solid var(--el-color-warning-light-7);
  background: var(--el-color-warning-light-9);
  border-radius: 6px;
  font-size: 13px;
  color: var(--nms-text-secondary);

  :deep(.el-button) {
    font-size: 13px;
    vertical-align: baseline;
  }
}

.npt-or {
  color: var(--nms-text-muted);
}

.batch-bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 8px;

  .bb-label {
    font-size: 12.5px;
    color: var(--el-text-color-secondary);
  }
  .bb-sel {
    width: 240px;
  }
  .bb-meta {
    font-size: 11.5px;
    color: var(--el-text-color-placeholder);
    margin-left: auto;
  }
}

.acc-tabs {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;

  :deep(.el-tabs__header) {
    margin: 0 0 8px;
  }
  :deep(.el-tabs__content) {
    flex: 1 1 auto;
    min-height: 0;
    overflow: hidden;
  }
  :deep(.el-tab-pane) {
    height: 100%;
  }
}
.result-wrap {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.twin-wrap {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
}
.raw-body {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.raw-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 8px;

  .rm-code {
    font-family: Consolas, Monaco, monospace;
    font-size: 12px;
    color: var(--el-text-color-regular);
  }
  .rm-dur {
    font-size: 11px;
    color: var(--el-text-color-placeholder);
    margin-left: auto;
  }
}
.raw-pre {
  flex: 1 1 auto;
  margin: 0;
  padding: 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
  word-break: break-all;
  overflow-y: auto;
}
.raw-note {
  flex: 0 0 auto;
  padding-top: 10px;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  line-height: 1.6;
}
.run-dlg {
  .rd-line {
    font-size: 13px;
    color: var(--el-text-color-primary);
    margin: 0 0 8px;
  }
  .rd-tip {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    line-height: 1.7;
    margin: 0;
  }
}
.confirm-dlg {
  .cd-tip {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    line-height: 1.7;
    margin: 0 0 12px;
    padding: 8px 10px;
    border-radius: 4px;
    background: var(--el-color-warning-light-9);
    color: var(--el-color-warning-dark-2);
  }
  .cd-op {
    font-size: 12px;
    color: var(--el-text-color-primary);
  }
}
</style>
