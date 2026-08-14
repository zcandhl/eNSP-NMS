<template>
  <div v-if="auth.hasPermission('devices:read')" class="hdr-search" :class="{ focused }">
    <el-icon class="hdr-search-icon"><Search /></el-icon>
    <el-autocomplete
      v-model="keyword"
      :fetch-suggestions="querySearch"
      :trigger-on-focus="false"
      clearable
      placeholder="搜索设备 / IP"
      value-key="label"
      class="hdr-search-input"
      popper-class="hdr-search-popper"
      @focus="focused = true"
      @blur="focused = false"
      @select="onSelect"
    >
      <template #default="{ item }">
        <div class="hdr-search-item">
          <span class="dot" :class="item.status === 'online' ? 'on' : 'off'" />
          <div class="meta">
            <div class="name">{{ item.name }}</div>
            <div class="ip">{{ item.ipAddress || '-' }}</div>
          </div>
          <span class="type">{{ item.typeLabel }}</span>
        </div>
      </template>
    </el-autocomplete>
  </div>
</template>

<script setup>
import { h, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { deviceApi } from '@/api/device'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

const keyword = ref('')
const focused = ref(false)
let cache = null
let cacheAt = 0

const typeLabel = (d) => {
  const t = String(d?.deviceType || d?.type || '').toLowerCase()
  const map = {
    router: '路由',
    switch: '交换',
    firewall: '防火墙',
    ac: 'AC',
    ap: 'AP',
    server: '服务器',
    pc: 'PC'
  }
  return map[t] || (t || '设备')
}

const ensureDevices = async () => {
  const now = Date.now()
  if (cache && now - cacheAt < 60000) return cache
  const res = await deviceApi.getDevices()
  cache = Array.isArray(res) ? res : []
  cacheAt = now
  return cache
}

const querySearch = async (queryString, cb) => {
  const q = String(queryString || '').trim().toLowerCase()
  if (!q) {
    cb([])
    return
  }
  try {
    const list = await ensureDevices()
    const matched = list
      .filter((d) => {
        const name = String(d.name || '').toLowerCase()
        const ip = String(d.ipAddress || '').toLowerCase()
        return name.includes(q) || ip.includes(q)
      })
      .slice(0, 12)
      .map((d) => ({
        ...d,
        label: `${d.name} (${d.ipAddress || '-'})`,
        typeLabel: typeLabel(d)
      }))
    cb(matched)
  } catch {
    cb([])
  }
}

const go = (target, id) => {
  if (target === 'topology') {
    router.push({ path: '/topology', query: { deviceId: id } })
  } else if (target === 'performance') {
    router.push({ path: '/performance', query: { deviceId: id } })
  } else if (target === 'alarms') {
    router.push({ path: '/alarms', query: { deviceId: id } })
  } else {
    router.push({ path: '/devices', query: { deviceId: id } })
  }
}

const onSelect = async (item) => {
  if (!item?.id) return
  keyword.value = ''
  const id = String(item.id)

  const actions = []
  if (auth.hasPermission('topology:read')) actions.push({ value: 'topology', label: '拓扑定位', primary: true })
  if (auth.hasPermission('devices:read')) actions.push({ value: 'device', label: '设备详情' })
  if (auth.hasPermission('performance:read')) actions.push({ value: 'performance', label: '性能监控' })
  if (auth.hasPermission('alarms:read')) actions.push({ value: 'alarms', label: '相关告警' })
  if (!actions.length) return

  if (actions.length === 1) {
    go(actions[0].value, id)
    return
  }

  try {
    await ElMessageBox({
      title: item.name || '打开设备',
      message: () =>
        h('div', { class: 'hdr-jump-box' }, [
          h('div', { class: 'hdr-jump-ip' }, item.ipAddress || `ID ${id}`),
          h(
            'div',
            { class: 'hdr-jump-actions' },
            actions.map((a) =>
              h(
                ElButton,
                {
                  size: 'small',
                  type: a.primary ? 'primary' : 'default',
                  onClick: () => {
                    go(a.value, id)
                    ElMessageBox.close()
                  }
                },
                () => a.label
              )
            )
          )
        ]),
      showConfirmButton: false,
      showCancelButton: true,
      cancelButtonText: '取消',
      customClass: 'hdr-jump-msgbox'
    })
  } catch {
    /* 取消 */
  }
}
</script>

<style scoped>
.hdr-search {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  width: 200px;
  padding: 0 10px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--nms-border-soft, #e8eef5);
  border-radius: 8px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, width 0.15s ease;
}

.hdr-search.focused {
  width: 240px;
  border-color: var(--nms-primary, #2f6fed);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--nms-primary, #2f6fed) 16%, transparent);
  background: #fff;
}

.hdr-search-icon {
  color: #8a97a8;
  flex-shrink: 0;
}

.hdr-search-input {
  flex: 1;
  min-width: 0;
}

.hdr-search-input :deep(.el-input__wrapper) {
  box-shadow: none !important;
  background: transparent !important;
  padding: 0;
}

.hdr-search-input :deep(.el-input__inner) {
  font-size: 12px;
  height: 28px;
  line-height: 28px;
}

.hdr-search-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.dot.on { background: #1f9d6a; }
.dot.off { background: #c0c8d4; }

.meta {
  flex: 1;
  min-width: 0;
}

.name {
  font-size: 12px;
  font-weight: 550;
  color: #1a2332;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ip {
  font-size: 11px;
  color: #8a97a8;
  font-family: 'IBM Plex Mono', Consolas, monospace;
}

.type {
  font-size: 10px;
  color: #8a97a8;
  flex-shrink: 0;
}
</style>

<style>
.hdr-jump-box {
  text-align: left;
}
.hdr-jump-ip {
  font-size: 12px;
  color: #5c6b7f;
  font-family: 'IBM Plex Mono', Consolas, monospace;
  margin-bottom: 12px;
}
.hdr-jump-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
