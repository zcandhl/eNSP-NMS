/**
 * @file    RunResultPanel.vue
 * @module  视图页/验收组件
 * @desc    验收执行结果面板。
 *
 * @author  ZC
 * @date    2026-11-22 * @version 1.0.0
 * @requires @element-plus/icons-vue, element-plus, vue, @/api/acceptance, @/stores/auth
 */

<template>
  <div class="result-panel">
    <div class="result-head">
      <div class="head-left">
        <span class="title">运行结果</span>
        <el-tag v-if="batch.no" size="small" effect="plain" class="no">{{ batch.no }}</el-tag>
        <el-tag size="small" :type="statusType" effect="light">{{ statusText }}</el-tag>
      </div>
      <div class="head-right">
        <span class="sum">
          <span class="dot ok"></span>PASS {{ counts.PASS }}
          <span class="dot warn"></span>FINDING {{ counts.FINDING }}
          <span class="dot bad"></span>FAIL {{ counts.FAIL }}
          <span class="dot mute"></span>PENDING {{ counts.PENDING }}
        </span>
        <el-button
          v-if="canAiSummary"
          size="small"
          type="primary"
          plain
          class="stop-btn"
          :loading="aiLoading"
          @click="doAiSummary"
        >AI 摘要</el-button>
        <el-button
          v-if="batch.status === 'RUNNING'"
          size="small"
          type="danger"
          plain
          class="stop-btn"
          @click="$emit('stop')"
        >停止运行</el-button>
        <el-button
          v-if="batch.status === 'DONE'"
          size="small"
          type="warning"
          plain
          class="stop-btn"
          @click="$emit('void')"
        >作废</el-button>
        <el-button
          v-if="batch.status === 'INTERRUPTED' || batch.status === 'FAILED'"
          size="small"
          type="danger"
          plain
          class="stop-btn"
          @click="$emit('delete')"
        >删除</el-button>
      </div>
    </div>

    <div v-if="batch.statusMessage" class="status-note" :class="`note-${(batch.status || '').toLowerCase()}`">
      {{ batch.statusMessage }}
    </div>

    <div v-if="batch.status === 'RUNNING'" class="progress-wrap">
      <el-progress
        :percentage="percent"
        :stroke-width="4"
        :show-text="false"
        status="success"
      />
      <div class="progress-text">
        运行中 {{ batch.progress?.done || 0 }}/{{ batch.progress?.total || 0 }}
        <span v-if="batch.progress?.pending">· 待人工确认 {{ batch.progress.pending }} 项</span>
        <span v-if="batch.progress?.current">· 当前：{{ batch.progress.current }}</span>
      </div>
    </div>

    <div class="result-list" v-if="batch.items?.length">
      <div
        v-for="it in batch.items"
        :key="it.checkId"
        class="result-row"
        :class="[`st-${it.status.toLowerCase()}`, { active: activeId === it.checkId }]"
        @click="toggle(it)"
      >
        <div class="row-main">
          <span class="bar" :class="`bar-${it.status.toLowerCase()}`"></span>
          <span class="code">{{ it.code }}</span>
          <span class="name">{{ it.title }}</span>
          <span class="owner" :class="{ mine: isMine(it) }">
            {{ memberName(it.owner) }}<em v-if="isMine(it)" class="mine-badge">我</em>
          </span>
          <span class="dur" v-if="it.durationMs">{{ (it.durationMs / 1000).toFixed(1) }}s</span>
          <el-tag size="small" :type="tagType(it.status)" effect="plain" class="tag">
            {{ statusLabel(it.status) }}
          </el-tag>
          <el-icon class="chev" :class="{ open: activeId === it.checkId }"><ArrowRight /></el-icon>
        </div>

        <div v-if="activeId === it.checkId" class="row-detail" @click.stop>
          <div v-if="it.message" class="msg" :class="`msg-${it.status.toLowerCase()}`">
            {{ it.message }}
          </div>

          <div v-if="metricList(it).length" class="metrics">
            <div v-for="m in metricList(it)" :key="m.key" class="metric">
              <span class="mk">{{ m.label }}</span>
              <span class="mv">{{ m.value }}</span>
            </div>
          </div>

          <div v-if="it.findings?.length" class="findings">
            <div v-for="f in it.findings" :key="f.code" class="finding">
              <el-tag size="small" type="warning" effect="plain">{{ f.code }}</el-tag>
              <span class="fdev">{{ f.device }}</span>
              <span class="fdesc">{{ f.detail }}</span>
            </div>
          </div>

          <div class="detail-actions">
            <el-button
              v-if="it.status === 'PENDING'"
              size="small"
              type="primary"
              plain
              @click="$emit('confirm', it)"
            >确认执行</el-button>
            <el-button
              size="small"
              :disabled="!it.rawOutput"
              @click="$emit('view-raw', it)"
            >查看原始输出</el-button>
          </div>
        </div>
      </div>
    </div>

    <el-empty v-else description="尚未运行。点击右上角「运行」开始本次验收" :image-size="72" />

    <el-dialog v-model="aiVisible" title="AI 执行摘要" width="640px" append-to-body>
      <div v-loading="aiLoading" class="ai-body" :style="{ minHeight: aiLoading ? '120px' : 'auto' }">
        <template v-if="aiResult">
          <div class="ai-section">
            <div class="ai-h">执行摘要</div>
            <p class="ai-p">{{ aiResult.summary }}</p>
          </div>
          <div v-if="aiResult.rootCauses?.length" class="ai-section">
            <div class="ai-h">根因分析</div>
            <div v-for="(c, i) in aiResult.rootCauses" :key="'c' + i" class="ai-line">{{ c }}</div>
          </div>
          <div v-if="aiResult.suggestions?.length" class="ai-section">
            <div class="ai-h">整改建议</div>
            <div v-for="(s, i) in aiResult.suggestions" :key="'s' + i" class="ai-line">{{ s }}</div>
          </div>
          <div class="ai-meta">
            {{ aiResult.provider === 'ollama' ? '本地模型（Ollama）' : 'OpenAI 兼容 API（云端）' }}
            · {{ aiResult.model }} · 生成于 {{ aiResult.generatedAt }}
          </div>
          <el-alert :title="aiResult.disclaimer" type="warning" :closable="false" class="ai-tip" />
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { acceptanceApi } from '@/api/acceptance'
import { metricLabel } from './judgeRule'

const props = defineProps({
  batch: { type: Object, default: () => ({ items: [] }) },
  members: { type: Array, default: () => [] }
})
defineEmits(['view-raw', 'confirm', 'stop', 'void', 'delete'])

const auth = useAuthStore()
const activeId = ref(null)

const aiVisible = ref(false)
const aiLoading = ref(false)
const aiResult = ref(null)

const canAiSummary = computed(() =>
  ['DONE', 'INTERRUPTED', 'FAILED'].includes(props.batch?.status) && (props.batch?.items?.length || 0) > 0
)

async function doAiSummary() {
  aiVisible.value = true
  aiLoading.value = true
  aiResult.value = null
  try {
    aiResult.value = await acceptanceApi.aiSummary(props.batch.batchId)
  } catch (e) {
    aiVisible.value = false
    ElMessage.error(e?.response?.data?.error || e?.message || 'AI 摘要生成失败')
  } finally {
    aiLoading.value = false
  }
}

// owner（username）→ 真实姓名；未匹配到时原样显示
function memberName(username) {
  const m = props.members.find((x) => x.username === username)
  return m ? m.realName : username
}
// 该项是否属于当前登录人
function isMine(item) {
  return item.owner && item.owner === auth.user?.username
}

watch(
  () => props.batch?.batchId,
  () => {
    activeId.value = null
  }
)

const counts = computed(() => {
  const c = { PASS: 0, FINDING: 0, FAIL: 0, PENDING: 0 }
  ;(props.batch?.items || []).forEach((i) => {
    if (c[i.status] !== undefined) c[i.status] += 1
  })
  return c
})

const percent = computed(() => {
  const p = props.batch?.progress
  if (!p || !p.total) return 0
  return Math.round((p.done / p.total) * 100)
})

const waitingConfirm = computed(
  () => props.batch?.status === 'RUNNING' && props.batch?.progress?.current === '等待人工确认'
)

const statusType = computed(() => {
  const s = props.batch?.status
  if (s === 'RUNNING') return waitingConfirm.value ? 'warning' : 'primary'
  if (s === 'DONE') return counts.value.FINDING || counts.value.FAIL ? 'warning' : 'success'
  if (s === 'FAILED') return 'danger'
  if (s === 'INTERRUPTED') return 'warning'
  if (s === 'VOIDED') return 'info'
  return 'info'
})

const statusText = computed(() => {
  const s = props.batch?.status
  if (s === 'RUNNING') return waitingConfirm.value ? '等待人工确认' : '运行中'
  if (s === 'DONE') return '已完成'
  if (s === 'FAILED') return '执行失败'
  if (s === 'INTERRUPTED') return '已中断'
  if (s === 'VOIDED') return '已作废'
  return '未运行'
})

function tagType(s) {
  return { PASS: 'success', FINDING: 'warning', FAIL: 'danger', PENDING: 'info', SKIPPED: 'info' }[s] || 'info'
}

function statusLabel(s) {
  return {
    PASS: 'PASS', FINDING: 'FINDING', FAIL: 'FAIL', PENDING: 'PENDING',
    WAITING: '排队', RUNNING: '执行中', SKIPPED: '已跳过'
  }[s] || s
}

function toggle(it) {
  activeId.value = activeId.value === it.checkId ? null : it.checkId
}

function metricList(it) {
  return Object.entries(it.metrics || {})
    .filter(([k, v]) => k !== 'detail' && v !== null && v !== undefined && v !== '')
    .map(([k, v]) => ({ key: k, label: metricLabel(it.type, k), value: typeof v === 'number' ? v : String(v) }))
}
</script>

<style scoped lang="scss">
.result-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 2px 10px;
  flex: 0 0 auto;

  .head-left {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .title {
    font-size: 13px;
    font-weight: 500;
    color: var(--el-text-color-primary);
  }
  .no {
    font-family: Consolas, Monaco, monospace;
    font-size: 11px;
  }
  .sum {
    font-size: 11px;
    color: var(--el-text-color-secondary);
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }
  .stop-btn {
    margin-left: 12px;
  }
  .dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    display: inline-block;
    margin-left: 8px;

    &.ok { background: var(--el-color-success); }
    &.warn { background: var(--el-color-warning); }
    &.bad { background: var(--el-color-danger); }
    &.mute { background: var(--el-text-color-placeholder); }
  }
}
.progress-wrap {
  flex: 0 0 auto;
  padding: 0 2px 8px;
}
.status-note {
  flex: 0 0 auto;
  margin: 0 2px 8px;
  padding: 6px 10px;
  font-size: 12px;
  line-height: 1.5;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);

  &.note-interrupted,
  &.note-failed {
    background: var(--el-color-danger-light-9);
    color: var(--el-color-danger);
  }
}
.progress-text {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.result-list {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-bg-color);
}
.result-row {
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
  transition: background 0.15s;

  &:last-child {
    border-bottom: none;
  }
  &:hover {
    background: var(--el-fill-color-light);
  }
  &.active {
    background: var(--el-fill-color-light);
  }

  &.st-finding .row-main { background: var(--el-color-warning-light-9); }
}
.row-main {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 42px;
  padding: 0 10px 0 0;
}
.bar {
  width: 3px;
  height: 100%;
  flex: 0 0 3px;

  &.bar-pass { background: var(--el-color-success); }
  &.bar-finding { background: var(--el-color-warning); }
  &.bar-fail { background: var(--el-color-danger); }
  &.bar-pending { background: var(--el-color-info-light-3); }
  &.bar-waiting { background: var(--el-border-color); }
  &.bar-skipped { background: var(--el-border-color-darker); }
}
.code {
  font-family: Consolas, Monaco, monospace;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  flex: 0 0 34px;
}
.name {
  font-size: 13px;
  color: var(--el-text-color-primary);
  flex: 1 1 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.owner {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  flex: 0 0 auto;
  &.mine {
    color: var(--el-color-primary);
  }
}
.mine-badge {
  display: inline-block;
  margin-left: 4px;
  padding: 0 4px;
  font-size: 10px;
  font-style: normal;
  line-height: 15px;
  color: #fff;
  background: var(--el-color-primary);
  border-radius: 3px;
}
.dur {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  font-family: Consolas, Monaco, monospace;
  flex: 0 0 34px;
  text-align: right;
}
.tag {
  flex: 0 0 auto;
  font-size: 10px;
}
.chev {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  transition: transform 0.2s;

  &.open {
    transform: rotate(90deg);
  }
}
.row-detail {
  padding: 0 12px 12px;
  cursor: default;
}
.msg {
  font-size: 12px;
  padding: 6px 10px;
  border-radius: 4px;
  margin-bottom: 8px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);

  &.msg-finding {
    background: var(--el-color-warning-light-9);
    color: var(--el-color-warning-dark-2);
  }
  &.msg-fail {
    background: var(--el-color-danger-light-9);
    color: var(--el-color-danger);
  }
}
.metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 8px;
  margin-bottom: 8px;
}
.metric {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;
  padding: 3px 8px;
  border-radius: 4px;
  background: var(--el-fill-color-lighter);

  .mk {
    font-size: 11px;
    color: var(--el-text-color-secondary);
  }
  .mv {
    font-size: 12px;
    font-family: Consolas, Monaco, monospace;
    color: var(--el-text-color-primary);
  }
}
.findings {
  margin-bottom: 8px;
}
.finding {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  padding: 4px 0;

  .fdev {
    font-family: Consolas, Monaco, monospace;
    color: var(--el-text-color-regular);
  }
  .fdesc {
    color: var(--el-text-color-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
.detail-actions {
  display: flex;
  gap: 8px;
}
.ai-body {
  padding: 2px 4px;
}
.ai-section {
  margin-bottom: 14px;
}
.ai-h {
  font-size: 12px;
  font-weight: 500;
  color: var(--el-color-primary);
  margin-bottom: 6px;
}
.ai-p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-primary);
}
.ai-line {
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
  padding: 4px 8px;
  border-left: 2px solid var(--el-color-primary-light-5);
  margin-bottom: 4px;
  background: var(--el-fill-color-lighter);
  border-radius: 0 4px 4px 0;
}
.ai-meta {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin: 10px 0 8px;
}
.ai-tip {
  font-size: 11px;
}
</style>
