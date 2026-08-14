<template>
  <div class="aiops-workbench" v-loading="loading">
    <div class="wb-header">
      <div>
        <h2>事件处置</h2>
        <p class="subtitle">规则感知 · LLM 编排 · 白名单执行</p>
      </div>
      <div class="header-actions">
        <el-radio-group
          v-model="opsMode"
          size="small"
          class="ops-mode-group"
          @change="onOpsModeChange"
        >
          <el-radio-button label="manual">人工运维</el-radio-button>
          <el-radio-button label="unattended">无人值守</el-radio-button>
        </el-radio-group>
        <el-button @click="$router.push('/aiops/automation')">自动化运营</el-button>
        <el-button
          v-if="secondaryCount > 0"
          type="warning"
          :loading="ackingSecondary"
          title="批量处理关联告警：阅知类直接关闭，其余进入处理中（不改设备配置）"
          @click="ackAllSecondary"
        >
          处理关联告警 ({{ secondaryCount }})
        </el-button>
        <el-button @click="$router.push('/aiops/policy')">策略</el-button>
        <el-button :loading="loading" @click="refresh">刷新</el-button>
        <el-button
          v-if="auth.hasAnyPermission('aiops:read', 'alarms:read')"
          type="primary"
          plain
          @click="openOpsAssist"
        >运维辅助</el-button>
        <el-button
          type="primary"
          plain
          :loading="detecting"
          title="检测设备性能是否偏离近期基线"
          @click="runDetect"
        >基线检测</el-button>
        <el-button
          type="primary"
          :loading="inspecting"
          title="立即重算关联与基线；无人值守模式下由 LLM 规划并自动执行安全动作"
          @click="runInspect"
        >智能巡检</el-button>
      </div>
    </div>

    <div class="wb-kpi">
      <div class="kpi-chip" title="按设备在线与未关闭告警（待处理+处理中）评估；关闭后分数才回升">
        <span class="kpi-n" :class="health.level">{{ health.networkScore ?? '-' }}</span>
        <span class="kpi-l">健康分</span>
      </div>
      <div class="kpi-chip" title="状态为待处理（ACTIVE）的代表事件">
        <span class="kpi-n">{{ pendingIncidentCount }}</span>
        <span class="kpi-l">待处理事件</span>
      </div>
      <div class="kpi-chip">
        <span class="kpi-n">{{ secondaryCount }}</span>
        <span class="kpi-l">可处理关联告警</span>
      </div>
      <div class="kpi-chip">
        <span class="kpi-n">{{ (rca.candidates || []).length }}</span>
        <span class="kpi-l">可疑根因</span>
      </div>
      <div class="kpi-chip grow">
        <span class="kpi-story">{{ story.headline || '就绪：选择左侧事件开始处置' }}</span>
        <span v-if="storyDetailShort" class="kpi-story-detail">{{ storyDetailShort }}</span>
      </div>
    </div>

    <div
      v-if="suggestions.filter(s => s.executable !== false && s.action !== 'noop').length"
      class="wb-suggest"
    >
      <span class="wb-suggest-label">快捷处置</span>
      <el-button
        v-for="(s, i) in suggestions.filter(x => x.executable !== false && x.action !== 'noop').slice(0, 4)"
        :key="s.code + i"
        size="small"
        :type="s.priority === 'high' ? 'danger' : 'primary'"
        plain
        :loading="acting"
        @click="runSuggestion(s)"
      >{{ s.text?.slice(0, 28) }}{{ (s.text || '').length > 28 ? '…' : '' }}</el-button>
    </div>

    <div class="wb-main">
      <!-- 左：事件队列 -->
      <section class="wb-col wb-left">
        <div class="wb-col-title">
          <span>事件列表</span>
          <span class="wb-col-meta">{{ filteredIncidents.length }}/{{ incidents.length }}</span>
        </div>
        <div class="incident-toolbar">
          <el-radio-group v-model="incidentFilter" size="small">
            <el-radio-button label="pending">待处理</el-radio-button>
            <el-radio-button label="in_progress">处理中</el-radio-button>
            <el-radio-button label="closed">已关闭</el-radio-button>
            <el-radio-button label="all">全部</el-radio-button>
          </el-radio-group>
          <el-select v-model="incidentSort" size="small" class="incident-sort">
            <el-option label="最新优先" value="time_desc" />
            <el-option label="最早优先" value="time_asc" />
            <el-option label="级别优先" value="severity" />
          </el-select>
        </div>
        <el-table
          :data="filteredIncidents"
          size="small"
          height="100%"
          row-key="id"
          highlight-current-row
          :row-class-name="incidentRowClass"
          :empty-text="incidentEmptyText"
          @row-click="selectFocus"
        >
          <el-table-column label="级别" width="64">
            <template #default="{ row }">
              <el-tag :type="severityType(row.severity)" size="small">{{ severityText(row.severity) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="事件" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="incident-title-cell">
                <el-tag
                  v-if="incidentPhase(row) === 'closed'"
                  size="small"
                  type="success"
                  effect="plain"
                  class="incident-status-tag"
                >已关闭</el-tag>
                <el-tag
                  v-else-if="incidentPhase(row) === 'in_progress'"
                  size="small"
                  type="warning"
                  effect="plain"
                  class="incident-status-tag"
                >处理中</el-tag>
                <el-tag
                  v-else-if="Number(row.childCount) > 0"
                  size="small"
                  type="warning"
                  effect="plain"
                  class="incident-status-tag"
                >×{{ Number(row.childCount) + 1 }}</el-tag>
                <span class="incident-title-text">{{ row.title }}</span>
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="deviceName" label="设备" width="90" show-overflow-tooltip />
        </el-table>

        <div class="wb-col-title" style="margin-top: 12px">可疑根因</div>
        <div class="rca-list">
          <div
            v-for="c in (rca.candidates || []).slice(0, 5)"
            :key="c.deviceId"
            class="rca-mini"
          >
            <div class="rca-mini-main" @click="selectDeviceFocus(c)">
              <div class="rca-mini-title">{{ c.name }} <el-tag size="small">{{ c.score }}</el-tag></div>
              <div class="rca-mini-desc">{{ c.reason }}</div>
            </div>
            <el-button
              v-if="auth.hasPermission('topology:read')"
              size="small"
              link
              type="primary"
              @click.stop="openRcaOnTopology(c)"
            >拓扑影响面</el-button>
          </div>
          <el-empty v-if="!(rca.candidates || []).length" description="暂无可疑根因" :image-size="40" />
        </div>
      </section>

      <!-- 中：作业区 -->
      <section class="wb-col wb-center">
        <div class="wb-col-title sticky-title">当前作业</div>
        <div class="wb-center-scroll">
          <div v-if="inspectEvidence && Object.keys(inspectEvidence).length" class="evidence-card">
            <div class="evidence-card-head">
              <span>本次巡检算了什么</span>
              <el-tag size="small" type="info">{{ inspectEvidence.engineLabel || '规则引擎' }}</el-tag>
            </div>
            <div class="evidence-metrics">
              <span>代表事件 {{ inspectEvidence.representativeCount ?? '-' }}</span>
              <span>风暴组 {{ inspectEvidence.stormGroups ?? '-' }}</span>
              <span>连带 {{ inspectEvidence.secondaryMarked ?? '-' }}</span>
              <span>抑制 {{ inspectEvidence.suppressedCount ?? '-' }}</span>
              <span>基线异常 {{ inspectEvidence.baselineAnomalies ?? '-' }}</span>
              <span v-if="inspectEvidence.ackClosesAutoClosed">阅知关闭 {{ inspectEvidence.ackClosesAutoClosed }}</span>
            </div>
            <ul v-if="inspectReportLines.length" class="inspect-lines">
              <li v-for="(line, i) in inspectReportLines" :key="i">{{ line }}</li>
            </ul>
          </div>
          <template v-if="focus">
            <div v-loading="focusLoading" class="plan-card">
              <div class="plan-card-head">
                <el-tag size="small" type="primary">{{ plan.scenarioLabel || '处置方案' }}</el-tag>
                <el-tag v-if="focusClosed" size="small" type="success">已关闭</el-tag>
                <el-tag v-else-if="focusInProgress" size="small" type="warning">处理中</el-tag>
                <el-tag v-if="plan.ackCloses" size="small">阅知类</el-tag>
                <span class="plan-card-title">{{ focus.title || focus.name || '-' }}</span>
              </div>
              <p class="plan-summary">{{ plan.summary || focus.correlationNote || focus.reason || '加载处置方案…' }}</p>
              <p v-if="plan.hint" class="plan-hint">{{ plan.hint }}</p>
              <div class="plan-impact" v-if="plan.impact">
                <span>关联告警 {{ plan.impact.childCount ?? 0 }} 条（未处理 {{ plan.impact.childActiveCount ?? 0 }}）</span>
                <span>同设备相关 {{ plan.impact.secondaryOnDevice ?? 0 }} 条</span>
                <span v-if="plan.impact.deviceStatus">设备 {{ deviceStatusText(plan.impact.deviceStatus) }}</span>
                <span v-if="plan.impact.lastProbeMethod">探测 {{ String(plan.impact.lastProbeMethod).toUpperCase() }}</span>
                <span v-if="plan.impact.onlineAfterSuccesses != null">
                  连续可达 {{ plan.impact.consecutiveSuccess ?? 0 }}/{{ plan.impact.onlineAfterSuccesses }}
                  <template v-if="plan.impact.suspectedRecovery">（疑似恢复）</template>
                  <template v-else-if="plan.impact.recoveryConfirmed">（已确认）</template>
                </span>
              </div>

              <div v-if="llmExplain" class="llm-explain">
                <div class="llm-explain-head">
                  <span>智能解读</span>
                  <el-tag size="small" :type="llmExplain.source === 'llm' ? 'success' : 'info'">
                    {{ llmExplain.source === 'llm' ? 'LLM' : '规则' }}
                  </el-tag>
                  <el-button link type="primary" size="small" :loading="llmLoading" @click="loadLlmExplain(true)">刷新</el-button>
                </div>
                <p class="llm-explain-body">{{ llmExplain.text }}</p>
              </div>

              <div v-if="primaryTool" class="primary-step">
                <div class="primary-step-label">下一步</div>
                <el-button
                  size="large"
                  :type="toolButtonType(primaryTool.name) === 'default' ? 'primary' : toolButtonType(primaryTool.name)"
                  :loading="toolLoading(primaryTool.name)"
                  @click="runRecommendedTool(primaryTool)"
                >{{ primaryTool.label || primaryTool.name }}</el-button>
                <p class="primary-step-detail">
                  {{ primaryTool.reason || primaryTool.description || primaryTool.detail || '' }}
                </p>
                <p v-if="opsMode === 'unattended'" class="primary-step-mode">当前无人值守：安全动作可自动执行；回滚仍需人工</p>
              </div>
              <div v-else-if="!focusClosed && focus.id" class="primary-step">
                <div class="primary-step-label">下一步</div>
                <el-button size="large" type="warning" :loading="disposing" @click="runStandardDispose">
                  标准处置本事件
                </el-button>
                <p class="primary-step-detail">触发网管探测：连续可达确认后关闭；单次可达为疑似恢复并进入「处理中」（不改配）</p>
              </div>

              <div v-if="secondaryTools.length" class="secondary-actions">
                <div class="secondary-actions-label">其它操作</div>
                <div class="wb-actions">
                  <el-button
                    v-for="(t, ti) in secondaryTools"
                    :key="(t.name || t.id) + '-' + ti"
                    size="small"
                    :type="toolButtonType(t.name)"
                    plain
                    :loading="toolLoading(t.name)"
                    @click="runRecommendedTool(t)"
                  >{{ t.label || t.name }}</el-button>
                </div>
              </div>
            </div>

            <el-collapse v-model="evidenceOpen" class="evidence-panel">
              <el-collapse-item title="排查依据" name="ev">
                <div v-if="evidence.childAlarms?.length" class="ev-block">
                  <div class="ev-block-title">
                    关联告警
                    <el-button
                      v-if="focus.id && !focusClosed"
                      link
                      size="small"
                      type="warning"
                      :loading="acting"
                      @click="runFocusTool('ack_noise')"
                    >批量确认</el-button>
                  </div>
                  <div v-for="c in evidence.childAlarms.slice(0, 8)" :key="c.id" class="ev-row">
                    <el-tag size="small" :type="c.status === 'CLEARED' ? 'success' : (c.status === 'ACKNOWLEDGED' ? 'warning' : 'info')">
                      {{ alarmStatusText(c.status) }}
                    </el-tag>
                    {{ c.title }}
                  </div>
                </div>
                <div v-if="evidence.performance" class="ev-block">
                  <div class="ev-block-title">最近性能</div>
                  <div class="ev-row">
                    CPU {{ evidence.performance.cpuUsage ?? '-' }}%
                    ({{ metricSourceText(evidence.performance.cpuSource) }})
                    · 内存 {{ evidence.performance.memoryUsage ?? '-' }}%
                    ({{ metricSourceText(evidence.performance.memorySource) }})
                  </div>
                </div>
                <div v-if="evidence.relatedChanges?.length" class="ev-block">
                  <div class="ev-block-title">相关配置变更</div>
                  <div v-for="(ch, ci) in evidence.relatedChanges.slice(0, 5)" :key="ci" class="ev-row">
                    {{ formatChange(ch) }}
                  </div>
                </div>
                <div v-if="evidence.rcaHints?.length" class="ev-block">
                  <div class="ev-block-title">根因依据</div>
                  <div v-for="(r, ri) in evidence.rcaHints" :key="ri" class="ev-row">
                    <strong>{{ r.name }}</strong> · {{ r.reason }}
                    <div v-if="r.evidence?.length" class="ev-sub">
                      <div v-for="(e, ei) in r.evidence.slice(0, 3)" :key="ei">· {{ e }}</div>
                    </div>
                  </div>
                </div>
                <el-empty
                  v-if="!evidence.childAlarms?.length && !evidence.performance && !evidence.relatedChanges?.length && !evidence.rcaHints?.length"
                  description="暂无排查依据"
                  :image-size="40"
                />
              </el-collapse-item>
            </el-collapse>

            <div class="work-log">
              <div class="work-log-head">
                <span>执行记录</span>
                <el-button v-if="workLog.length" link size="small" @click="workLog = []">清空</el-button>
              </div>
              <div v-if="workLog.length" class="work-log-body">
                <div v-for="(line, i) in workLog" :key="i" class="work-log-line">{{ line }}</div>
              </div>
              <div v-else class="work-log-empty">执行标准处置或其它动作后，结果会显示在这里</div>
            </div>

            <div v-if="lastDisposeResult" class="dispose-result-card">
              <div class="work-log-head">
                <span>最近处置结果</span>
                <el-button link size="small" @click="lastDisposeResult = null">关闭</el-button>
              </div>
              <div class="dispose-result-summary">{{ lastDisposeResult.message }}</div>
              <div v-if="lastDisposeResult.closeReason || lastDisposeResult.probeMethod" class="dispose-result-meta">
                <span v-if="lastDisposeResult.probeMethod">探测 {{ String(lastDisposeResult.probeMethod).toUpperCase() }}</span>
                <span v-if="lastDisposeResult.consecutiveSuccess != null">
                  连续可达 {{ lastDisposeResult.consecutiveSuccess }}/{{ lastDisposeResult.onlineAfterSuccesses ?? 2 }}
                </span>
                <span v-if="lastDisposeResult.closeReason">关闭原因：{{ lastDisposeResult.closeReason }}</span>
              </div>
              <div
                v-for="(st, i) in (lastDisposeResult.steps || [])"
                :key="i"
                class="dispose-result-step"
                :class="{ ok: st.ok, fail: st.ok === false }"
              >
                <div class="dispose-step-title">{{ i + 1 }}. {{ st.title }}</div>
                <div class="dispose-step-detail">{{ st.message }}</div>
              </div>
            </div>

            <div v-if="configPreview" class="config-preview">
              <div class="work-log-head">
                <span>running 配置预览</span>
                <el-button link size="small" @click="configPreview = ''">关闭</el-button>
              </div>
              <pre>{{ configPreview }}</pre>
            </div>

            <div class="wb-col-title" style="margin-top: 12px">事件时间线</div>
            <el-skeleton v-if="timelineLoading" :rows="3" animated />
            <el-timeline v-else-if="(timeline.events || []).length">
              <el-timeline-item
                v-for="(ev, i) in timeline.events"
                :key="i"
                :timestamp="formatTime(ev.at)"
                placement="top"
              >
                <div class="tl-title">{{ ev.title }}</div>
                <div class="tl-text">{{ ev.text }}</div>
              </el-timeline-item>
            </el-timeline>
            <el-empty v-else description="暂无时间线" :image-size="48" />
          </template>

          <div v-else class="guide-empty">
            <div class="guide-title">还没有选中事件</div>
            <ol class="guide-steps">
              <li>在左侧点选一条待处理事件</li>
              <li>看中间「下一步」主按钮说明</li>
              <li>右侧作业条可诊断 / 确认执行；结果写回本页</li>
            </ol>
            <p v-if="!pendingIncidentCount" class="guide-tip">若列表为空，请先点右上角「智能巡检」</p>
          </div>
        </div>
      </section>

      <!-- 右：智能辅助（默认收起） -->
      <OpsInlineShell storage-key="workbench" fill-parent class="wb-right" />
    </div>

    <el-dialog v-model="policyVisible" title="AIOps 策略" width="560px" destroy-on-close>
      <el-form label-width="140px" size="small">
        <el-form-item label="运维模式">
          <el-radio-group v-model="policyForm.llmOpsMode">
            <el-radio label="manual">人工运维</el-radio>
            <el-radio label="unattended">无人值守</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="无人值守每轮上限">
          <el-input-number v-model="policyForm.unattendedMaxPerCycle" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="单事件最多步数">
          <el-input-number v-model="policyForm.unattendedMaxSteps" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="自动处置冷却(分)">
          <el-input-number v-model="policyForm.unattendedCooldownMinutes" :min="1" :max="120" />
        </el-form-item>
        <el-form-item label="允许自动备份">
          <el-switch v-model="policyForm.unattendedAllowBackup" />
          <span class="policy-hint">回滚永远不允许自动</span>
        </el-form-item>
        <el-form-item label="关联后自动处置">
          <el-switch v-model="policyForm.unattendedOnCorrelate" />
          <span class="policy-hint">定时关联周期后触发无人值守（默认关）</span>
        </el-form-item>
        <el-form-item label="风暴窗口(分)">
          <el-input-number v-model="policyForm.stormWindowMinutes" :min="1" :max="120" />
        </el-form-item>
        <el-form-item label="链路窗口(分)">
          <el-input-number v-model="policyForm.linkWindowMinutes" :min="1" :max="120" />
        </el-form-item>
        <el-form-item label="基线样本数">
          <el-input-number v-model="policyForm.anomalyMinSamples" :min="3" :max="60" />
        </el-form-item>
        <el-form-item label="分析仿真指标">
          <el-switch v-model="policyForm.analyzeSimulatedMetrics" />
        </el-form-item>
        <el-form-item label="自动确认连带">
          <el-switch v-model="policyForm.autoAckSecondary" />
        </el-form-item>
        <el-form-item label="Webhook">
          <el-switch v-model="policyForm.webhookEnabled" />
        </el-form-item>
        <el-form-item label="Webhook URL">
          <el-input v-model="policyForm.webhookUrl" />
        </el-form-item>
        <el-form-item label="超时升级">
          <el-switch v-model="policyForm.escalationEnabled" />
          <span class="policy-hint">待处理超时后自动升一级</span>
        </el-form-item>
        <el-form-item label="升级阈值(分)">
          <el-input-number v-model="policyForm.escalationMinutes" :min="5" :max="1440" />
        </el-form-item>
        <el-form-item label="升级推送">
          <el-switch v-model="policyForm.escalationNotify" />
          <span class="policy-hint">升级时额外 Webhook（需已启用）</span>
        </el-form-item>
        <el-form-item label="恢复确认次数">
          <el-input-number v-model="policyForm.onlineAfterSuccesses" :min="1" :max="10" />
          <span class="policy-hint">连续探测成功后才关闭连通性告警</span>
        </el-form-item>
        <el-form-item label="离线确认次数">
          <el-input-number v-model="policyForm.offlineAfterFailures" :min="1" :max="10" />
          <span class="policy-hint">连续探测失败后才标记离线</span>
        </el-form-item>
        <el-form-item label="测试">
          <el-button size="small" :loading="webhookTesting" @click="testWebhook">测试连通</el-button>
          <el-button size="small" :loading="escalationRunning" @click="runEscalationNow">立即扫描升级</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="policyVisible = false">取消</el-button>
        <el-button type="primary" :loading="policySaving" @click="savePolicy">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiopsApi, llmApi } from '@/api/device'
import { pushOpsAssistantResult, setOpsAssistantContext } from '@/composables/opsAssistantBus'
import { clearPageContext, publishToolResult, setPageContext, usePageOpsBus } from '@/composables/pageOpsBus'
import { useOpsTools } from '@/composables/useOpsTools'
import { useAuthStore } from '@/stores/auth'
import OpsInlineShell from '@/components/ops-assistant/OpsInlineShell.vue'
import { requestOpenOpsInline } from '@/composables/useOpsInlinePanel'

const INSPECT_TS_KEY = 'aiops_last_inspect_ts'
const INSPECT_TTL_MS = 10 * 60 * 1000

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { executeTool, formatToolResult } = useOpsTools()
const { sink: pageSink } = usePageOpsBus()

const loading = ref(false)
const detecting = ref(false)
const inspecting = ref(false)
const acting = ref(false)
const ackingSecondary = ref(false)
const webhookTesting = ref(false)
const escalationRunning = ref(false)
const disposing = ref(false)
const pullingConfig = ref(false)
const timelineLoading = ref(false)
const focusLoading = ref(false)
const overview = ref({})
const inspectReport = ref(null)
const focus = ref(null)
const plan = ref({})
const evidence = ref({})
const recommendedTools = ref([])
const evidenceOpen = ref(['ev'])
const timeline = ref({})
const configPreview = ref('')
const workLog = ref([])
const lastDisposeResult = ref(null)
const policyVisible = ref(false)
const policySaving = ref(false)
/** 事件列表：默认只看待处理 */
const incidentFilter = ref('pending')
/** time_desc | time_asc | severity */
const incidentSort = ref('time_desc')
const policyForm = ref({
  stormWindowMinutes: 10,
  linkWindowMinutes: 15,
  anomalyLookbackHours: 24,
  anomalyKSigma: 2.2,
  anomalyMinSamples: 5,
  analyzeSimulatedMetrics: false,
  autoAckSecondary: false,
  webhookEnabled: false,
  webhookUrl: '',
  webhookMinSeverity: 'MAJOR',
  escalationEnabled: false,
  escalationMinutes: 30,
  escalationNotify: true,
  onlineAfterSuccesses: 2,
  offlineAfterFailures: 2,
  llmOpsMode: 'manual',
  unattendedMaxPerCycle: 3,
  unattendedMaxSteps: 3,
  unattendedCooldownMinutes: 10,
  unattendedOnCorrelate: false,
  unattendedAllowBackup: false,
  cpuThreshold: { warning: 70, danger: 85 },
  memoryThreshold: { warning: 75, danger: 90 }
})
const opsMode = ref('manual')
const llmExplain = ref(null)
const llmLoading = ref(false)
const inspectEvidence = computed(() =>
  inspectReport.value?.evidence || overview.value.lastInspectEvidence || null
)
const inspectReportLines = computed(() => {
  const lines = inspectReport.value?.lines
  return Array.isArray(lines) ? lines : []
})

const health = computed(() => overview.value.health || {})
const incidents = computed(() => overview.value.incidents || [])
const pendingIncidentCount = computed(() =>
  incidents.value.filter((r) => incidentPhase(r) === 'pending').length
)
const SEVERITY_RANK = { CRITICAL: 0, MAJOR: 1, WARNING: 2, MINOR: 3, INFO: 4 }
const filteredIncidents = computed(() => {
  let list = [...incidents.value]
  if (incidentFilter.value === 'pending') {
    list = list.filter((r) => incidentPhase(r) === 'pending')
  } else if (incidentFilter.value === 'in_progress' || incidentFilter.value === 'handled') {
    list = list.filter((r) => incidentPhase(r) === 'in_progress')
  } else if (incidentFilter.value === 'closed') {
    list = list.filter((r) => incidentPhase(r) === 'closed')
  }
  const sort = incidentSort.value
  list.sort((a, b) => {
    if (sort === 'severity') {
      const ra = SEVERITY_RANK[String(a.severity || '').toUpperCase()] ?? 9
      const rb = SEVERITY_RANK[String(b.severity || '').toUpperCase()] ?? 9
      if (ra !== rb) return ra - rb
    }
    const ta = String(a.occurredAt || '')
    const tb = String(b.occurredAt || '')
    const cmp = tb.localeCompare(ta)
    return sort === 'time_asc' ? -cmp : cmp
  })
  return list
})
const incidentEmptyText = computed(() => {
  if (!incidents.value.length) return '暂无事件，请先点右上角「智能巡检」'
  if (incidentFilter.value === 'pending') return '没有待处理事件（可切换到「处理中 / 已关闭」查看）'
  if (incidentFilter.value === 'in_progress' || incidentFilter.value === 'handled') return '暂无处理中事件'
  if (incidentFilter.value === 'closed') return '暂无已关闭事件'
  return '暂无事件'
})
const rca = computed(() => overview.value.rca || {})
const story = computed(() => overview.value.story || {})
const suggestions = computed(() => overview.value.suggestions || health.value.suggestions || [])
const secondaryCount = computed(() => {
  const c = overview.value.correlation || {}
  const n = Number(c.ackableNoiseCount ?? c.activeSecondaryCount ?? 0)
  return Number.isFinite(n) ? n : 0
})

/** pending | in_progress | closed */
function incidentPhase(row) {
  if (!row) return 'pending'
  if (row.phase === 'pending' || row.phase === 'in_progress' || row.phase === 'closed') return row.phase
  const st = String(row.status || '').toUpperCase()
  if (st === 'CLEARED' || row.closed === true || row.handled === true) return 'closed'
  if (st === 'ACKNOWLEDGED' || row.inProgress === true) return 'in_progress'
  return 'pending'
}

function isIncidentClosed(row) {
  return incidentPhase(row) === 'closed'
}

function isIncidentHandled(row) {
  return isIncidentClosed(row)
}

const focusClosed = computed(() =>
  isIncidentClosed(focus.value)
  || !!plan.value?.closed
  || !!plan.value?.handled
  || String(plan.value?.status || '').toUpperCase() === 'CLEARED'
)
const focusInProgress = computed(() =>
  !focusClosed.value && (
    incidentPhase(focus.value) === 'in_progress'
    || !!plan.value?.inProgress
    || String(plan.value?.status || '').toUpperCase() === 'ACKNOWLEDGED'
  )
)
/** 兼容旧名：仅已关闭 */
const focusHandled = focusClosed
/** 已关闭后隐藏会重复闭环的动作；处理中仍可标准处置 */
const HIDDEN_WHEN_CLOSED = new Set(['dispose_incident', 'ack_noise', 'ack_alarm'])
const visibleRecommendedTools = computed(() => {
  const list = Array.isArray(recommendedTools.value) ? recommendedTools.value : []
  if (!focusClosed.value) return list
  return list.filter((t) => t?.name && !HIDDEN_WHEN_CLOSED.has(String(t.name)))
})
const primaryTool = computed(() => {
  const list = visibleRecommendedTools.value
  return list.length ? list[0] : null
})
const secondaryTools = computed(() => visibleRecommendedTools.value.slice(1))
const storyDetailShort = computed(() => {
  const d = String(story.value?.detail || '').trim()
  if (!d) return ''
  return d.length > 80 ? `${d.slice(0, 80)}…` : d
})

const severityType = (s) => ({ CRITICAL: 'danger', MAJOR: 'warning', WARNING: 'warning', MINOR: 'info', INFO: '' }[s] || 'info')
const severityText = (s) => ({ CRITICAL: '严重', MAJOR: '重要', WARNING: '警告', MINOR: '次要', INFO: '提示' }[s] || s || '-')

function alarmStatusText(st) {
  const u = String(st || '').toUpperCase()
  if (u === 'ACKNOWLEDGED') return '处理中'
  if (u === 'ACTIVE') return '待处理'
  if (u === 'CLEARED') return '已关闭'
  return st || '-'
}

function deviceStatusText(st) {
  const s = String(st || '').toLowerCase()
  if (s === 'online') return '在线'
  if (s === 'offline') return '离线'
  return st || '-'
}

function metricSourceText(src) {
  const s = String(src || '').toLowerCase()
  if (s === 'simulated' || s.includes('sim')) return '仿真'
  if (s === 'snmp') return 'SNMP'
  return src || '-'
}

function formatTime(t) {
  if (!t) return '-'
  const s = String(t).replace('T', ' ')
  return s.length > 19 ? s.slice(0, 19) : s
}

function incidentRowClass({ row }) {
  if (incidentPhase(row) === 'closed') return 'incident-handled'
  if (incidentPhase(row) === 'in_progress') return 'incident-in-progress'
  if (focus.value?.id != null && Number(row.id) === Number(focus.value.id)) return 'incident-highlight'
  return ''
}

/** 处置结果回写左侧队列 */
function patchIncidentStatus(alarmId, status, extra = {}) {
  if (alarmId == null) return
  const list = Array.isArray(overview.value.incidents) ? [...overview.value.incidents] : []
  const idx = list.findIndex((i) => Number(i.id) === Number(alarmId))
  if (idx < 0) return
  const st = String(status || '').toUpperCase()
  const phase = st === 'CLEARED' ? 'closed' : (st === 'ACKNOWLEDGED' ? 'in_progress' : 'pending')
  list[idx] = {
    ...list[idx],
    status: st,
    phase,
    closed: phase === 'closed',
    handled: phase === 'closed',
    inProgress: phase === 'in_progress',
    ...extra
  }
  list.sort((a, b) => {
    const order = { pending: 0, in_progress: 2, closed: 3 }
    const pa = order[incidentPhase(a)] ?? 0
    const pb = order[incidentPhase(b)] ?? 0
    if (pa !== pb) return pa - pb
    return String(b.occurredAt || '').localeCompare(String(a.occurredAt || ''))
  })
  overview.value = { ...overview.value, incidents: list }
}

function markIncidentHandled(alarmId) {
  patchIncidentStatus(alarmId, 'CLEARED')
}

function formatChange(ch) {
  if (!ch) return '-'
  const parts = [ch.changeType, ch.operator, ch.reason, ch.createdAt].filter(Boolean)
  return parts.join(' · ') || '-'
}

function toolButtonType(name) {
  if (name === 'dispose_incident') return 'warning'
  if (name === 'restore_latest') return 'danger'
  if (name === 'refresh_device' || name === 'refresh_offline') return 'primary'
  return 'default'
}

function toolLoading(name) {
  if (name === 'dispose_incident') return disposing.value
  if (name === 'pull_live_config') return pullingConfig.value
  return acting.value
}

function syncPolicyForm(p) {
  if (!p) return
  policyForm.value = {
    stormWindowMinutes: p.stormWindowMinutes ?? 10,
    linkWindowMinutes: p.linkWindowMinutes ?? 15,
    anomalyLookbackHours: p.anomalyLookbackHours ?? 24,
    anomalyKSigma: p.anomalyKSigma ?? 2.2,
    anomalyMinSamples: p.anomalyMinSamples ?? 5,
    analyzeSimulatedMetrics: !!p.analyzeSimulatedMetrics,
    autoAckSecondary: !!p.autoAckSecondary,
    webhookEnabled: !!p.webhookEnabled,
    webhookUrl: p.webhookUrl || '',
    webhookMinSeverity: p.webhookMinSeverity || 'MAJOR',
    escalationEnabled: !!p.escalationEnabled,
    escalationMinutes: p.escalationMinutes ?? 30,
    escalationNotify: p.escalationNotify !== false,
    onlineAfterSuccesses: p.onlineAfterSuccesses ?? 2,
    offlineAfterFailures: p.offlineAfterFailures ?? 2,
    llmOpsMode: p.llmOpsMode === 'unattended' ? 'unattended' : 'manual',
    unattendedMaxPerCycle: p.unattendedMaxPerCycle ?? 3,
    unattendedMaxSteps: p.unattendedMaxSteps ?? 3,
    unattendedCooldownMinutes: p.unattendedCooldownMinutes ?? 10,
    unattendedOnCorrelate: !!p.unattendedOnCorrelate,
    unattendedAllowBackup: !!p.unattendedAllowBackup,
    cpuThreshold: {
      warning: p.cpuThreshold?.warning ?? 70,
      danger: p.cpuThreshold?.danger ?? 85
    },
    memoryThreshold: {
      warning: p.memoryThreshold?.warning ?? 75,
      danger: p.memoryThreshold?.danger ?? 90
    }
  }
  opsMode.value = policyForm.value.llmOpsMode
}

async function onOpsModeChange(mode) {
  const next = mode === 'unattended' ? 'unattended' : 'manual'
  if (next === 'unattended') {
    try {
      await ElMessageBox.confirm(
        '开启无人值守后，巡检将对安全动作（刷新/确认噪音/标准处置/阅知关闭）自动执行；配置回滚不会自动执行。是否继续？',
        '切换无人值守',
        { type: 'warning', confirmButtonText: '开启', cancelButtonText: '取消' }
      )
    } catch {
      opsMode.value = 'manual'
      return
    }
  }
  try {
    const res = await aiopsApi.updatePolicy({ ...policyForm.value, llmOpsMode: next })
    syncPolicyForm(res)
    ElMessage.success(next === 'unattended' ? '已切换为无人值守' : '已切换为人工运维')
    if (next === 'unattended') {
      const u = await aiopsApi.runUnattended()
      if (u?.message) appendLog(u.message)
    }
  } catch (e) {
    opsMode.value = policyForm.value.llmOpsMode || 'manual'
    ElMessage.error(e?.response?.data?.message || '切换失败')
  }
}

async function loadLlmExplain(force = false) {
  if (!focus.value?.id && !plan.value?.alarmId) return
  if (!force && llmExplain.value?.text && llmExplain.value?.source === 'llm') return
  llmLoading.value = true
  try {
    const alarmId = focus.value?.id || plan.value?.alarmId
    const deviceId = focus.value?.deviceId || plan.value?.deviceId
    const question = [
      '请用不超过120字说明：当前告警可能原因、建议先做的网管动作（只能从推荐工具中选）、不要声称已修好。',
      plan.value?.summary ? `处置摘要：${plan.value.summary}` : '',
      plan.value?.primaryReason ? `规则建议：${plan.value.primaryReason}` : ''
    ].filter(Boolean).join('\n')
    try {
      const res = await llmApi.chat({ question, alarmId, deviceId, history: [] })
      const text = res?.answer || res?.message || res?.content || ''
      if (text) {
        llmExplain.value = { text, source: 'llm' }
        return
      }
    } catch {
      /* fallback */
    }
    llmExplain.value = {
      source: 'rules',
      text: plan.value?.primaryReason
        || plan.value?.hint
        || plan.value?.summary
        || '建议按「下一步」执行规则推荐动作；LLM 未启用或调用失败，已回退规则说明。'
    }
  } finally {
    llmLoading.value = false
  }
}

function appendLog(msg) {
  const ts = new Date().toLocaleTimeString()
  workLog.value = [`[${ts}] ${msg}`, ...workLog.value].slice(0, 30)
}

function bindAssistant(row, focusData = {}, { autoAsk = false } = {}) {
  const closed = !!focusData.closed || !!focusData.handled || isIncidentClosed(row)
  const inProgress = !closed && (!!focusData.inProgress || incidentPhase(row) === 'in_progress')
  const tools = Array.isArray(focusData.recommendedTools)
    ? focusData.recommendedTools
    : (recommendedTools.value || [])
  const visible = closed
    ? tools.filter((t) => t?.name && !HIDDEN_WHEN_CLOSED.has(String(t.name)))
    : tools
  const primary = visible[0] || null
  const primaryLabel = primary?.label || primary?.name || '下一步'
  setOpsAssistantContext({
    alarmId: row.id || row.alarmId || focusData.alarmId || null,
    deviceId: row.deviceId ?? focusData.deviceId,
    title: row.title || row.name || focusData.title || '',
    deviceName: row.deviceName || row.name || focusData.deviceName || '',
    source: 'workbench',
    expand: autoAsk,
    focusInput: autoAsk,
    scenario: focusData.scenario || '',
    scenarioLabel: focusData.scenarioLabel || '',
    handled: closed,
    primaryToolLabel: primaryLabel,
    recommendedTools: tools,
    autoAsk,
    autoAskQuestion: closed
      ? `事件已关闭。复核建议：点「${primaryLabel}」。`
      : (inProgress
        ? `处理中。修好后可再点标准处置；或点「${primaryLabel}」复核。`
        : `诊断当前事件，优先动作：「${primaryLabel}」。`)
  })
  setPageContext({
    page: 'workbench',
    alarmId: row.id || row.alarmId || focusData.alarmId || null,
    deviceId: row.deviceId ?? focusData.deviceId ?? null,
    title: row.title || row.name || focusData.title || '',
    deviceName: row.deviceName || row.name || focusData.deviceName || '',
    scenario: focusData.scenario || '',
    meta: { primaryToolLabel: primaryLabel, closed, inProgress }
  })
}

async function loadWorkbenchFocus(row, { autoAsk = false, skipUnattended = false } = {}) {
  const alarmId = row.id || row.alarmId || null
  const deviceId = row.deviceId ?? null
  focusLoading.value = true
  timelineLoading.value = true
  llmExplain.value = null
  try {
    const data = (await aiopsApi.getWorkbenchFocus(alarmId, deviceId)) || {}
    plan.value = data
    evidence.value = data.evidence || {}
    recommendedTools.value = Array.isArray(data.recommendedTools) ? data.recommendedTools : []
    timeline.value = data.timeline || {}
    const closed = !!data.closed || !!data.handled || String(data.status || '').toUpperCase() === 'CLEARED'
    const inProgress = !closed && (!!data.inProgress || String(data.status || '').toUpperCase() === 'ACKNOWLEDGED')
    focus.value = {
      ...row,
      id: alarmId || row.id,
      alarmId: data.alarmId || alarmId,
      deviceId: data.deviceId ?? deviceId,
      deviceName: data.deviceName || row.deviceName || row.name,
      title: data.title || row.title || row.name,
      severity: data.severity || row.severity,
      status: data.status || row.status,
      phase: data.phase || (closed ? 'closed' : (inProgress ? 'in_progress' : 'pending')),
      closed,
      handled: closed,
      inProgress,
      correlationNote: data.correlationNote || row.correlationNote,
      scenario: data.scenario
    }
    // 焦点接口已关闭时，同步左侧队列标记
    if (closed && alarmId != null) {
      patchIncidentStatus(alarmId, 'CLEARED')
    } else if (inProgress && alarmId != null) {
      patchIncidentStatus(alarmId, 'ACKNOWLEDGED')
    }
    bindAssistant(focus.value, data, { autoAsk })
    await loadLlmExplain(false)
    if (!skipUnattended && opsMode.value === 'unattended' && !closed && alarmId) {
      try {
        const u = await aiopsApi.runUnattended({ alarmId, deviceId })
        if (u?.processed > 0 || u?.ran) {
          appendLog(u.message || '无人值守已对本事件执行安全动作')
          await refresh()
          const latest = incidents.value.find((i) => Number(i.id) === Number(alarmId))
          if (latest) await loadWorkbenchFocus(latest, { autoAsk: false, skipUnattended: true })
        } else if (u?.message && !u?.skipped) {
          appendLog(u.message)
        }
      } catch { /* ignore */ }
    }
  } catch {
    plan.value = {}
    evidence.value = {}
    recommendedTools.value = []
    llmExplain.value = null
    bindAssistant(row, {}, { autoAsk: false })
    try {
      if (alarmId) timeline.value = (await aiopsApi.getTimeline(alarmId, deviceId)) || {}
    } catch {
      timeline.value = {}
    }
  } finally {
    focusLoading.value = false
    timelineLoading.value = false
  }
}

async function selectFocus(row) {
  if (!row) return
  focus.value = { ...row }
  configPreview.value = ''
  await loadWorkbenchFocus(row, { autoAsk: false })
}

async function selectDeviceFocus(c) {
  const row = {
    deviceId: c.deviceId,
    name: c.name,
    deviceName: c.name,
    title: `根因设备 · ${c.name}`,
    reason: c.reason,
    category: c.category
  }
  focus.value = row
  timeline.value = {}
  configPreview.value = ''
  appendLog(`已选中根因设备 ${c.name}`)
  await loadWorkbenchFocus(row, { autoAsk: false })
}

async function openRcaOnTopology(c) {
  const ids = []
  if (c?.deviceId != null) ids.push(String(c.deviceId))
  const impact = Array.isArray(c?.impactDeviceIds) ? c.impactDeviceIds : []
  impact.forEach((id) => {
    const s = String(id)
    if (s && !ids.includes(s)) ids.push(s)
  })
  if (!ids.length) {
    ElMessage.warning('该根因暂无影响设备列表')
    return
  }
  try {
    await ElMessageBox.confirm('将打开拓扑并高亮根因影响设备，是否继续？', '打开拓扑', {
      type: 'info', confirmButtonText: '前往', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  router.push({
    name: 'Topology',
    query: {
      rca: '1',
      highlight: ids.join(','),
      deviceId: ids[0]
    }
  })
}

async function runRecommendedTool(t) {
  if (!t?.name) return
  if (focusClosed.value && HIDDEN_WHEN_CLOSED.has(String(t.name))) {
    return
  }
  if (t.name === 'dispose_incident') {
    await runStandardDispose()
    return
  }
  await runFocusTool(t.name, t.params || t.args || {})
}

async function runStandardDispose() {
  const row = focus.value
  if (!row?.id) {
    ElMessage.warning('请先选择带告警 ID 的收敛事件')
    return
  }
  if (focusClosed.value || isIncidentClosed(row)) {
    return
  }
  const skipConfirm = opsMode.value === 'unattended'
  if (!skipConfirm) {
    try {
      await ElMessageBox.confirm(
        '将真实执行：网管探测设备 → 处理关联告警（阅知类直接关闭）→ 连续可达确认后关闭告警，否则进入「处理中」。单次可达不算恢复。不会改设备配置。',
        '标准处置',
        { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  disposing.value = true
  try {
    const res = await aiopsApi.disposeIncident(row.id, row.deviceId ?? null, true)
    if (res?.ok === false) {
      ElMessage.error(res.error || '处置失败')
      appendLog(`处置失败：${res.error}`)
      return
    }
    const msg = res?.message || '标准处置完成'
    ElMessage.success(msg)
    lastDisposeResult.value = res
    appendLog(msg)
    if (res?.steps?.length) {
      res.steps.forEach((s) => appendLog(`${s.title}: ${s.message || ''}`))
    }
    pushOpsAssistantResult({ message: `【工作台处置】${msg}` })

    const outcome = String(res?.outcome || '').toLowerCase()
    const statusFromRes = String(res?.status || '').toUpperCase()
    let nextStatus = statusFromRes
    if (!nextStatus) {
      if (outcome === 'closed' || res?.closed === true) nextStatus = 'CLEARED'
      else if (outcome === 'in_progress' || res?.inProgress === true) nextStatus = 'ACKNOWLEDGED'
      else nextStatus = 'ACKNOWLEDGED'
    }
    patchIncidentStatus(row.id, nextStatus)
    await refresh()
    const updated = incidents.value.find((i) => Number(i.id) === Number(row.id))
    if (updated) {
      patchIncidentStatus(row.id, updated.status || nextStatus)
    }

    // 待处理视图：离开当前条；处理中/已关闭视图：留在当前并刷新作业区
    if (incidentFilter.value === 'pending' && nextStatus !== 'ACTIVE') {
      const next = filteredIncidents.value.find((i) => Number(i.id) !== Number(row.id))
        || filteredIncidents.value[0]
      if (next && Number(next.id) !== Number(row.id)) {
        await selectFocus(next)
      } else if (!filteredIncidents.value.length) {
        focus.value = null
        plan.value = {}
        evidence.value = {}
        recommendedTools.value = []
        timeline.value = {}
      } else {
        await loadWorkbenchFocus(
          incidents.value.find((i) => Number(i.id) === Number(row.id)) || { ...row, status: nextStatus },
          { autoAsk: false }
        )
      }
    } else {
      const latest = incidents.value.find((i) => Number(i.id) === Number(row.id))
        || { ...row, status: nextStatus, phase: incidentPhase({ status: nextStatus }) }
      if (nextStatus === 'CLEARED' && incidentFilter.value === 'pending') {
        incidentFilter.value = 'closed'
      } else if (nextStatus === 'ACKNOWLEDGED' && incidentFilter.value === 'pending') {
        incidentFilter.value = 'in_progress'
      }
      await loadWorkbenchFocus(latest, { autoAsk: false })
    }
  } catch {
    ElMessage.error('标准处置失败')
  } finally {
    disposing.value = false
  }
}

const SAFE_UNATTENDED = new Set([
  'inspect', 'pull_live_config', 'refresh_device', 'refresh_offline',
  'ack_noise', 'dispose_incident', 'ack_alarm'
])

async function runFocusTool(name, extraArgs = {}) {
  const f = focus.value || {}
  const tool = {
    name,
    label: name,
    needConfirm: name !== 'pull_live_config' && name !== 'inspect',
    args: { ...extraArgs }
  }
  if (tool.args.deviceId == null && f.deviceId) tool.args.deviceId = f.deviceId
  if (tool.args.alarmId == null && (f.id || f.alarmId)) tool.args.alarmId = f.id || f.alarmId
  if (name === 'pull_live_config') {
    if (!tool.args.configType) tool.args.configType = 'running'
    pullingConfig.value = true
  } else {
    acting.value = true
  }
  const skipConfirm = opsMode.value === 'unattended'
    && SAFE_UNATTENDED.has(name)
    && name !== 'restore_latest'
  try {
    const res = await executeTool(tool, { skipConfirm })
    if (res?.cancelled) return
    publishToolResult(res, { tool: name, source: 'workbench' })
    appendLog(formatToolResult(res).split('\n')[0])
    if (res?.configText) {
      configPreview.value = String(res.configText).slice(0, 20000)
    }
    pushOpsAssistantResult({ message: `【工作台】${formatToolResult(res)}` })
    const detail = res?.detail || {}
    if (detail.navigate && detail.path) {
      const { offerToolNavigate } = await import('@/composables/useSafeNavigate')
      await offerToolNavigate(router, route, {
        toolName: name,
        detail,
        onSamePage: async (d) => {
          const aid = d.alarmId != null ? Number(d.alarmId) : Number(d.query?.alarmId)
          if (Number.isFinite(aid)) {
            const hit = incidents.value.find((r) => Number(r.id) === aid)
            if (hit) await selectFocus(hit)
          }
        }
      })
      return
    }
    if (name !== 'pull_live_config') {
      await refresh()
      if (f.id || f.alarmId || f.deviceId) {
        await loadWorkbenchFocus(focus.value || f, { autoAsk: false })
      }
    }
  } finally {
    acting.value = false
    pullingConfig.value = false
  }
}

async function ackAllSecondary() {
  try {
    await ElMessageBox.confirm(
      `处理全网约 ${secondaryCount.value} 条关联告警？阅知类将直接关闭，其余进入处理中。不会修改设备配置。`,
      '处理关联告警',
      { type: 'warning' }
    )
  } catch {
    return
  }
  ackingSecondary.value = true
  try {
    const res = await aiopsApi.ackSecondary(null, true, null)
    if (res?.ok === false) ElMessage.error(res.error || '失败')
    else {
      ElMessage.success(res.message || '关联告警已处理')
      appendLog(res.message || '已处理关联告警')
      await refresh()
    }
  } catch {
    ElMessage.error('确认失败')
  } finally {
    ackingSecondary.value = false
  }
}

function canExecuteSuggestion(s) {
  return !!(s && s.executable !== false && s.action && s.action !== 'noop')
}

async function runSuggestion(s) {
  const action = s?.action || s?.code || ''
  if (!canExecuteSuggestion(s)) return
  try {
    await ElMessageBox.confirm(s.text || '确认执行？', '确认执行', { type: 'warning' })
  } catch {
    return
  }
  acting.value = true
  try {
    let res
    if (action === 'ack_noise' || action === 'secondary_alarms') {
      res = await aiopsApi.ackSecondary(null, true, null)
    } else if (action === 'refresh_offline' || action === 'check_offline') {
      res = await aiopsApi.refreshOffline(true)
    } else if (action === 'backup') {
      const deviceId = s.deviceId || focus.value?.deviceId || (rca.value.candidates || [])[0]?.deviceId
      if (!deviceId) {
        ElMessage.warning('无目标设备')
        return
      }
      res = await aiopsApi.backupAction(deviceId, true, '工作台快捷备份')
    } else if (action === 'inspect' || action === 'inspect_and_refresh') {
      res = await aiopsApi.executePlaybook({ action: 'inspect', confirmed: true })
      if (action === 'inspect_and_refresh') {
        const r2 = await aiopsApi.refreshOffline(true)
        res = { ok: res?.ok !== false && r2?.ok !== false, message: `${res?.message}；${r2?.message}` }
      }
    } else {
      res = await aiopsApi.executePlaybook({ action, deviceId: s.deviceId, confirmed: true })
    }
    if (res?.ok === false) ElMessage.error(res.error || '失败')
    else {
      ElMessage.success(res?.message || '已执行')
      appendLog(res?.message || '快捷处置完成')
      await refresh()
    }
  } catch {
    ElMessage.error('执行失败')
  } finally {
    acting.value = false
  }
}

async function testWebhook() {
  webhookTesting.value = true
  try {
    await aiopsApi.updatePolicy(policyForm.value)
    const res = await aiopsApi.testWebhook()
    if (res?.ok) ElMessage.success('Webhook 测试成功')
    else ElMessage.error(res?.error || '失败')
  } catch {
    ElMessage.error('Webhook 测试失败')
  } finally {
    webhookTesting.value = false
  }
}

async function runEscalationNow() {
  escalationRunning.value = true
  try {
    await aiopsApi.updatePolicy(policyForm.value)
    const res = await aiopsApi.runEscalation()
    ElMessage.success(res?.message || '升级扫描完成')
    appendLog(res?.message || '超时升级扫描完成')
    await refresh()
  } catch {
    ElMessage.error('升级扫描失败')
  } finally {
    escalationRunning.value = false
  }
}

async function refresh() {
  loading.value = true
  try {
    overview.value = (await aiopsApi.getOverview(true)) || {}
    syncPolicyForm(overview.value.policy)
    const autoClosed = Number(overview.value.ackClosesAutoClosed || 0)
    if (autoClosed > 0) {
      ElMessage.success(`已自动阅知关闭 ${autoClosed} 条提示类事件`)
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function openOpsAssist() {
  requestOpenOpsInline('workbench')
}

/** 静默刷新：不打断操作区 loading，用于轮询同步新告警到事件列表 */
async function silentRefresh() {
  if (loading.value || disposing.value || acting.value || inspecting.value) return
  if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
  try {
    const data = (await aiopsApi.getOverview()) || {}
    overview.value = data
    syncPolicyForm(data.policy)
  } catch {
    /* 静默失败，避免打扰值班 */
  }
}

const OVERVIEW_POLL_MS = 30000
let overviewPollTimer = null

function startOverviewPoll() {
  stopOverviewPoll()
  overviewPollTimer = setInterval(() => {
    silentRefresh()
  }, OVERVIEW_POLL_MS)
}

function stopOverviewPoll() {
  if (overviewPollTimer) {
    clearInterval(overviewPollTimer)
    overviewPollTimer = null
  }
}

watch(policyVisible, async (v) => {
  if (v) {
    try {
      syncPolicyForm(await aiopsApi.getPolicy())
    } catch {
      syncPolicyForm(overview.value.policy)
    }
  }
})

async function savePolicy() {
  policySaving.value = true
  try {
    const res = await aiopsApi.updatePolicy(policyForm.value)
    syncPolicyForm(res)
    ElMessage.success(res?.note || '已保存')
    policyVisible.value = false
    await refresh()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    policySaving.value = false
  }
}

async function runDetect() {
  detecting.value = true
  try {
    const res = await aiopsApi.detectAnomaly()
    ElMessage.success(`基线：检查 ${res.checkedDevices || 0}，异常 ${res.anomalies || 0}`)
    await refresh()
  } catch {
    ElMessage.error('基线检测失败')
  } finally {
    detecting.value = false
  }
}

async function runInspect() {
  inspecting.value = true
  try {
    const res = await aiopsApi.inspect()
    const { saveInspectReport } = await import('@/composables/inspectReportStore')
    inspectReport.value = saveInspectReport(res) || res
    sessionStorage.setItem(INSPECT_TS_KEY, String(Date.now()))
    ElMessage.success(res.summary || '巡检完成')
    appendLog(res.summary || '智能巡检完成')
    if (res.unattended?.message) appendLog(res.unattended.message)
    await refresh()
    if (!focus.value && filteredIncidents.value.length) {
      await selectFocus(filteredIncidents.value[0])
    }
    // 报告留在本页「本次巡检算了什么」与智能运维「巡检报告」；不灌运维助手
  } catch {
    ElMessage.error('巡检失败')
  } finally {
    inspecting.value = false
  }
}

async function applyDeepLink() {
  const qAlarm = route.query.alarmId != null ? Number(route.query.alarmId) : null
  const qDevice = route.query.deviceId != null ? Number(route.query.deviceId) : null
  if (qAlarm && Number.isFinite(qAlarm)) {
    const hit = incidents.value.find((r) => Number(r.id) === qAlarm)
    if (hit) {
      const ph = incidentPhase(hit)
      if (ph !== 'pending') incidentFilter.value = ph === 'closed' ? 'closed' : 'in_progress'
      await selectFocus(hit)
    }
  } else if (qDevice && Number.isFinite(qDevice)) {
    const hit = filteredIncidents.value.find((r) => Number(r.deviceId) === qDevice)
      || incidents.value.find((r) => Number(r.deviceId) === qDevice)
    if (hit) await selectFocus(hit)
  }
}

onMounted(async () => {
  setPageContext({ page: 'workbench' })
  await refresh()
  await applyDeepLink()
  // 进入页只选中首条，不自动巡检、不自动唤起 LLM（用户点行/「刷新」再拉）
  if (!focus.value && filteredIncidents.value.length) {
    await loadWorkbenchFocus(filteredIncidents.value[0], { autoAsk: false, skipUnattended: true })
  }
  startOverviewPoll()
})

onUnmounted(() => {
  stopOverviewPoll()
  clearPageContext()
})

watch(
  () => [route.query.alarmId, route.query.deviceId],
  async () => {
    if (route.path.startsWith('/aiops/workbench') || route.path === '/aiops') await applyDeepLink()
  }
)

/** 助手工具结果回写本页 */
watch(
  () => pageSink.token,
  async (token) => {
    if (!token || !pageSink.last) return
    const { result, tool, source } = pageSink.last
    if (source === 'workbench') return
    if (!result || result.ok === false) return
    const detail = result.detail || {}
    const name = tool || result.tool || ''
    appendLog(`〔作业条〕${(result.message || name || '已执行').toString().slice(0, 80)}`)
    if (detail.navigate && detail.path) {
      if (source === 'assistant') {
        // 助手已确认跳转；工作台优先同页聚焦
        if (detail.openWorkbench || name === 'open_workbench_event' || detail.path?.includes('/aiops/workbench')) {
          const aid = detail.alarmId != null ? Number(detail.alarmId) : Number(detail.query?.alarmId)
          const did = detail.deviceId != null ? Number(detail.deviceId) : Number(detail.query?.deviceId)
          if (Number.isFinite(aid)) {
            const hit = incidents.value.find((r) => Number(r.id) === aid)
            if (hit) {
              await selectFocus(hit)
              return
            }
          }
          if (Number.isFinite(did)) {
            const hit = incidents.value.find((r) => Number(r.deviceId) === did)
            if (hit) {
              await selectFocus(hit)
              return
            }
          }
        }
        return
      }
      if (detail.openWorkbench || name === 'open_workbench_event') {
        const aid = detail.alarmId != null ? Number(detail.alarmId) : Number(detail.query?.alarmId)
        const did = detail.deviceId != null ? Number(detail.deviceId) : Number(detail.query?.deviceId)
        if (Number.isFinite(aid)) {
          const hit = incidents.value.find((r) => Number(r.id) === aid)
          if (hit) {
            await selectFocus(hit)
            return
          }
        }
        if (Number.isFinite(did)) {
          const hit = incidents.value.find((r) => Number(r.deviceId) === did)
          if (hit) {
            await selectFocus(hit)
            return
          }
        }
        return
      }
      const { offerToolNavigate } = await import('@/composables/useSafeNavigate')
      await offerToolNavigate(router, route, { toolName: name, detail })
      return
    }
    if (['inspect', 'refresh_device', 'refresh_offline', 'ack_noise', 'dispose_incident', 'ack_alarm', 'ping_check', 'probe_device'].includes(name)) {
      await refresh()
      if (focus.value) await loadWorkbenchFocus(focus.value, { autoAsk: false })
    }
  }
)
</script>

<style scoped>
.aiops-workbench {
  padding: 4px 4px 12px;
  height: calc(100vh - 100px);
  min-height: 560px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.wb-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-shrink: 0;
}
.wb-header h2 { margin: 0 0 4px; font-size: 20px; }
.subtitle { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
.header-actions { display: flex; gap: 8px; flex-wrap: wrap; }

.wb-kpi {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  flex-shrink: 0;
}
.kpi-chip {
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 8px 14px;
  min-width: 88px;
  display: flex;
  flex-direction: column;
}
.kpi-chip.grow { flex: 1; min-width: 200px; justify-content: center; }
.kpi-n { font-size: 22px; font-weight: 700; line-height: 1.2; }
.kpi-n.good { color: #67c23a; }
.kpi-n.fair { color: #e6a23c; }
.kpi-n.poor, .kpi-n.critical { color: #f56c6c; }
.kpi-l { font-size: 12px; color: var(--el-text-color-secondary); }
.kpi-story { font-size: 13px; color: var(--el-text-color-regular); font-weight: 600; }
.kpi-story-detail {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
  line-height: 1.45;
}

.wb-suggest {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  flex-shrink: 0;
}
.wb-suggest-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-right: 4px;
}

.wb-main {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(240px, 28%) minmax(0, 1fr) auto;
  gap: 12px;
}
@media (max-width: 1200px) {
  .wb-main {
    grid-template-columns: minmax(220px, 30%) minmax(0, 1fr) auto;
  }
}
@media (max-width: 900px) {
  .wb-main {
    grid-template-columns: 1fr;
    overflow: auto;
  }
  .aiops-workbench { height: auto; }
  .wb-col { min-height: 320px; }
}

.wb-col {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 10px 12px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.wb-col-title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
}
.wb-col-meta {
  font-weight: 400;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.incident-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  flex-shrink: 0;
}
.incident-sort {
  width: 118px;
}
.wb-left :deep(.el-table) { flex: 1; }
.rca-list { overflow: auto; max-height: 220px; }
.rca-mini {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.rca-mini-main {
  min-width: 0;
  flex: 1;
  cursor: pointer;
}
.rca-mini:hover { background: var(--el-fill-color-lighter); }
.rca-mini-title { font-size: 13px; font-weight: 600; }
.rca-mini-desc { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }

.wb-center {
  overflow: hidden;
}
.sticky-title { flex-shrink: 0; }
.wb-center-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 2px;
}
.plan-card {
  background: linear-gradient(180deg, #f8fafc 0%, #fff 60%);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}
.plan-card-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.plan-card-title {
  font-weight: 600;
  font-size: 14px;
}
.plan-summary {
  margin: 0 0 6px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-regular);
}
.plan-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--el-color-warning-dark-2);
}
.plan-impact {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
}
.primary-step {
  background: #fff7e6;
  border: 1px solid #ffe1a8;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}
.primary-step-label {
  font-size: 12px;
  font-weight: 700;
  color: #b88230;
  margin-bottom: 8px;
  letter-spacing: 0.02em;
}
.primary-step-detail {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-regular);
}
.secondary-actions {
  margin-bottom: 4px;
}
.secondary-actions-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.guide-empty {
  padding: 28px 16px;
  text-align: left;
}
.guide-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 12px;
}
.guide-steps {
  margin: 0 0 12px 18px;
  padding: 0;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
}
.guide-tip {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ops-mode-group { margin-right: 4px; }
.evidence-card {
  margin: 0 12px 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
}
.evidence-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}
.evidence-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.inspect-lines {
  margin: 10px 0 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.55;
}
.inspect-lines li { margin-bottom: 4px; }
.llm-explain {
  margin: 10px 0 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
}
.llm-explain-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}
.llm-explain-body {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
}
.primary-step-mode {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-color-warning);
}
.evidence-panel {
  margin-bottom: 12px;
  border: none;
}
.evidence-panel :deep(.el-collapse-item__header) {
  font-weight: 600;
  font-size: 13px;
}
.ev-block { margin-bottom: 10px; }
.ev-block-title {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 4px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.ev-row {
  font-size: 12px;
  line-height: 1.45;
  padding: 3px 0;
  color: var(--el-text-color-regular);
}
.ev-sub {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
}
.wb-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 0;
  flex-shrink: 0;
  align-items: center;
}
:deep(.incident-handled) {
  opacity: 0.72;
}
:deep(.incident-handled td) {
  color: var(--el-text-color-secondary);
}
:deep(.incident-in-progress) {
  background: var(--el-color-warning-light-9);
}
.incident-title-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  vertical-align: middle;
}
.incident-status-tag {
  flex-shrink: 0;
}
.incident-title-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.work-log {
  background: var(--el-fill-color-light);
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 12px;
  border: 1px solid var(--el-border-color-lighter);
}
.work-log-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 8px;
}
.work-log-body {
  max-height: 160px;
  overflow-y: auto;
}
.work-log-empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  padding: 4px 0;
}
.work-log-line {
  font-size: 12px;
  color: var(--el-text-color-regular);
  padding: 3px 0;
  line-height: 1.45;
  font-family: ui-monospace, Consolas, monospace;
  white-space: pre-wrap;
  word-break: break-word;
}
.dispose-result-card {
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 12px;
}
.dispose-result-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 0 0 8px;
}
.policy-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.dispose-result-summary {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 8px;
}
.dispose-result-step {
  margin-top: 8px;
  padding-left: 8px;
  border-left: 3px solid var(--el-border-color);
}
.dispose-result-step.ok { border-left-color: var(--el-color-success); }
.dispose-result-step.fail { border-left-color: var(--el-color-danger); }
.dispose-step-title { font-weight: 600; font-size: 13px; }
.dispose-step-detail { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }
.config-preview {
  margin-bottom: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  padding: 10px 12px;
  background: #fff;
}
.config-preview pre {
  margin: 0;
  max-height: 220px;
  overflow: auto;
  font-size: 11px;
  white-space: pre-wrap;
  word-break: break-all;
}
.tl-title { font-weight: 600; font-size: 13px; }
.tl-text { font-size: 12px; color: var(--el-text-color-secondary); }
.ml4 { margin-left: 4px; }

.wb-right {
  padding: 0;
  background: transparent;
  border: none;
  align-self: stretch;
}
.wb-right.is-open {
  /* OpsInlineShell open 态自带边框 */
}

:deep(.incident-highlight) { background: #ecf5ff !important; }
:deep(.incident-highlight td) { background: transparent !important; }
</style>
