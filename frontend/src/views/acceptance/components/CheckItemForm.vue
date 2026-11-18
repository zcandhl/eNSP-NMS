<template>
  <div class="check-form" :class="{ 'in-drawer': inDrawer }">
    <div class="form-head">
      <span class="code">{{ check.code }}</span>
      <el-input v-model="check.title" size="small" placeholder="检查项名称" />
    </div>

    <el-form label-position="top" size="small" class="form-body">
      <el-form-item label="检查类型">
        <el-select v-model="check.type" class="full" @change="onTypeChange">
          <el-option
            v-for="t in checkTypes"
            :key="t.type"
            :label="`${t.label} · ${t.type}`"
            :value="t.type"
          />
        </el-select>
        <div class="hint">{{ typeMeta?.description }}</div>
      </el-form-item>

      <div class="fields">
        <el-form-item v-for="f in currentFields" :key="f.key" :label="f.label">
          <el-input-number
            v-if="f.kind === 'number'"
            v-model="check.params[f.key]"
            :min="f.min"
            :max="f.max"
            :step="f.step || 1"
            :controls="false"
            size="small"
            class="full"
          />
          <el-select
            v-else-if="f.kind === 'select'"
            v-model="check.params[f.key]"
            size="small"
            class="full"
          >
            <el-option v-for="o in f.options" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-input
            v-else
            v-model="check.params[f.key]"
            size="small"
            :placeholder="f.placeholder || ''"
          />
        </el-form-item>
      </div>

      <el-form-item label="判定规则（选三项就成一句话）">
        <div class="rule-box">
          <div v-for="(it, idx) in ruleItems" :key="idx" class="rule-row">
            <el-select
              v-model="it.metric"
              size="small"
              class="r-metric"
              placeholder="看哪个指标"
              @change="onMetricChange(idx)"
            >
              <el-option v-for="m in metrics" :key="m.key" :label="m.label" :value="m.key" />
            </el-select>
            <el-select v-model="it.op" size="small" class="r-op" @change="syncRule">
              <el-option v-for="o in ops(it.metric)" :key="o.op" :label="o.label" :value="o.op" />
            </el-select>
            <template v-if="!isBool(it)">
              <el-select
                v-model="it.targetRef"
                size="small"
                class="r-target"
                placeholder="跟什么比"
                @change="syncRule"
              >
                <el-option-group v-if="currentFields.length" label="方案参数（设计值）">
                  <el-option
                    v-for="f in currentFields"
                    :key="f.key"
                    :label="`${f.label} ${check.params[f.key]}`"
                    :value="`param:${f.key}`"
                  />
                </el-option-group>
                <el-option-group label="实测指标">
                  <el-option v-for="m in metrics" :key="m.key" :label="m.label" :value="`metric:${m.key}`" />
                </el-option-group>
                <el-option label="固定值（自己填）" value="value" />
              </el-select>
              <el-input-number
                v-if="it.targetRef === 'value'"
                v-model="it.targetValue"
                size="small"
                :controls="false"
                class="r-num"
                @change="syncRule"
              />
            </template>
            <el-button
              v-if="ruleItems.length > 1"
              text
              size="small"
              class="r-del"
              @click="removeRuleItem(idx)"
            >
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>

          <div class="rule-sentence">
            <span class="rule-tag">判定</span>
            <span>{{ sentence || '还没有可判定的规则，请在上面选指标与比较方式' }}</span>
          </div>

          <div class="rule-foot">
            <el-button link type="primary" size="small" @click="addRuleItem">+ 再加一个条件</el-button>
            <el-button link size="small" @click="toggleAdvanced">
              {{ advanced ? '收起高级写法' : '高级写法（表达式）' }}
            </el-button>
          </div>

          <div v-if="advanced" class="rule-adv">
            <el-input
              v-model="check.assertExpr"
              type="textarea"
              :rows="2"
              class="mono"
              placeholder="例如 ospf.neighborCount >= expectedNeighbors"
              @change="syncFromExpr"
            />
            <div v-if="assertError" class="err">{{ assertError }}</div>
            <div v-else class="hint">
              <span class="hint-label">参考</span>
              <code>{{ typeMeta?.assertHint }}</code>
            </div>
          </div>
        </div>
      </el-form-item>

      <div class="row2">
        <el-form-item label="负责人">
          <el-select
            v-model="check.owner"
            size="small"
            class="full"
            filterable
            allow-create
            default-first-option
            :loading="membersLoading"
            placeholder="选择或输入账号"
          >
            <el-option
              v-for="m in memberOptions"
              :key="m.username"
              :label="`${m.realName}（${m.username}）`"
              :value="m.username"
            />
          </el-select>
          <div class="hint owner-hint">
            负责人绑定系统账号：登录后可在结果区看到"我"负责的项
          </div>
        </el-form-item>
        <el-form-item label="严重级别">
          <el-select v-model="check.severity" size="small" class="full">
            <el-option label="致命" value="critical" />
            <el-option label="高" value="high" />
            <el-option label="中" value="medium" />
            <el-option label="低" value="low" />
          </el-select>
        </el-form-item>
      </div>

      <div class="switch-row">
        <label class="sw">
          <el-switch v-model="check.enabled" size="small" />
          <span>启用</span>
        </label>
        <label class="sw">
          <el-switch v-model="check.allowFinding" size="small" />
          <span>允许留问题</span>
        </label>
        <label class="sw">
          <el-switch v-model="check.needConfirm" size="small" />
          <span>需人工确认</span>
        </label>
      </div>

      <el-collapse class="vars">
        <el-collapse-item title="可用变量（判定表达式里能引用）" name="v">
          <div class="var-grid">
            <code v-for="v in variables" :key="v">{{ v }}</code>
          </div>
        </el-collapse-item>
      </el-collapse>
    </el-form>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { Delete } from '@element-plus/icons-vue'
import { describe, buildExpr, parseExpr, metricList, metricDef, opList } from './judgeRule'

// 说明：父组件传入的 check 为同一引用，此处直接修改其属性，父级立即可见。
// 这样避免为每个字段单独 emit，保持表单代码可读。
const props = defineProps({
  check: { type: Object, required: true },
  checkTypes: { type: Array, default: () => [] },
  members: { type: Array, default: () => [] },
  membersLoading: { type: Boolean, default: false },
  // 抽屉内使用时去掉顶部边框（抽屉本身已有分隔与留白）
  inDrawer: { type: Boolean, default: false }
})

// 负责人候选：来自系统账号（后端 /members），无数据时回退到队员编号
const memberOptions = computed(() =>
  props.members.length
    ? props.members
    : [
        { username: '1号', realName: '1号' },
        { username: '2号', realName: '2号' },
        { username: '3号', realName: '3号' },
        { username: '4号', realName: '4号' }
      ]
)

const variables = [
  'topology.matched', 'topology.total',
  'reachability.up', 'reachability.total',
  'ospf.neighborCount',
  'vrrp.masterCount', 'vrrp.backupCount', 'vrrp.switchoverMs',
  'dhcp.poolCount', 'dhcp.maxUsage',
  'vlan.matched', 'vlan.total', 'vlan.actualSuffix',
  'minComplianceScore',
  'config.diffLines',
  'custom.matched',
  'expectedNodes', 'expectedNeighbors', 'expectMaster', 'expectBackup',
  'expectedPools', 'expectedVlans', 'minScore', 'allowDiffLines', 'switchoverMaxMs'
]

const typeMeta = computed(() => props.checkTypes.find((t) => t.type === props.check.type))
const currentFields = computed(() => typeMeta.value?.fields || [])

/* ── 判定规则：三段式（指标 + 比较 + 目标），生成 assertExpr 交给后端 ── */
const metrics = computed(() => metricList(props.check.type))
const advanced = ref(false)
const ruleItems = ref([])

const paramKeys = computed(() => currentFields.value.map((f) => f.key))

function labelOf(key) {
  return currentFields.value.find((f) => f.key === key)?.label || key
}

function ops(metricKey) {
  return opList(props.check.type, metricKey)
}

function isBool(it) {
  return metricDef(props.check.type, it.metric)?.kind === 'bool'
}

/** 内部条目 → 界面绑定字段：targetRef = param:xx / metric:xx / value */
function toUi(it) {
  const ref =
    it.targetKind === 'param'
      ? `param:${it.targetValue}`
      : it.targetKind === 'metric'
        ? `metric:${it.targetValue}`
        : 'value'
  return { ...it, targetRef: ref }
}

/** 界面绑定字段 → 内部条目 */
function toItem(it) {
  const out = { metric: it.metric, op: it.op }
  if (isBool(it)) return out
  const ref = it.targetRef || 'value'
  if (ref.startsWith('param:')) {
    out.targetKind = 'param'
    out.targetValue = ref.slice(6)
  } else if (ref.startsWith('metric:')) {
    out.targetKind = 'metric'
    out.targetValue = ref.slice(7)
  } else {
    out.targetKind = 'value'
    out.targetValue = it.targetValue ?? 0
  }
  return out
}

function initRule() {
  const pk = paramKeys.value
  const fromSaved = props.check.rule?.items
  const parsed =
    (Array.isArray(fromSaved) && fromSaved.length ? fromSaved : null) ||
    parseExpr(props.check.assertExpr, props.check.type, pk)
  if (parsed && parsed.length) {
    ruleItems.value = parsed.map(toUi)
    advanced.value = false
  } else {
    ruleItems.value = []
    advanced.value = true
  }
}

const sentence = computed(() => {
  const items = ruleItems.value.filter((it) => it.metric && it.op)
  if (!items.length) return ''
  return describe(items.map(toItem), props.check.type, props.check.params, labelOf)
})

/** 把界面上的规则写回 check（同时更新 assertExpr，后端只认它） */
function syncRule() {
  const items = ruleItems.value.filter((it) => it.metric && it.op).map(toItem)
  if (!items.length) return
  props.check.rule = { items }
  props.check.assertExpr = buildExpr(items, props.check.type)
}

function onMetricChange(idx) {
  const it = ruleItems.value[idx]
  const list = ops(it.metric)
  if (!list.some((o) => o.op === it.op)) it.op = list[0].op
  syncRule()
}

function addRuleItem() {
  const first = metrics.value[0]
  if (!first) return
  ruleItems.value.push({
    metric: first.key,
    op: ops(first.key)[0].op,
    targetRef: paramKeys.value.length ? `param:${paramKeys.value[0]}` : 'value',
    targetValue: 0
  })
  syncRule()
}

function removeRuleItem(idx) {
  ruleItems.value.splice(idx, 1)
  syncRule()
}

/** 高级写法手改后，尝试重新解析回三段式 */
function syncFromExpr() {
  const parsed = parseExpr(props.check.assertExpr, props.check.type, paramKeys.value)
  if (parsed && parsed.length) ruleItems.value = parsed.map(toUi)
}

function toggleAdvanced() {
  if (advanced.value) {
    const parsed = parseExpr(props.check.assertExpr, props.check.type, paramKeys.value)
    if (parsed && parsed.length) {
      ruleItems.value = parsed.map(toUi)
      advanced.value = false
    } else {
      // 解析不了就留在高级写法，避免把已有表达式覆盖掉
      advanced.value = true
    }
  } else {
    advanced.value = true
  }
}

initRule()
watch(() => props.check.type, () => initRule())


// 判定表达式校验：只做静态检查，不执行用户输入
const assertError = computed(() => {
  const s = (props.check.assertExpr || '').trim()
  if (!s) return '判定表达式不能为空'
  if (/[;{}`]/.test(s)) return '表达式不能包含 ; { } 或反引号'
  let depth = 0
  for (const ch of s) {
    if (ch === '(') depth += 1
    if (ch === ')') depth -= 1
    if (depth < 0) return '括号不配对'
  }
  if (depth !== 0) return '括号不配对'
  return ''
})

function onTypeChange(type) {
  const meta = props.checkTypes.find((t) => t.type === type)
  const params = {}
  ;(meta?.fields || []).forEach((f) => {
    params[f.key] = f.default
  })
  props.check.params = params
  props.check.rule = null
  if (meta?.assertHint) props.check.assertExpr = meta.assertHint
  initRule()
}
</script>

<style scoped lang="scss">
.check-form {
  padding: 12px 14px 16px;
  border-top: 1px solid var(--el-border-color-lighter);

  &.in-drawer {
    padding: 4px 4px 16px;
    border-top: none;
  }
  &.in-drawer :deep(.el-form-item__label) {
    font-size: 13px;
  }
}
.form-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;

  .code {
    flex: 0 0 auto;
    font-family: Consolas, Monaco, monospace;
    font-size: 12px;
    padding: 2px 7px;
    border-radius: 4px;
    background: var(--el-fill-color);
    color: var(--el-text-color-regular);
  }
}
.form-body {
  :deep(.el-form-item) {
    margin-bottom: 10px;
  }
  :deep(.el-form-item__label) {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    padding-bottom: 2px;
    line-height: 18px;
  }
}
.full {
  width: 100%;
}
.fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 10px;
}
.row2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 10px;
}
.mono :deep(textarea) {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}
.hint {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  margin-top: 2px;

  code {
    font-family: Consolas, Monaco, monospace;
    color: var(--el-color-primary);
  }
}
.hint-label {
  margin-right: 4px;
  opacity: 0.7;
}
.err {
  font-size: 11px;
  color: var(--el-color-danger);
  margin-top: 2px;
}
/* ── 判定规则三段式 ── */
.rule-box {
  width: 100%;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px 10px 6px;
  background: var(--el-fill-color-blank);
}
.rule-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;

  .r-metric {
    flex: 1 1 42%;
    min-width: 0;
  }
  .r-op {
    flex: 0 0 30%;
    min-width: 0;
  }
  .r-target {
    flex: 1 1 30%;
    min-width: 0;
  }
  .r-num {
    flex: 0 0 76px;
  }
  .r-del {
    flex: 0 0 auto;
    padding: 0 4px;
    color: var(--el-text-color-secondary);
  }
}
.rule-sentence {
  display: flex;
  gap: 6px;
  align-items: flex-start;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-radius: 4px;
  padding: 6px 8px;
  margin: 2px 0 4px;

  .rule-tag {
    flex: 0 0 auto;
    font-size: 11px;
    color: #fff;
    background: var(--el-color-primary);
    border-radius: 3px;
    padding: 0 5px;
    line-height: 17px;
    margin-top: 1px;
  }
}
.rule-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.rule-adv {
  margin-top: 4px;
  padding-top: 6px;
  border-top: 1px dashed var(--el-border-color-lighter);
}
.switch-row {
  display: flex;
  gap: 16px;
  margin: 4px 0 8px;

  .sw {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: 12px;
    color: var(--el-text-color-regular);
    cursor: pointer;
  }
}
.vars {
  border-top: none;
  :deep(.el-collapse-item__header) {
    height: 30px;
    font-size: 12px;
    border-bottom: none;
    color: var(--el-text-color-secondary);
  }
  :deep(.el-collapse-item__wrap) {
    border-bottom: none;
  }
}
.var-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;

  code {
    font-family: Consolas, Monaco, monospace;
    font-size: 11px;
    padding: 2px 6px;
    border-radius: 3px;
    background: var(--el-fill-color-light);
    color: var(--el-text-color-regular);
  }
}
</style>
