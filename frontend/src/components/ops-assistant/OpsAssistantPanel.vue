<template>
  <div class="oap" :class="{ embedded }">
    <header class="oap-hd">
      <div class="oap-hd-left">
        <span class="oap-brand">{{ embedded ? '运维辅助' : '运维作业' }}</span>
        <el-tag v-if="settings.enabled && !lastFallback" size="small" type="success" effect="plain">LLM</el-tag>
        <el-tag v-else size="small" type="info" effect="plain">规则</el-tag>
      </div>
      <div class="oap-hd-right">
        <el-button link :icon="Setting" title="模型设置" @click="showSettings = !showSettings" />
        <el-button
          v-if="embedded"
          link
          :icon="Minus"
          title="收起运维辅助"
          @click="$emit('collapse')"
        />
        <el-button v-else link :icon="Minus" title="收起" @click="$emit('collapse')" />
      </div>
    </header>

    <div v-if="showSettings" class="oap-settings">
      <el-form label-position="top" size="small">
        <el-form-item label="启用 LLM">
          <el-switch v-model="form.enabled" :disabled="!canWrite" />
        </el-form-item>
        <el-form-item label="Provider">
          <el-select v-model="form.provider" :disabled="!canWrite" style="width: 100%">
            <el-option label="本地 Ollama" value="ollama" />
            <el-option label="OpenAI 兼容" value="openai_compatible" />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" :disabled="!canWrite" />
        </el-form-item>
        <el-form-item v-if="form.provider === 'openai_compatible'" label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password :disabled="!canWrite" />
        </el-form-item>
        <el-form-item label="模型">
          <el-input v-model="form.model" :disabled="!canWrite" />
        </el-form-item>
        <div class="oap-settings-actions">
          <el-button size="small" :loading="testing" :disabled="!canWrite" @click="testConn">测试</el-button>
          <el-button size="small" type="primary" :loading="saving" :disabled="!canWrite" @click="saveSettings">保存</el-button>
        </div>
        <p class="oap-hint">未启用时仅根据工具证据给规则结论。</p>
      </el-form>
    </div>

    <div v-else class="oap-body">
      <div class="oap-focus">
        <div class="oap-focus-main">
          <div class="oap-focus-k">焦点</div>
          <div class="oap-focus-v" :title="focusLabel || ''">
            {{ focusLabel || (embedded ? '跟随左侧选中对象' : '未选定也可提问') }}
          </div>
        </div>
        <div class="oap-focus-acts">
          <el-button v-if="focusLabel && !embedded" link type="danger" size="small" @click="clearFocus">清除</el-button>
          <el-button type="primary" size="small" :loading="sending" @click="runDiagnose">
            {{ sending ? '诊断中' : '诊断' }}
          </el-button>
        </div>
      </div>

      <div class="oap-ask" :class="{ compact: !!job }">
        <div class="oap-ask-row">
          <el-input
            ref="inputRef"
            v-model="input"
            type="textarea"
            :rows="job ? 1 : 2"
            resize="none"
            :placeholder="embedded ? '针对当前对象提问，Enter 发送' : '输入问题，Enter 发送'"
            :disabled="sending"
            @keydown.enter.exact.prevent="send"
          />
          <el-button type="primary" :loading="sending" @click="send">发送</el-button>
        </div>
        <div v-if="!job" class="oap-intents">
          <el-button
            v-for="it in intents"
            :key="it.id"
            size="small"
            text
            bg
            :disabled="sending"
            @click="runIntent(it)"
          >{{ it.label }}</el-button>
        </div>
      </div>

      <!-- 主阅读区：结论完整展开，只滚这一层 -->
      <div class="oap-job" ref="jobScrollRef" v-loading="sending">
        <template v-if="job">
          <div class="oap-verdict" :class="job.level">
            <div class="oap-verdict-label">
              <span>结论</span>
              <el-tag v-if="job.intentLabel" size="small" type="info" effect="plain">{{ job.intentLabel }}</el-tag>
            </div>
            <div class="oap-verdict-text">{{ job.verdict }}</div>
            <div v-if="job.modeNote" class="oap-verdict-note">{{ job.modeNote }}</div>
          </div>

          <el-collapse v-if="job.evidence?.length" class="oap-collapse">
            <el-collapse-item :title="`证据 ${job.evidence.length} 条（可展开核对）`" name="ev">
              <ul class="oap-ev-list">
                <li v-for="(e, i) in job.evidence" :key="i" :class="{ bad: e.ok === false }">
                  <b>{{ e.label || e.tool }}</b>：{{ e.message || '-' }}
                </li>
              </ul>
            </el-collapse-item>
          </el-collapse>
        </template>
        <div v-else class="oap-empty">
          <p>{{ embedded ? '选定对象后点「诊断」，或直接提问。' : '可直接提问；选定设备/告警后证据更准。' }}</p>
        </div>
      </div>

      <!-- 执行反馈：贴在动作上方，不占结论阅读空间 -->
      <div v-if="latestRun" class="oap-feedback" :class="{ bad: latestRun.ok === false, ok: latestRun.ok === true }">
        <div class="oap-feedback-main">
          <span class="oap-feedback-k">刚才</span>
          <b>{{ latestRun.label }}</b>
          <span class="oap-feedback-msg">{{ latestRun.message }}</span>
        </div>
        <el-button
          v-if="runLog.length > 1"
          link
          size="small"
          @click="showRunHistory = !showRunHistory"
        >{{ showRunHistory ? '收起' : `记录 ${runLog.length}` }}</el-button>
      </div>
      <div v-if="showRunHistory && runLog.length > 1" class="oap-run-history">
        <div
          v-for="r in runLog.slice(1)"
          :key="r.id"
          class="oap-run-history-item"
          :class="{ bad: r.ok === false }"
        >
          <span class="oap-runlog-time">{{ r.time }}</span>
          <b>{{ r.label }}</b>
          <span>{{ r.message }}</span>
        </div>
      </div>

      <div v-if="job" class="oap-actions-bar">
        <div class="oap-block-title">建议动作 <span class="oap-title-hint">读完结论后点这里</span></div>
        <div v-if="job.actions?.length" class="oap-acts">
          <el-button
            v-for="(a, ai) in job.actions"
            :key="(a.name || a.id) + '-' + ai"
            size="small"
            :type="a.needConfirm === false ? 'primary' : 'warning'"
            :plain="a.needConfirm !== false"
            :loading="executingKey === 'job-' + ai"
            @click="runJobAction(a, ai)"
          >{{ a.label || a.name }}</el-button>
        </div>
        <p v-else class="oap-hint">暂无建议动作。选定设备/告警后再点「诊断」。</p>
        <p v-if="job.actions?.length" class="oap-hint">橙色为写操作，会二次确认。</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Setting, Minus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { llmApi } from '@/api/device'
import { useAuthStore } from '@/stores/auth'
import { clearOpsAssistantContext, useOpsAssistantBus } from '@/composables/opsAssistantBus'
import { publishToolResult } from '@/composables/pageOpsBus'
import { useOpsTools } from '@/composables/useOpsTools'

defineProps({ embedded: { type: Boolean, default: false } })
defineEmits(['collapse'])

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const bus = useOpsAssistantBus()
const { executeTool } = useOpsTools()

const showSettings = ref(false)
const sending = ref(false)
const saving = ref(false)
const testing = ref(false)
const executingKey = ref('')
const input = ref('')
const inputRef = ref(null)
const jobScrollRef = ref(null)
const lastFallback = ref(true)
const job = ref(null)
/** 动作执行记录（反馈条展示最新一条，不挤占结论） */
const runLog = ref([])
const showRunHistory = ref(false)

const latestRun = computed(() => runLog.value[0] || null)

const settings = reactive({
  enabled: false, provider: 'ollama', baseUrl: '', model: '', apiKey: '', hasApiKey: false
})
const form = reactive({
  enabled: false, provider: 'ollama', baseUrl: 'http://127.0.0.1:11434', model: 'qwen2.5:7b', apiKey: ''
})

const canWrite = computed(() => auth.isAdmin || auth.hasPermission('configs:write'))
const focusLabel = computed(() => {
  const parts = []
  if (bus.title) parts.push(bus.title)
  else if (bus.alarmId) parts.push(`告警 #${bus.alarmId}`)
  if (bus.deviceName) parts.push(bus.deviceName)
  else if (bus.deviceId) parts.push(`设备 #${bus.deviceId}`)
  return parts.join(' · ')
})

const intents = computed(() => {
  const src = String(bus.source || '')
  const hasDevice = bus.deviceId != null
  if (src === 'config' || src === 'configs' || hasDevice) {
    return [
      { id: 'diag', label: '诊断', q: '诊断当前焦点，给出证据与可执行动作。' },
      { id: 'risk', label: '配置风险', q: '评估配置风险：备份与 running 差异。' },
      { id: 'cmd', label: '命令建议', q: '给出可手工执行的配置命令建议。' }
    ]
  }
  return [
    { id: 'diag', label: '全网态势', q: '诊断全网健康与优先风险。' },
    { id: 'alm', label: '告警优先', q: '当前应优先处理哪些告警/事件？' },
    { id: 'cmd', label: '命令建议', q: '我想要配置命令建议。' }
  ]
})

function contextParams() {
  const q = route.query || {}
  const qd = q.deviceId != null && q.deviceId !== '' ? Number(q.deviceId) : undefined
  const qa = q.alarmId != null && q.alarmId !== '' ? Number(q.alarmId) : undefined
  const deviceId = bus.deviceId != null ? Number(bus.deviceId) : (Number.isFinite(qd) ? qd : undefined)
  const alarmId = bus.alarmId != null ? Number(bus.alarmId) : (Number.isFinite(qa) ? qa : undefined)
  return {
    pagePath: route.path,
    deviceId: Number.isFinite(deviceId) ? deviceId : undefined,
    alarmId: Number.isFinite(alarmId) ? alarmId : undefined,
    intent: bus.source || undefined
  }
}

function clearFocus() {
  clearOpsAssistantContext()
  job.value = null
  runLog.value = []
  showRunHistory.value = false
}

function shortToolMessage(res) {
  if (!res) return '无结果'
  if (res.cancelled) return '已取消'
  if (res.ok === false) return `失败：${res.error || '未知错误'}`
  let text = res.message || '已完成'
  if (res.steps?.length) {
    const n = res.steps.length
    text += `（${n} 步）`
  }
  if (res.configText) {
    text += ` · 配置 ${String(res.configText).length} 字（已在业务区/弹窗查看，此处不展开）`
  }
  if (text.length > 220) text = text.slice(0, 220) + '…'
  return text
}

function pushRunLog(tool, res) {
  const now = new Date()
  const time = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`
  runLog.value = [
    {
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      label: tool.label || tool.name || '动作',
      message: shortToolMessage(res),
      ok: res?.cancelled ? null : res?.ok !== false,
      time
    },
    ...runLog.value
  ].slice(0, 12)
  // 执行后不滚动结论区，用户可继续对照结论点下一步
}

function focusInput() {
  nextTick(() => {
    const el = inputRef.value
    if (!el) return
    if (typeof el.focus === 'function') el.focus()
    else if (el.textarea && typeof el.textarea.focus === 'function') el.textarea.focus()
    else el?.input?.focus?.()
  })
}

async function loadSettings() {
  try {
    const s = await llmApi.getSettings()
    Object.assign(settings, {
      enabled: !!s.enabled, provider: s.provider || 'ollama', baseUrl: s.baseUrl || '',
      model: s.model || '', apiKey: s.apiKey || '', hasApiKey: !!s.hasApiKey
    })
    Object.assign(form, {
      enabled: !!s.enabled, provider: s.provider || 'ollama',
      baseUrl: s.baseUrl || 'http://127.0.0.1:11434',
      model: s.model || 'qwen2.5:7b', apiKey: s.apiKey || ''
    })
  } catch { /* ignore */ }
}

async function saveSettings() {
  saving.value = true
  try {
    await llmApi.updateSettings({ ...form })
    ElMessage.success('已保存')
    await loadSettings()
    showSettings.value = false
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function testConn() {
  testing.value = true
  try {
    const res = await llmApi.test()
    if (res?.ok) ElMessage.success(res.message || '连通正常')
    else ElMessage.error(res?.error || res?.message || '测试失败')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '测试失败')
  } finally {
    testing.value = false
  }
}

function normalizeActions(res) {
  const raw = res?.suggestedActions || res?.proposedTools || res?.tools || res?.actions || []
  if (!Array.isArray(raw)) return []
  return raw
    .map((a) => ({
      name: a?.name || a?.id || '',
      label: a?.label || a?.name || a?.id || '执行',
      args: a?.args && typeof a.args === 'object' ? a.args : {},
      needConfirm: !!(a?.needConfirm ?? a?.need_confirm)
    }))
    .filter((a) => a.name)
}

function applyJob(res) {
  if (!res) return
  lastFallback.value = res.fallback === true || res.source === 'rules' || res.provider === 'rules'
  const evidenceRaw = Array.isArray(res.toolRuns)
    ? res.toolRuns
    : (Array.isArray(res.evidence) ? res.evidence : [])
  const evidence = evidenceRaw.map((e) => ({
    label: e.label || e.tool || e.name,
    tool: e.tool || e.name,
    message: e.message || e.summary || '',
    ok: e.ok
  }))
  const actions = normalizeActions(res)
  const intent = res.intent
  const intentLabel = typeof intent === 'object' && intent
    ? (intent.label || intent.name || '')
    : (res.intentLabel || intent || '')
  runLog.value = []
  showRunHistory.value = false
  job.value = {
    verdict: res.answer || res.message || '无结论',
    evidence,
    actions,
    intentLabel,
    modeNote: res.disclaimer || res.note || res.hint || '',
    level: res.ok === false ? 'bad' : (lastFallback.value ? 'rules' : 'llm')
  }
  nextTick(() => {
    const el = jobScrollRef.value
    if (el) el.scrollTop = 0
  })
}

async function assist(question) {
  const q = String(question || '').trim()
  if (!q) return
  sending.value = true
  try {
    const res = await llmApi.assist({
      question: q,
      ...contextParams(),
      history: []
    })
    applyJob(res)
  } catch (e) {
    runLog.value = []
    job.value = {
      verdict: '提问失败：' + (e?.response?.data?.message || e?.message || '网络错误'),
      level: 'bad',
      modeNote: '可修改问题后再次发送',
      evidence: [],
      actions: []
    }
    if (!input.value.trim() && q) input.value = q
    ElMessage.error(e?.response?.data?.message || e?.message || '助手请求失败')
  } finally {
    sending.value = false
    // 不在诊断后强制 focus，避免浏览器把页面滚到输入框
  }
}

function runDiagnose() {
  const bits = []
  if (bus.alarmId) bits.push(`告警#${bus.alarmId}`)
  if (bus.deviceId) bits.push(`设备#${bus.deviceId}`)
  const focus = bits.length ? bits.join(' ') : '全网'
  return assist(`诊断${focus}：基于工具证据给出简短结论与可执行动作。`)
}

function runIntent(it) {
  return assist(it.q)
}

function send() {
  const q = input.value.trim()
  if (sending.value) return
  if (!q) {
    ElMessage.info('请输入问题，或点「诊断」/快捷意图')
    focusInput()
    return
  }
  const question = q
  input.value = ''
  return assist(question)
}

async function runJobAction(a, ai) {
  executingKey.value = 'job-' + ai
  try {
    const tool = {
      name: a.name || a.id,
      label: a.label,
      needConfirm: a.needConfirm,
      args: { ...(a.args || {}) }
    }
    const ctx = contextParams()
    if (tool.args.deviceId == null && ctx.deviceId != null) tool.args.deviceId = ctx.deviceId
    if (tool.args.alarmId == null && ctx.alarmId != null) tool.args.alarmId = ctx.alarmId
    tool.args.source = bus.source || 'assistant'
    const res = await executeTool(tool)
    if (res?.cancelled) return
    publishToolResult(res, { tool: tool.name, source: 'assistant' })
    const detail = res?.detail || {}
    if ((tool.name === 'navigate_hint' || detail.navigate) && detail.path) {
      const { offerToolNavigate } = await import('@/composables/useSafeNavigate')
      await offerToolNavigate(router, route, { toolName: tool.name, detail })
    }
    pushRunLog(tool, res)
  } finally {
    executingKey.value = ''
  }
}

watch(
  () => bus.autoAskToken,
  async (token) => {
    if (!token || sending.value) return
    const q = bus.autoAskQuestion || '诊断当前焦点'
    await assist(q)
  }
)

onMounted(() => loadSettings())
defineExpose({ focusInput, loadSettings, runDiagnose })
</script>

<style scoped>
/* 浮窗：深色作业卡（叠在业务页上） */
.oap {
  --oap-bg: #0b1220;
  --oap-panel: #121a2b;
  --oap-line: #243044;
  --oap-text: #e8eef8;
  --oap-muted: #8b9bb4;
  --oap-accent: var(--el-color-primary);
  --oap-fill: #152036;
  --oap-input-bg: #121a2b;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--oap-bg);
  color: var(--oap-text);
  font-family: inherit;
  overflow: hidden;
  overflow-anchor: none;
}

/* 内嵌：跟随网管浅色工作台 */
.oap.embedded {
  --oap-bg: var(--el-bg-color);
  --oap-panel: var(--el-fill-color-blank, #fff);
  --oap-line: var(--el-border-color-lighter);
  --oap-text: var(--el-text-color-primary);
  --oap-muted: var(--el-text-color-secondary);
  --oap-fill: var(--el-fill-color-light);
  --oap-input-bg: var(--el-fill-color-blank, #fff);
  border: none;
  border-radius: 0;
  overflow: hidden;
}

.oap-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--oap-line);
  background: var(--oap-fill);
  flex-shrink: 0;
}
.oap.embedded .oap-hd {
  background: transparent;
  padding: 6px 10px;
}
.oap-hd-left { display: flex; align-items: center; gap: 8px; }
.oap-brand { font-weight: 600; font-size: 13px; }
.oap.embedded .oap-brand { font-size: 13px; font-weight: 600; }
.oap-hd-right :deep(.el-button) { color: var(--oap-muted); }
.oap-settings { padding: 12px; overflow: auto; flex: 1; background: var(--oap-panel); }
.oap-settings :deep(.el-form-item__label) { color: var(--oap-muted); }
.oap-settings-actions { display: flex; gap: 8px; }

.oap-body { flex: 1; display: flex; flex-direction: column; min-height: 0; }

.oap-focus {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--oap-line);
  background: var(--oap-fill);
  flex-shrink: 0;
}
.oap.embedded .oap-focus {
  background: var(--el-fill-color-lighter);
  padding: 8px 10px;
}
.oap-focus-k {
  font-size: 12px;
  color: var(--oap-muted);
  margin-bottom: 2px;
}
.oap-focus-v {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}
.oap-focus-acts { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }

.oap-ask {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--oap-line);
  background: var(--oap-panel);
  flex-shrink: 0;
}
.oap.embedded .oap-ask { padding: 8px 10px; }
.oap-ask.compact { padding-top: 6px; padding-bottom: 6px; gap: 0; }
.oap-ask-row { display: flex; gap: 8px; align-items: flex-end; }
.oap-ask-row :deep(.el-textarea__inner) {
  background: var(--oap-input-bg);
  color: var(--oap-text);
  box-shadow: 0 0 0 1px var(--oap-line) inset;
}
.oap.embedded .oap-ask-row :deep(.el-textarea__inner) {
  box-shadow: 0 0 0 1px var(--el-border-color) inset;
}

.oap-intents {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.oap:not(.embedded) .oap-intents :deep(.el-button) {
  --el-button-text-color: var(--oap-text);
  --el-button-bg-color: var(--oap-fill);
  --el-button-hover-bg-color: #1a2740;
}

.oap-job {
  flex: 1;
  overflow: auto;
  padding: 12px;
  min-height: 0;
  overflow-anchor: none;
}
.oap.embedded .oap-job { padding: 12px 10px; }

.oap-feedback {
  flex-shrink: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid var(--oap-line);
  background: var(--oap-panel);
  font-size: 12px;
  line-height: 1.45;
}
.oap.embedded .oap-feedback {
  background: var(--el-fill-color-lighter);
}
.oap-feedback.ok { border-left: 3px solid var(--el-color-success); }
.oap-feedback.bad { border-left: 3px solid var(--el-color-danger); }
.oap-feedback-main {
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 4px 8px;
  align-items: baseline;
}
.oap-feedback-k {
  color: var(--oap-muted);
  flex-shrink: 0;
}
.oap-feedback-msg {
  color: var(--oap-text);
  word-break: break-word;
}
.oap-run-history {
  flex-shrink: 0;
  max-height: 96px;
  overflow: auto;
  padding: 0 12px 8px;
  border-top: 1px dashed var(--oap-line);
  background: var(--oap-panel);
  font-size: 12px;
}
.oap.embedded .oap-run-history {
  background: var(--el-fill-color-lighter);
}
.oap-run-history-item {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 8px;
  padding: 4px 0;
  color: var(--oap-text);
  opacity: 0.9;
}
.oap-run-history-item.bad { color: var(--el-color-danger); }
.oap-runlog-time { color: var(--oap-muted); flex-shrink: 0; }

.oap-actions-bar {
  flex-shrink: 0;
  padding: 8px 12px 10px;
  border-top: 1px solid var(--oap-line);
  background: var(--oap-fill);
}
.oap.embedded .oap-actions-bar {
  background: var(--el-color-primary-light-9, #ecf5ff);
  border-top-color: var(--el-color-primary-light-5, #b3d8ff);
}

.oap-empty {
  color: var(--oap-muted);
  font-size: 13px;
  line-height: 1.55;
  padding: 16px 4px;
}

.oap-verdict {
  border: 1px solid var(--oap-line);
  border-radius: 8px;
  padding: 12px 14px;
  background: var(--oap-fill);
  margin-bottom: 10px;
}
.oap.embedded .oap-verdict {
  background: var(--el-fill-color-blank, #fff);
  border-color: var(--el-border-color-lighter);
}
.oap-verdict.llm { border-left: 3px solid var(--el-color-success); }
.oap-verdict.rules { border-left: 3px solid var(--el-color-info); }
.oap-verdict.bad { border-left: 3px solid var(--el-color-danger); }
.oap-verdict-label {
  font-size: 12px;
  color: var(--oap-muted);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.oap-verdict-text {
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}
.oap-verdict-note {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-color-warning);
}

.oap-collapse { margin-bottom: 4px; border: none; }
.oap-collapse :deep(.el-collapse-item__header) {
  height: 36px;
  font-size: 12px;
  color: var(--oap-muted);
  background: transparent;
  border-bottom-color: var(--oap-line);
}
.oap-collapse :deep(.el-collapse-item__wrap) {
  background: transparent;
  border-bottom: none;
}
.oap-collapse :deep(.el-collapse-item__content) { padding-bottom: 8px; }

.oap-title-hint {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 400;
  color: var(--oap-muted);
  text-transform: none;
  letter-spacing: 0;
}
.oap-block-title {
  font-size: 12px;
  color: var(--oap-muted);
  margin-bottom: 6px;
}
.oap-ev-list {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--oap-text);
  line-height: 1.55;
  opacity: 0.9;
}
.oap-ev-list .bad { color: var(--el-color-danger); }
.oap-acts { display: flex; flex-wrap: wrap; gap: 6px; }
.oap-hint { margin: 6px 0 0; font-size: 12px; color: var(--oap-muted); }
</style>
