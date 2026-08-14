<template>
  <div class="nms-page log-center">
    <div class="nms-page-header">
      <div class="nms-page-title-block">
        <h1 class="nms-page-title">日志中心</h1>
        <p class="nms-page-subtitle">全站操作留痕 · 配置变更全文 · 可按时间检索</p>
      </div>
    </div>

    <div class="nms-panel log-panel">
      <div class="nms-panel-body log-panel-body">
        <el-tabs v-model="activeTab" class="log-tabs" @tab-change="onTabChange">
          <!-- ========== 操作日志 ========== -->
          <el-tab-pane
            v-if="auth.hasPermission('audit:read')"
            label="操作日志"
            name="ops"
          >
            <div class="log-toolbar">
              <el-radio-group v-model="opsTimePreset" size="small" @change="onOpsTimePreset">
                <el-radio-button label="1h">近1小时</el-radio-button>
                <el-radio-button label="today">今天</el-radio-button>
                <el-radio-button label="7d">近7天</el-radio-button>
                <el-radio-button label="30d">近30天</el-radio-button>
                <el-radio-button label="custom">自定义</el-radio-button>
              </el-radio-group>
              <el-date-picker
                v-if="opsTimePreset === 'custom'"
                v-model="opsDateRange"
                type="datetimerange"
                range-separator="至"
                start-placeholder="开始"
                end-placeholder="结束"
                value-format="YYYY-MM-DDTHH:mm:ss"
                size="small"
                style="width: 340px"
                @change="onOpsFilterChange"
              />
              <el-input
                v-model="opsFilters.keyword"
                clearable
                size="small"
                placeholder="摘要 / 对象 / 操作人"
                style="width: 180px"
                @keyup.enter="onOpsFilterChange"
                @clear="onOpsFilterChange"
              />
              <el-input
                v-model="opsFilters.operator"
                clearable
                size="small"
                placeholder="操作人"
                style="width: 110px"
                @keyup.enter="onOpsFilterChange"
                @clear="onOpsFilterChange"
              />
              <el-select
                v-model="opsFilters.module"
                clearable
                size="small"
                placeholder="模块"
                style="width: 110px"
                @change="onOpsFilterChange"
              >
                <el-option v-for="m in moduleOptions" :key="m.value" :label="m.label" :value="m.value" />
              </el-select>
              <el-select
                v-model="opsFilters.action"
                clearable
                filterable
                size="small"
                placeholder="动作"
                style="width: 140px"
                @change="onOpsFilterChange"
              >
                <el-option v-for="a in actionOptions" :key="a.value" :label="a.label" :value="a.value" />
              </el-select>
              <el-select
                v-model="opsFilters.status"
                clearable
                size="small"
                placeholder="结果"
                style="width: 100px"
                @change="onOpsFilterChange"
              >
                <el-option label="成功" value="success" />
                <el-option label="部分成功" value="partial" />
                <el-option label="失败" value="failed" />
              </el-select>
              <el-button type="primary" size="small" @click="onOpsFilterChange">查询</el-button>
              <el-button size="small" @click="resetOpsFilters">重置</el-button>
              <el-button size="small" :loading="opsLoading" @click="loadOpsLogs">刷新</el-button>
              <el-button size="small" :loading="opsExporting" @click="exportOpsCsv">导出</el-button>
              <label class="log-auto">
                <el-switch v-model="opsAutoRefresh" size="small" @change="onOpsAutoRefreshChange" />
                <span>自动刷新</span>
              </label>
            </div>

            <div class="log-split">
              <div class="log-list">
                <div class="log-table-wrap">
                  <el-table
                    v-loading="opsLoading"
                    :data="opsLogs"
                    size="small"
                    height="100%"
                    highlight-current-row
                    row-key="id"
                    :row-class-name="opsRowClass"
                    @current-change="onOpsRowSelect"
                    @row-click="(row) => onOpsRowSelect(row)"
                  >
                    <el-table-column prop="createdAt" label="时间" width="156">
                      <template #default="{ row }">
                        <span class="log-time">{{ formatDate(row.createdAt) }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column prop="status" label="结果" width="78">
                      <template #default="{ row }">
                        <el-tag :type="statusTag(row.status)" size="small" effect="plain">
                          {{ formatStatus(row.status) }}
                        </el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column prop="module" label="模块" width="78">
                      <template #default="{ row }">{{ formatModule(row.module) }}</template>
                    </el-table-column>
                    <el-table-column prop="action" label="动作" width="120" show-overflow-tooltip>
                      <template #default="{ row }">{{ formatAction(row.action) }}</template>
                    </el-table-column>
                    <el-table-column prop="operator" label="操作人" width="90" show-overflow-tooltip />
                    <el-table-column label="对象" min-width="120" show-overflow-tooltip>
                      <template #default="{ row }">
                        {{ row.targetName || row.targetId || '-' }}
                      </template>
                    </el-table-column>
                    <el-table-column prop="summary" label="摘要" min-width="140" show-overflow-tooltip />
                    <el-table-column prop="clientIp" label="IP" width="110" show-overflow-tooltip />
                  </el-table>
                </div>
                <div class="log-pager">
                  <el-pagination
                    v-model:current-page="opsPage"
                    v-model:page-size="opsPageSize"
                    :total="opsTotal"
                    :page-sizes="[20, 50, 100, 200]"
                    layout="total, sizes, prev, pager, next"
                    small
                    background
                    @current-change="loadOpsLogs"
                    @size-change="onOpsPageSizeChange"
                  />
                </div>
                <el-empty v-if="!opsLoading && !opsLogs.length" description="暂无操作日志" :image-size="64" />
              </div>

              <aside class="log-detail">
                <template v-if="currentOpsLog">
                  <div class="log-detail-hd">
                    <div class="log-detail-title">日志详情</div>
                    <el-tag :type="statusTag(currentOpsLog.status)" size="small">
                      {{ formatStatus(currentOpsLog.status) }}
                    </el-tag>
                  </div>
                  <div class="log-detail-meta">{{ formatDate(currentOpsLog.createdAt) }}</div>
                  <dl class="log-kv">
                    <div><dt>操作人</dt><dd>{{ currentOpsLog.operator || '-' }}</dd></div>
                    <div><dt>模块</dt><dd>{{ formatModule(currentOpsLog.module) }}</dd></div>
                    <div><dt>动作</dt><dd>{{ formatAction(currentOpsLog.action) }}</dd></div>
                    <div><dt>对象类型</dt><dd>{{ currentOpsLog.targetType || '-' }}</dd></div>
                    <div><dt>对象</dt><dd>{{ currentOpsLog.targetName || currentOpsLog.targetId || '-' }}</dd></div>
                    <div><dt>客户端 IP</dt><dd>{{ currentOpsLog.clientIp || '-' }}</dd></div>
                    <div v-if="currentOpsLog.refType">
                      <dt>关联</dt>
                      <dd>{{ currentOpsLog.refType }} #{{ currentOpsLog.refId }}</dd>
                    </div>
                  </dl>
                  <div class="log-section-label">摘要</div>
                  <p class="log-summary">{{ currentOpsLog.summary || '-' }}</p>
                  <div class="log-section-label">详情</div>
                  <div class="log-detail-body">
                    <p v-if="opsDetailHint" class="log-detail-hint">{{ opsDetailHint }}</p>
                    <ul v-if="opsDetailIdList.length" class="log-id-list">
                      <li v-for="id in opsDetailIdList" :key="id">
                        <span class="log-id-chip">告警 #{{ id }}</span>
                      </li>
                    </ul>
                    <pre v-else class="detail-pre">{{ formatOpsDetailText(currentOpsLog) }}</pre>
                    <el-button
                      v-if="opsDetailIdList.length && auth.hasPermission('alarms:read')"
                      size="small"
                      link
                      type="primary"
                      class="log-jump"
                      @click="goAlarmsFromDetail"
                    >打开告警管理对照</el-button>
                  </div>
                  <el-button
                    v-if="canJumpChangeLog(currentOpsLog) && auth.hasPermission('configs:read')"
                    type="primary"
                    size="small"
                    plain
                    class="log-jump"
                    @click="jumpChangeLog(currentOpsLog)"
                  >
                    查看配置变更记录
                  </el-button>
                </template>
                <div v-else class="log-detail-empty">选中左侧一条日志查看详情</div>
              </aside>
            </div>
          </el-tab-pane>

          <!-- ========== 配置变更 ========== -->
          <el-tab-pane
            v-if="auth.hasPermission('configs:read')"
            label="配置变更"
            name="history"
          >
            <div class="log-toolbar">
              <el-radio-group v-model="changeTimePreset" size="small" @change="onChangeTimePreset">
                <el-radio-button label="1h">近1小时</el-radio-button>
                <el-radio-button label="today">今天</el-radio-button>
                <el-radio-button label="7d">近7天</el-radio-button>
                <el-radio-button label="30d">近30天</el-radio-button>
                <el-radio-button label="custom">自定义</el-radio-button>
              </el-radio-group>
              <el-date-picker
                v-if="changeTimePreset === 'custom'"
                v-model="changeDateRange"
                type="datetimerange"
                range-separator="至"
                start-placeholder="开始"
                end-placeholder="结束"
                value-format="YYYY-MM-DDTHH:mm:ss"
                size="small"
                style="width: 340px"
                @change="onChangeFilterChange"
              />
              <el-select
                v-model="changeFilter.deviceId"
                clearable
                filterable
                size="small"
                placeholder="设备"
                style="width: 200px"
                @change="onChangeFilterChange"
              >
                <el-option
                  v-for="d in devices"
                  :key="d.id"
                  :label="`${d.name} (${d.ipAddress})`"
                  :value="d.id"
                />
              </el-select>
              <el-select
                v-model="changeFilter.changeType"
                clearable
                size="small"
                placeholder="类型"
                style="width: 100px"
                @change="onChangeFilterChange"
              >
                <el-option label="备份" value="backup" />
                <el-option label="恢复" value="restore" />
                <el-option label="应用" value="apply" />
                <el-option label="批量" value="batch" />
              </el-select>
              <el-select
                v-model="changeFilter.status"
                clearable
                size="small"
                placeholder="状态"
                style="width: 100px"
                @change="onChangeFilterChange"
              >
                <el-option label="成功" value="success" />
                <el-option label="部分成功" value="partial" />
                <el-option label="失败" value="failed" />
              </el-select>
              <el-input
                v-model="changeFilter.keyword"
                clearable
                size="small"
                placeholder="操作人 / 原因"
                style="width: 150px"
                @keyup.enter="onChangeFilterChange"
                @clear="onChangeFilterChange"
              />
              <el-button type="primary" size="small" @click="onChangeFilterChange">查询</el-button>
              <el-button size="small" @click="resetChangeFilters">重置</el-button>
              <el-button size="small" :loading="changeLoading" @click="loadChangeLogs">刷新</el-button>
              <el-button size="small" :loading="changeExporting" @click="exportChangeCsv">导出</el-button>
            </div>

            <div class="log-split">
              <div class="log-list">
                <div class="log-table-wrap">
                  <el-table
                    v-loading="changeLoading"
                    :data="changeLogs"
                    size="small"
                    height="100%"
                    highlight-current-row
                    row-key="id"
                    :row-class-name="changeRowClass"
                    @current-change="onChangeRowSelect"
                    @row-click="(row) => onChangeRowSelect(row)"
                  >
                    <el-table-column prop="createdAt" label="时间" width="156">
                      <template #default="{ row }">
                        <span class="log-time">{{ formatDate(row.createdAt) }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column prop="status" label="状态" width="78">
                      <template #default="{ row }">
                        <el-tag :type="statusTag(row.status)" size="small" effect="plain">
                          {{ formatStatus(row.status) }}
                        </el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column prop="changeType" label="类型" width="72">
                      <template #default="{ row }">
                        <el-tag :type="getChangeTypeTag(row.changeType)" size="small" effect="plain">
                          {{ formatChangeType(row.changeType) }}
                        </el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column prop="deviceName" label="设备" min-width="140" show-overflow-tooltip />
                    <el-table-column prop="operator" label="操作人" width="90" show-overflow-tooltip />
                    <el-table-column prop="reason" label="原因" min-width="160" show-overflow-tooltip />
                  </el-table>
                </div>
                <div class="log-pager">
                  <el-pagination
                    v-model:current-page="changePage"
                    v-model:page-size="changePageSize"
                    :total="changeTotal"
                    :page-sizes="[20, 50, 100, 200]"
                    layout="total, sizes, prev, pager, next"
                    small
                    background
                    @current-change="loadChangeLogs"
                    @size-change="onChangePageSizeChange"
                  />
                </div>
                <el-empty v-if="!changeLoading && !changeLogs.length" description="暂无变更记录" :image-size="64" />
              </div>

              <aside class="log-detail">
                <template v-if="currentChangeLog">
                  <div class="log-detail-hd">
                    <div class="log-detail-title">变更详情</div>
                    <el-tag :type="statusTag(currentChangeLog.status)" size="small">
                      {{ formatStatus(currentChangeLog.status) }}
                    </el-tag>
                  </div>
                  <div class="log-detail-meta">{{ formatDate(currentChangeLog.createdAt) }}</div>
                  <dl class="log-kv">
                    <div><dt>设备</dt><dd>{{ currentChangeLog.deviceName || '-' }}</dd></div>
                    <div><dt>类型</dt><dd>{{ formatChangeType(currentChangeLog.changeType) }}</dd></div>
                    <div><dt>操作人</dt><dd>{{ currentChangeLog.operator || '-' }}</dd></div>
                    <div><dt>原因</dt><dd>{{ currentChangeLog.reason || '-' }}</dd></div>
                  </dl>
                  <div class="log-section-label">执行命令</div>
                  <pre class="detail-pre tall">{{ currentChangeLog.commands || '（无）' }}</pre>
                  <div class="log-section-label">执行结果</div>
                  <pre class="detail-pre tall">{{ currentChangeLog.result || '（无）' }}</pre>
                </template>
                <div v-else class="log-detail-empty">选中左侧一条记录查看命令与结果</div>
              </aside>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { auditLogApi } from '@/api/audit'
import { deviceApi, configChangeLogApi } from '@/api/device'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeTab = ref('ops')

const opsLoading = ref(false)
const opsExporting = ref(false)
const opsLogs = ref([])
const opsPage = ref(1)
const opsPageSize = ref(50)
const opsTotal = ref(0)
const currentOpsLog = ref(null)
const opsTimePreset = ref('7d')
const opsDateRange = ref(null)
const opsAutoRefresh = ref(false)
let opsAutoTimer = null
const opsFilters = ref({
  keyword: '',
  operator: '',
  module: '',
  action: '',
  status: ''
})

const changeLoading = ref(false)
const changeExporting = ref(false)
const changeLogs = ref([])
const changePage = ref(1)
const changePageSize = ref(50)
const changeTotal = ref(0)
const currentChangeLog = ref(null)
const changeTimePreset = ref('7d')
const changeDateRange = ref(null)
const changeFilter = ref({
  deviceId: null,
  changeType: '',
  status: '',
  keyword: ''
})
const devices = ref([])

const moduleOptions = [
  { value: 'auth', label: '认证' },
  { value: 'device', label: '设备' },
  { value: 'config', label: '配置' },
  { value: 'alarm', label: '告警' },
  { value: 'aiops', label: '智能运维' },
  { value: 'user', label: '用户' },
  { value: 'role', label: '角色' }
]

const actionOptions = [
  { value: 'login', label: '登录' },
  { value: 'logout', label: '登出' },
  { value: 'create', label: '新增' },
  { value: 'update', label: '编辑' },
  { value: 'delete', label: '删除' },
  { value: 'update_password', label: '重置密码' },
  { value: 'unlock', label: '解锁' },
  { value: 'backup', label: '备份' },
  { value: 'restore', label: '恢复' },
  { value: 'batch_apply', label: '批量下发' },
  { value: 'batch_backup', label: '批量备份' },
  { value: 'apply', label: '应用模板' },
  { value: 'ack', label: '确认告警' },
  { value: 'clear', label: '清除告警' },
  { value: 'ack_close', label: '阅知关闭' },
  { value: 'batch_ack', label: '批量确认' },
  { value: 'batch_clear', label: '批量清除' },
  { value: 'batch_delete', label: '批量删除' },
  { value: 'llm_unattended_cycle', label: '无人值守轮次' },
  { value: 'llm_unattended_pause', label: '无人值守暂停' },
  { value: 'llm_unattended_resume', label: '无人值守恢复' },
  { value: 'llm_tool_inspect', label: '智能·重算关联' },
  { value: 'llm_tool_get_device_summary', label: '智能·设备摘要' },
  { value: 'llm_tool_dispose_incident', label: '智能·标准处置' },
  { value: 'llm_tool_backup', label: '智能·备份' },
  { value: 'llm_tool_restore_latest', label: '智能·回滚' },
  { value: 'llm_tool_refresh_device', label: '智能·刷新设备' }
]

const moduleLabels = {
  auth: '认证',
  device: '设备',
  config: '配置',
  alarm: '告警',
  aiops: '智能运维',
  user: '用户',
  role: '角色'
}

const actionLabels = {
  login: '登录',
  logout: '登出',
  create: '新增',
  update: '编辑',
  delete: '删除',
  refresh: '刷新',
  discover: '发现扫描',
  discover_scan: '异步扫描',
  discover_import: '发现入库',
  backup: '备份',
  restore: '恢复',
  batch_apply: '批量下发',
  batch_backup: '批量备份',
  apply: '应用模板',
  schedule_create: '创建计划',
  schedule_update: '更新计划',
  schedule_delete: '删除计划',
  schedule_execute: '执行计划',
  ack: '确认告警',
  clear: '清除告警',
  ack_close: '阅知关闭',
  batch_ack: '批量确认',
  batch_clear: '批量清除',
  batch_delete: '批量删除',
  update_password: '重置密码',
  llm_unattended_cycle: '无人值守轮次',
  llm_unattended_pause: '无人值守暂停',
  llm_unattended_resume: '无人值守恢复',
  llm_tool_inspect: '智能·重算关联',
  llm_tool_get_device_summary: '智能·设备摘要',
  llm_tool_get_topo_neighbors: '智能·拓扑邻居',
  llm_tool_get_perf_snapshot: '智能·性能快照',
  llm_tool_get_config_diff_summary: '智能·配置差异',
  llm_tool_list_active_alarms_for_device: '智能·活动告警',
  llm_tool_run_path_hint: '智能·路径提示',
  llm_tool_dispose_incident: '智能·标准处置',
  llm_tool_ack_noise: '智能·确认噪音',
  llm_tool_ack_alarm: '智能·确认告警',
  llm_tool_backup: '智能·备份',
  llm_tool_restore_latest: '智能·回滚',
  llm_tool_refresh_device: '智能·刷新设备',
  llm_tool_refresh_offline: '智能·批量刷新离线',
  llm_tool_pull_live_config: '智能·拉取配置'
}

function pad2(n) {
  return String(n).padStart(2, '0')
}

function toLocalIso(d) {
  if (!(d instanceof Date) || Number.isNaN(d.getTime())) return undefined
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}T${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
}

function resolveTimeRange(preset, customRange) {
  const now = new Date()
  if (preset === 'custom') {
    if (Array.isArray(customRange) && customRange.length === 2 && customRange[0] && customRange[1]) {
      return { from: customRange[0], to: customRange[1] }
    }
    return { from: undefined, to: undefined }
  }
  if (preset === '1h') {
    return { from: toLocalIso(new Date(now.getTime() - 3600000)), to: toLocalIso(now) }
  }
  if (preset === 'today') {
    const start = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 0, 0, 0)
    return { from: toLocalIso(start), to: toLocalIso(now) }
  }
  if (preset === '30d') {
    return { from: toLocalIso(new Date(now.getTime() - 30 * 86400000)), to: toLocalIso(now) }
  }
  // default 7d
  return { from: toLocalIso(new Date(now.getTime() - 7 * 86400000)), to: toLocalIso(now) }
}

function formatDate(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

function formatModule(m) {
  return moduleLabels[m] || m || '-'
}

function formatAction(a) {
  if (!a) return '-'
  if (actionLabels[a]) return actionLabels[a]
  if (String(a).startsWith('llm_tool_')) return '智能操作·' + String(a).replace(/^llm_tool_/, '')
  return a
}

function formatStatus(s) {
  const map = { success: '成功', failed: '失败', partial: '部分成功' }
  return map[s] || s || '-'
}

/** 解析历史审计里裸写的 [1,2,3] 告警 ID 列表 */
function parseAlarmIdList(detail) {
  if (detail == null) return []
  const s = String(detail).trim()
  if (!/^\[\s*\d+(\s*,\s*\d+)*\s*\]$/.test(s)) return []
  return s
    .slice(1, -1)
    .split(',')
    .map((x) => x.trim())
    .filter(Boolean)
}

const opsDetailIdList = computed(() => parseAlarmIdList(currentOpsLog.value?.detail))

const opsDetailHint = computed(() => {
  const row = currentOpsLog.value
  if (!row) return ''
  if (opsDetailIdList.value.length) {
    return `下列数字是本次操作涉及的告警 ID（共 ${opsDetailIdList.value.length} 条），不是随机编号。可到「告警管理」按记录对照；新产生的批量操作会写入标题与设备名。`
  }
  if (row.module === 'alarm' && row.targetType === 'alarm' && row.targetId && !row.detail) {
    return `对象「告警 #${row.targetId}」即告警编号。`
  }
  return ''
})

function formatOpsDetailText(row) {
  if (!row) return '（无详情正文）'
  if (row.detail) return row.detail
  if (row.targetType === 'alarm' && row.targetId) {
    return `告警 ID：#${row.targetId}${row.targetName ? `\n标题：${row.targetName}` : ''}`
  }
  return '（无详情正文）'
}

function goAlarmsFromDetail() {
  router.push({ path: '/alarms' })
}

function statusTag(s) {
  if (s === 'success') return 'success'
  if (s === 'failed') return 'danger'
  if (s === 'partial') return 'warning'
  return 'info'
}

function formatChangeType(t) {
  const map = { backup: '备份', restore: '恢复', apply: '应用', batch: '批量' }
  return map[t] || t || '-'
}

function getChangeTypeTag(type) {
  const map = { backup: '', restore: 'success', apply: 'warning', batch: 'info' }
  return map[type] || 'info'
}

function opsRowClass({ row }) {
  if (row?.status === 'failed') return 'log-row-failed'
  if (row?.status === 'partial') return 'log-row-partial'
  if (currentOpsLog.value && String(currentOpsLog.value.id) === String(row.id)) return 'log-row-active'
  return ''
}

function changeRowClass({ row }) {
  if (row?.status === 'failed') return 'log-row-failed'
  if (row?.status === 'partial') return 'log-row-partial'
  if (currentChangeLog.value && String(currentChangeLog.value.id) === String(row.id)) return 'log-row-active'
  return ''
}

function onOpsRowSelect(row) {
  if (row) currentOpsLog.value = row
}

function onChangeRowSelect(row) {
  if (row) currentChangeLog.value = row
}

function canJumpChangeLog(row) {
  if (!row) return false
  if (row.refType === 'config_change_log' && row.refId) return true
  return row.module === 'config'
}

function jumpChangeLog(row) {
  if (!row) return
  activeTab.value = 'history'
  if (row.refType === 'config_change_log' && row.refId) {
    openChangeById(row.refId)
    syncQuery()
    return
  }
  if (row.targetType === 'device' && row.targetId) {
    changeFilter.value.deviceId = Number(row.targetId) || null
  }
  onChangeFilterChange()
  syncQuery()
}

async function openChangeById(id) {
  try {
    const log = await configChangeLogApi.getLogById(id)
    if (log) {
      activeTab.value = 'history'
      currentChangeLog.value = log
      // 尽量让列表也包含该行
      if (!changeLogs.value.some((x) => String(x.id) === String(log.id))) {
        changeLogs.value = [log, ...changeLogs.value]
      }
    }
  } catch {
    ElMessage.error('加载变更详情失败')
  }
}

function buildOpsQuery(page, size) {
  const range = resolveTimeRange(opsTimePreset.value, opsDateRange.value)
  return {
    keyword: opsFilters.value.keyword || undefined,
    operator: opsFilters.value.operator || undefined,
    module: opsFilters.value.module || undefined,
    action: opsFilters.value.action || undefined,
    status: opsFilters.value.status || undefined,
    from: range.from,
    to: range.to,
    page,
    size
  }
}

function buildChangeQuery(page, size) {
  const range = resolveTimeRange(changeTimePreset.value, changeDateRange.value)
  return {
    deviceId: changeFilter.value.deviceId || undefined,
    changeType: changeFilter.value.changeType || undefined,
    status: changeFilter.value.status || undefined,
    keyword: changeFilter.value.keyword || undefined,
    from: range.from,
    to: range.to,
    page,
    size
  }
}

async function loadOpsLogs() {
  if (!auth.hasPermission('audit:read')) return
  opsLoading.value = true
  try {
    const res = await auditLogApi.queryLogs(buildOpsQuery(opsPage.value - 1, opsPageSize.value))
    opsLogs.value = Array.isArray(res?.content) ? res.content : []
    opsTotal.value = res?.totalElements != null ? Number(res.totalElements) : opsLogs.value.length
    if (opsLogs.value.length) {
      const still = currentOpsLog.value
        && opsLogs.value.some((x) => String(x.id) === String(currentOpsLog.value.id))
      if (!still) currentOpsLog.value = opsLogs.value[0]
    } else {
      currentOpsLog.value = null
    }
  } catch (error) {
    const status = error?.response?.status
    if (status === 403) {
      ElMessage.error('无权限查看操作日志（需 audit:read）')
    } else {
      ElMessage.error(error?.response?.data?.message || '加载操作日志失败')
    }
  } finally {
    opsLoading.value = false
  }
}

async function loadChangeLogs() {
  if (!auth.hasPermission('configs:read')) return
  changeLoading.value = true
  try {
    const res = await configChangeLogApi.queryLogs(
      buildChangeQuery(changePage.value - 1, changePageSize.value)
    )
    changeLogs.value = Array.isArray(res?.content) ? res.content : (Array.isArray(res) ? res : [])
    changeTotal.value = res?.totalElements != null ? Number(res.totalElements) : changeLogs.value.length
    if (changeLogs.value.length) {
      const still = currentChangeLog.value
        && changeLogs.value.some((x) => String(x.id) === String(currentChangeLog.value.id))
      if (!still) currentChangeLog.value = changeLogs.value[0]
    } else {
      currentChangeLog.value = null
    }
  } catch {
    ElMessage.error('加载变更记录失败')
  } finally {
    changeLoading.value = false
  }
}

async function loadDevices() {
  if (!auth.hasPermission('configs:read') && !auth.hasPermission('devices:read')) return
  try {
    const res = await deviceApi.getDevices()
    devices.value = Array.isArray(res) ? res : (res?.data || [])
  } catch {
    // ignore
  }
}

async function fetchAllPages(fetcher, pageSize = 500, maxRows = 2000) {
  const all = []
  let page = 0
  while (all.length < maxRows) {
    const res = await fetcher(page, pageSize)
    const chunk = Array.isArray(res?.content) ? res.content : (Array.isArray(res) ? res : [])
    all.push(...chunk)
    const total = res?.totalElements != null ? Number(res.totalElements) : all.length
    if (!chunk.length || all.length >= total || chunk.length < pageSize) break
    page += 1
  }
  return all.slice(0, maxRows)
}

function downloadCsv(filename, header, rows) {
  const escape = (v) => {
    const s = v == null ? '' : String(v)
    if (/[",\n\r]/.test(s)) return `"${s.replace(/"/g, '""')}"`
    return s
  }
  const lines = [header.map(escape).join(',')]
  rows.forEach((r) => lines.push(r.map(escape).join(',')))
  const blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

async function exportOpsCsv() {
  if (!auth.hasPermission('audit:read')) return
  opsExporting.value = true
  try {
    const rows = await fetchAllPages(
      (page, size) => auditLogApi.queryLogs(buildOpsQuery(page, size)),
      500,
      2000
    )
    downloadCsv(
      `操作日志_${formatDate(new Date().toISOString()).replace(/[: ]/g, '-')}.csv`,
      ['时间', '操作人', '模块', '动作', '对象', '结果', '摘要', 'IP', '详情'],
      rows.map((r) => [
        formatDate(r.createdAt),
        r.operator,
        formatModule(r.module),
        formatAction(r.action),
        r.targetName || r.targetId || '',
        formatStatus(r.status),
        r.summary || '',
        r.clientIp || '',
        (r.detail || '').slice(0, 500)
      ])
    )
    ElMessage.success(`已导出 ${rows.length} 条`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '导出失败')
  } finally {
    opsExporting.value = false
  }
}

async function exportChangeCsv() {
  if (!auth.hasPermission('configs:read')) return
  changeExporting.value = true
  try {
    const rows = await fetchAllPages(
      (page, size) => configChangeLogApi.queryLogs(buildChangeQuery(page, size)),
      500,
      2000
    )
    downloadCsv(
      `配置变更_${formatDate(new Date().toISOString()).replace(/[: ]/g, '-')}.csv`,
      ['时间', '设备', '类型', '操作人', '状态', '原因', '命令', '结果'],
      rows.map((r) => [
        formatDate(r.createdAt),
        r.deviceName || '',
        formatChangeType(r.changeType),
        r.operator || '',
        formatStatus(r.status),
        r.reason || '',
        (r.commands || '').slice(0, 500),
        (r.result || '').slice(0, 500)
      ])
    )
    ElMessage.success(`已导出 ${rows.length} 条`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '导出失败')
  } finally {
    changeExporting.value = false
  }
}

function onOpsTimePreset() {
  if (opsTimePreset.value !== 'custom') opsDateRange.value = null
  onOpsFilterChange()
}

function onChangeTimePreset() {
  if (changeTimePreset.value !== 'custom') changeDateRange.value = null
  onChangeFilterChange()
}

function onOpsFilterChange() {
  opsPage.value = 1
  loadOpsLogs()
}

function onOpsPageSizeChange() {
  opsPage.value = 1
  loadOpsLogs()
}

function resetOpsFilters() {
  opsFilters.value = { keyword: '', operator: '', module: '', action: '', status: '' }
  opsTimePreset.value = '7d'
  opsDateRange.value = null
  onOpsFilterChange()
}

function onChangeFilterChange() {
  changePage.value = 1
  loadChangeLogs()
  syncQuery()
}

function onChangePageSizeChange() {
  changePage.value = 1
  loadChangeLogs()
}

function resetChangeFilters() {
  changeFilter.value = { deviceId: null, changeType: '', status: '', keyword: '' }
  changeTimePreset.value = '7d'
  changeDateRange.value = null
  onChangeFilterChange()
}

function stopOpsAutoRefresh() {
  if (opsAutoTimer) {
    clearInterval(opsAutoTimer)
    opsAutoTimer = null
  }
}

function onOpsAutoRefreshChange(on) {
  stopOpsAutoRefresh()
  if (on) {
    opsAutoTimer = setInterval(() => {
      if (document.hidden) return
      if (activeTab.value !== 'ops') return
      loadOpsLogs()
    }, 30000)
    ElMessage.success('已开启操作日志自动刷新（30 秒）')
  }
}

function onTabChange() {
  syncQuery()
  if (activeTab.value === 'ops') loadOpsLogs()
  if (activeTab.value === 'history') loadChangeLogs()
}

function syncQuery() {
  const query = { tab: activeTab.value }
  if (activeTab.value === 'history' && changeFilter.value.deviceId) {
    query.deviceId = String(changeFilter.value.deviceId)
  }
  router.replace({ path: '/audit', query }).catch(() => {})
}

function resolveDefaultTab() {
  const q = route.query
  if (q.tab === 'history' && auth.hasPermission('configs:read')) return 'history'
  if (q.tab === 'ops' && auth.hasPermission('audit:read')) return 'ops'
  if (auth.hasPermission('audit:read')) return 'ops'
  if (auth.hasPermission('configs:read')) return 'history'
  return ''
}

async function applyRouteQuery() {
  const q = route.query
  if (q.module && typeof q.module === 'string' && auth.hasPermission('audit:read')) {
    opsFilters.value.module = q.module
    if (q.tab !== 'history') activeTab.value = 'ops'
  }
  if (q.deviceId && auth.hasPermission('configs:read')) {
    changeFilter.value.deviceId = Number(q.deviceId) || null
  }
  if (q.changeLogId && auth.hasPermission('configs:read')) {
    activeTab.value = 'history'
    await openChangeById(q.changeLogId)
  }
}

onMounted(async () => {
  try {
    await auth.fetchMe()
  } catch {
    // ignore
  }
  if (!auth.hasAnyPermission('audit:read', 'configs:read')) {
    ElMessage.error('无权限访问日志中心')
    router.replace('/')
    return
  }
  activeTab.value = resolveDefaultTab()
  await loadDevices()
  await applyRouteQuery()
  if (activeTab.value === 'ops') await loadOpsLogs()
  if (activeTab.value === 'history') await loadChangeLogs()
})

onUnmounted(() => {
  stopOpsAutoRefresh()
})

watch(
  () => route.query,
  async () => {
    const next = resolveDefaultTab()
    if (next && next !== activeTab.value) {
      activeTab.value = next
    }
    await applyRouteQuery()
  }
)
</script>

<style scoped>
.log-panel {
  display: flex;
  flex-direction: column;
  min-height: calc(100vh - 140px);
}
.log-panel-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding-bottom: 8px;
}
.log-tabs {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.log-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.log-tabs :deep(.el-tab-pane) {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.log-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
  flex-shrink: 0;
}
.log-auto {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: 4px;
  cursor: pointer;
  user-select: none;
}

.log-split {
  flex: 1;
  min-height: 420px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 380px);
  gap: 12px;
  min-height: 0;
}
.log-list {
  min-width: 0;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;
  background: var(--el-bg-color);
}
.log-table-wrap {
  flex: 1;
  min-height: 0;
  position: relative;
}
.log-list :deep(.el-table) {
  position: absolute;
  inset: 0;
}
.log-list :deep(.el-table .cell) {
  line-height: 1.35;
}
.log-list :deep(.el-table__row) {
  cursor: pointer;
}
.log-list :deep(.log-row-failed td:first-child) {
  box-shadow: inset 3px 0 0 var(--el-color-danger);
}
.log-list :deep(.log-row-partial td:first-child) {
  box-shadow: inset 3px 0 0 var(--el-color-warning);
}
.log-pager {
  flex-shrink: 0;
  padding: 8px 10px;
  border-top: 1px solid var(--el-border-color-lighter);
  display: flex;
  justify-content: flex-end;
}
.log-time {
  font-variant-numeric: tabular-nums;
  font-size: 12px;
}

.log-detail {
  min-width: 0;
  min-height: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-blank, #fff);
  padding: 12px 14px;
  overflow: auto;
  display: flex;
  flex-direction: column;
}
.log-detail-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.log-detail-title {
  font-weight: 600;
  font-size: 14px;
}
.log-detail-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.log-kv {
  margin: 12px 0 0;
  display: grid;
  gap: 6px;
}
.log-kv > div {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 8px;
  font-size: 12px;
  line-height: 1.45;
}
.log-kv dt {
  margin: 0;
  color: var(--el-text-color-secondary);
}
.log-kv dd {
  margin: 0;
  word-break: break-word;
}
.log-section-label {
  margin-top: 12px;
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: 600;
}
.log-summary {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
}
.log-detail-hint {
  margin: 0 0 8px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: 6px;
  padding: 8px 10px;
}
.log-id-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 220px;
  overflow: auto;
}
.log-id-chip {
  display: inline-block;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  border: 1px solid var(--el-color-primary-light-5);
}
.detail-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.45;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  max-height: 220px;
  overflow: auto;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-extra-light);
}
.detail-pre.tall {
  max-height: 260px;
}
.log-jump {
  margin-top: 12px;
  align-self: flex-start;
}
.log-detail-empty {
  margin: auto;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  text-align: center;
  padding: 24px;
}

@media (max-width: 1100px) {
  .log-split {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(280px, 48vh) minmax(240px, auto);
  }
}
</style>
