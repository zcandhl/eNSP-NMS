<template>
  <el-drawer
    :model-value="visible"
    size="560px"
    destroy-on-close
    class="device-detail-drawer"
    @close="emit('update:visible', false)"
  >
    <template #header>
      <div class="drawer-head">
        <div>
          <div class="drawer-title">{{ device?.name || '设备详情' }}</div>
          <div class="drawer-sub">{{ device?.ipAddress || '-' }}</div>
        </div>
        <el-tag v-if="device" :type="device.status === 'online' ? 'success' : 'danger'" size="small">
          {{ device.status === 'online' ? '在线' : '离线' }}
        </el-tag>
      </div>
    </template>

    <div v-if="device" v-loading="loading">
      <div class="local-ops">
        <el-button
          v-if="auth.hasPermission('devices:write')"
          size="small"
          :loading="testing"
          @click="runConnectivityTest"
        >连通性诊断</el-button>
        <el-button v-if="auth.hasPermission('devices:write')" size="small" @click="emit('refresh', device)">刷新状态</el-button>
        <el-button
          v-if="auth.hasPermission('webssh:connect') && canUseWebSsh(device)"
          size="small"
          type="primary"
          @click="emit('terminal', device)"
        >Web终端</el-button>
        <el-button v-if="auth.hasPermission('devices:write')" size="small" @click="emit('edit', device)">编辑</el-button>
      </div>

      <div v-if="relatedLinks.length" class="related-mods">
        <span class="related-label">相关模块</span>
        <button
          v-for="link in relatedLinks"
          :key="link.key"
          type="button"
          class="related-link"
          @click="openRelated(link)"
        >{{ link.label }}</button>
        <span class="related-hint">新标签打开，本页不离开</span>
      </div>

      <div v-if="connResult" class="conn-card">
        <div class="conn-title">
          诊断结果
          <el-tag size="small" :type="connSummaryType">{{ connResult.summary || '-' }}</el-tag>
        </div>
        <div class="conn-grid">
          <div v-for="item in connItems" :key="item.key" class="conn-item">
            <div class="conn-label">{{ item.label }}</div>
            <el-tag size="small" :type="item.type">{{ item.text }}</el-tag>
            <div v-if="item.latency != null" class="conn-meta">{{ item.latency }} ms</div>
            <div v-if="item.reason" class="conn-meta">{{ item.reason }}</div>
          </div>
        </div>
      </div>

      <el-tabs v-model="tab">
        <el-tab-pane label="概览" name="overview">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="类型">{{ deviceTypeLabel(device.deviceType) }}</el-descriptions-item>
            <el-descriptions-item label="型号">{{ device.model || '-' }}</el-descriptions-item>
            <el-descriptions-item label="厂商">{{ device.vendor || '-' }}</el-descriptions-item>
            <el-descriptions-item label="序列号">{{ device.serialNumber || '-' }}</el-descriptions-item>
            <el-descriptions-item label="位置">{{ device.location || '-' }}</el-descriptions-item>
            <el-descriptions-item label="联系人">{{ device.contact || '-' }}</el-descriptions-item>
            <el-descriptions-item label="监控方式">{{ monitorLabel(device.monitorMode) }}</el-descriptions-item>
            <el-descriptions-item label="最近探测">{{ probeLabel(device.lastProbeMethod) || '-' }}</el-descriptions-item>
            <el-descriptions-item label="最后在线">
              {{ device.lastSeen ? new Date(device.lastSeen).toLocaleString() : '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">
              {{ device.createdAt ? new Date(device.createdAt).toLocaleString() : '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">
              {{ device.updatedAt ? new Date(device.updatedAt).toLocaleString() : '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="SNMP">
              {{ device.snmpVersion || '-' }} / {{ device.snmpCommunity || '-' }} : {{ device.snmpPort || 161 }}
            </el-descriptions-item>
            <el-descriptions-item label="SSH">
              {{ device.sshUsername ? `${device.sshUsername}@${device.sshPort || 22}` : '未配置' }}
            </el-descriptions-item>
            <el-descriptions-item label="描述">{{ device.description || '-' }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>

        <el-tab-pane v-if="auth.hasPermission('performance:read')" label="接口" name="ports">
          <div class="pane-actions">
            <el-button size="small" :loading="loadingPorts" @click="loadPorts(device.id)">刷新接口</el-button>
          </div>
          <el-table :data="ports" size="small" max-height="360" stripe>
            <el-table-column prop="name" label="接口" min-width="120" show-overflow-tooltip />
            <el-table-column prop="operStatus" label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="String(row.operStatus).toLowerCase() === 'up' ? 'success' : 'info'">
                  {{ row.operStatus || '-' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="index" label="索引" width="70" />
          </el-table>
          <el-empty v-if="!ports.length && !loadingPorts" description="暂无端口数据（需 SNMP 性能采集）" :image-size="56" />
        </el-tab-pane>

        <el-tab-pane v-if="auth.hasPermission('alarms:read')" label="告警" name="alarms">
          <div class="pane-actions">
            <el-button size="small" :loading="loadingAlarms" @click="loadAlarms(device.id)">刷新告警</el-button>
          </div>
          <el-table :data="alarms" size="small" max-height="360" stripe>
            <el-table-column prop="severity" label="级别" width="90" />
            <el-table-column prop="status" label="状态" width="90" />
            <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip />
          </el-table>
          <el-empty v-if="!alarms.length && !loadingAlarms" description="无未清除告警" :image-size="56" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { deviceApi, alarmApi } from '@/api/device'
import { useAuthStore } from '@/stores/auth'
import {
  deviceTypeLabel,
  canUseWebSsh,
  canCollectPerformance,
  canConfigBackup,
  MONITOR_MODE_OPTIONS
} from '@/utils/deviceCapabilities'

const props = defineProps({
  visible: { type: Boolean, default: false },
  device: { type: Object, default: null }
})

const emit = defineEmits(['update:visible', 'refresh', 'edit', 'terminal'])

const router = useRouter()
const auth = useAuthStore()
const tab = ref('overview')
const loading = ref(false)
const testing = ref(false)
const ports = ref([])
const alarms = ref([])
const loadingPorts = ref(false)
const loadingAlarms = ref(false)
const connResult = ref(null)

const relatedLinks = computed(() => {
  const d = props.device
  if (!d?.id) return []
  const id = String(d.id)
  const links = []
  if (auth.hasPermission('topology:read')) {
    links.push({
      key: 'topology',
      label: '拓扑定位',
      route: { name: 'Topology', query: { deviceId: id, tab: 'monitor' } }
    })
  }
  if (auth.hasPermission('alarms:read')) {
    links.push({
      key: 'alarms',
      label: '告警中心',
      route: { path: '/alarms', query: { deviceId: id } }
    })
  }
  if (auth.hasPermission('performance:read') && canCollectPerformance(d)) {
    links.push({
      key: 'performance',
      label: '性能监控',
      route: { name: 'Performance', query: { deviceId: id } }
    })
  }
  if (auth.hasPermission('configs:read') && canConfigBackup(d)) {
    links.push({
      key: 'configs',
      label: '配置管理',
      route: { path: '/configs', query: { deviceId: id } }
    })
  }
  return links
})

const openRelated = (link) => {
  const resolved = router.resolve(link.route)
  window.open(resolved.href, '_blank', 'noopener')
}

const probeLabel = (method) => {
  const m = String(method || '').toLowerCase()
  if (m === 'snmp') return 'SNMP'
  if (m === 'icmp' || m === 'ping') return 'Ping'
  return method || ''
}

const monitorLabel = (mode) =>
  MONITOR_MODE_OPTIONS.find((o) => o.value === mode)?.label || mode || '自动'

const formatConn = (block) => {
  if (!block) return { text: '-', type: 'info' }
  if (block.skipped) return { text: '跳过', type: 'info', reason: block.reason, latency: null }
  if (block.ok === true) return { text: '通过', type: 'success', latency: block.latencyMs, reason: null }
  if (block.ok === false) return { text: '失败', type: 'danger', latency: block.latencyMs, reason: block.error || block.reason }
  return { text: '-', type: 'info' }
}

const connItems = computed(() => {
  const r = connResult.value
  if (!r) return []
  return [
    { key: 'icmp', label: 'ICMP', ...formatConn(r.icmp) },
    { key: 'snmp', label: 'SNMP', ...formatConn(r.snmp) },
    { key: 'ssh', label: 'SSH', ...formatConn(r.ssh) }
  ]
})

const connSummaryType = computed(() => {
  const s = connResult.value?.summary || ''
  if (s.includes('全部通过')) return 'success'
  if (s.includes('部分')) return 'warning'
  return 'danger'
})

const loadPorts = async (id) => {
  if (!auth.hasPermission('performance:read')) return
  loadingPorts.value = true
  try {
    const res = await deviceApi.getDevicePorts(id)
    const list = Array.isArray(res) ? res : []
    ports.value = list.map((p) => ({
      index: p.portIndex ?? p.ifIndex ?? p.index,
      name: p.portName || p.name || p.ifName || `ifIndex=${p.portIndex ?? p.ifIndex ?? p.index}`,
      operStatus: p.portOperStatus || p.operStatus || p.status || '-'
    }))
  } catch {
    ports.value = []
  } finally {
    loadingPorts.value = false
  }
}

const loadAlarms = async (id) => {
  if (!auth.hasPermission('alarms:read')) return
  loadingAlarms.value = true
  try {
    const res = await alarmApi.queryAlarms({
      deviceId: id,
      status: ['ACTIVE', 'ACKNOWLEDGED'],
      page: 0,
      size: 30,
      sort: 'occurredAt,desc'
    })
    alarms.value = Array.isArray(res?.content) ? res.content : (Array.isArray(res) ? res : [])
  } catch {
    alarms.value = []
  } finally {
    loadingAlarms.value = false
  }
}

const runConnectivityTest = async () => {
  if (!props.device?.id) return
  testing.value = true
  try {
    connResult.value = await deviceApi.testConnectivity(props.device.id)
    ElMessage.success(connResult.value?.summary || '连通性测试完成')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '连通性测试失败')
  } finally {
    testing.value = false
  }
}

watch(
  () => [props.visible, props.device?.id],
  async ([vis, id]) => {
    if (!vis || !id) return
    tab.value = 'overview'
    connResult.value = null
    loading.value = true
    try {
      await Promise.all([loadPorts(id), loadAlarms(id)])
    } finally {
      loading.value = false
    }
  },
  { immediate: true }
)
defineExpose({ runConnectivityTest })
</script>

<style scoped>
.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding-right: 12px;
}
.drawer-title {
  font-size: 16px;
  font-weight: 650;
  color: var(--nms-text);
}
.drawer-sub {
  font-size: 12px;
  font-family: var(--nms-mono);
  color: var(--nms-text-secondary);
  margin-top: 2px;
}
.local-ops {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}
.related-mods {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-bottom: 14px;
  padding: 8px 10px;
  border-radius: var(--nms-radius-sm, 6px);
  background: var(--nms-bg-elevated, #f8fafc);
  border: 1px solid var(--nms-border-soft, #e8eef5);
}
.related-label {
  font-size: 12px;
  color: var(--nms-text-muted, #8a96a8);
  font-weight: 600;
}
.related-link {
  border: none;
  background: none;
  padding: 0;
  font: inherit;
  font-size: 12px;
  color: var(--nms-primary);
  cursor: pointer;
}
.related-link:hover {
  text-decoration: underline;
}
.related-hint {
  margin-left: auto;
  font-size: 11px;
  color: var(--nms-text-muted, #8a96a8);
}
.conn-card {
  margin-bottom: 14px;
  padding: 10px 12px;
  border: 1px solid var(--nms-border-soft);
  border-radius: var(--nms-radius-sm);
  background: var(--nms-bg-elevated);
}
.conn-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--nms-text);
}
.conn-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.conn-item {
  background: #fff;
  border-radius: 6px;
  padding: 8px;
  border: 1px solid var(--nms-border-soft);
}
.conn-label {
  font-size: 12px;
  color: var(--nms-text-secondary);
  margin-bottom: 4px;
}
.conn-meta {
  margin-top: 4px;
  font-size: 11px;
  color: var(--nms-text-secondary);
  word-break: break-all;
}
.pane-actions {
  margin-bottom: 8px;
}
</style>
