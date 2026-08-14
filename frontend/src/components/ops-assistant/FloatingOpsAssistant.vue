<template>
  <Teleport to="body">
    <div class="foa-root">
      <button
        v-show="!expanded && !hideFab"
        type="button"
        class="foa-fab"
        title="运维助手 (Ctrl+K)"
        @click="openFromUi"
      >
        <el-icon :size="22"><ChatDotRound /></el-icon>
      </button>
      <div v-show="expanded" class="foa-panel">
        <OpsAssistantPanel ref="panelRef" @collapse="collapse" />
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ChatDotRound } from '@element-plus/icons-vue'
import OpsAssistantPanel from './OpsAssistantPanel.vue'
import { setOpsAssistantContext, useOpsAssistantBus } from '@/composables/opsAssistantBus'

const COLLAPSE_KEY = 'foa_collapsed'
const bus = useOpsAssistantBus()
const route = useRoute()
const expanded = ref(false)
const panelRef = ref(null)

/** 工作台 / 告警列表已有右栏作业条：隐藏 FAB，Ctrl+K 仍可用 */
const hideFab = computed(() => {
  const p = route.path || ''
  return p.startsWith('/aiops/workbench') || p === '/aiops' || p.startsWith('/alarms')
    || p.startsWith('/topology') || p.startsWith('/configs') || p.startsWith('/performance')
})

function expand() {
  expanded.value = true
  localStorage.setItem(COLLAPSE_KEY, '0')
}

function collapse() {
  expanded.value = false
  localStorage.setItem(COLLAPSE_KEY, '1')
}

function syncRouteFocusIfEmpty() {
  if (bus.deviceId != null || bus.alarmId != null) return
  const q = route.query || {}
  const deviceId = q.deviceId != null && q.deviceId !== '' ? Number(q.deviceId) : null
  const alarmId = q.alarmId != null && q.alarmId !== '' ? Number(q.alarmId) : null
  if (!Number.isFinite(deviceId) && !Number.isFinite(alarmId)) {
    setOpsAssistantContext({
      source: route.path || 'page',
      title: route.meta?.title ? String(route.meta.title) : ''
    })
    return
  }
  setOpsAssistantContext({
    deviceId: Number.isFinite(deviceId) ? deviceId : undefined,
    alarmId: Number.isFinite(alarmId) ? alarmId : undefined,
    source: route.path || 'page',
    title: alarmId ? `告警 #${alarmId}` : (deviceId ? `设备 #${deviceId}` : '')
  })
}

async function openFromUi() {
  syncRouteFocusIfEmpty()
  expand()
  await nextTick()
  panelRef.value?.focusInput?.()
}

watch(() => bus.expandRequest, async (v) => {
  if (!v) return
  expand()
  await nextTick()
  panelRef.value?.focusInput?.()
})

watch(() => bus.focusInputRequest, async (v) => {
  if (!v || !expanded.value) return
  await nextTick()
  panelRef.value?.focusInput?.()
})

function onHotkey(e) {
  if (!(e.ctrlKey || e.metaKey) || String(e.key).toLowerCase() !== 'k') return
  const tag = (e.target && e.target.tagName) ? String(e.target.tagName).toLowerCase() : ''
  if (tag === 'textarea' && e.target?.closest?.('.oap')) return
  e.preventDefault()
  syncRouteFocusIfEmpty()
  setOpsAssistantContext({ source: 'hotkey', expand: true, focusInput: true })
  openFromUi()
}

onMounted(() => {
  if (localStorage.getItem(COLLAPSE_KEY) === '0') {
    expanded.value = true
  }
  window.addEventListener('keydown', onHotkey)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onHotkey)
})
</script>

<style scoped>
.foa-root {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 4000;
}
.foa-fab {
  width: 48px;
  height: 48px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
  color: var(--el-color-primary);
  cursor: pointer;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.12);
  display: flex;
  align-items: center;
  justify-content: center;
}
.foa-fab:hover {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.foa-panel {
  width: min(400px, calc(100vw - 24px));
  height: min(640px, calc(100vh - 64px));
  border-radius: 10px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.18);
  overflow: hidden;
  background: #0b1220;
  border: 1px solid #243044;
}
.foa-panel :deep(.oap) { height: 100%; }
</style>
