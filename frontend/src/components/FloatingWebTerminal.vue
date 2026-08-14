<template>
  <Teleport to="body">
    <div
      v-show="bus.open"
      ref="panelRef"
      class="fwt-panel"
      :class="{ 'is-maximized': maximized, 'is-resizing': !!resizeDir }"
      :style="panelStyle"
    >
      <div class="fwt-header" @mousedown="onDragStart" @dblclick="toggleMaximize">
        <div class="fwt-title">
          <el-icon><Monitor /></el-icon>
          <span>Web 终端</span>
          <span v-if="sizeHintVisible" class="fwt-size-badge">{{ size.w }} × {{ size.h }}</span>
        </div>
        <div class="fwt-header-actions" @mousedown.stop>
          <el-button link :icon="Minus" title="最小化" @click="minimize" />
          <el-button
            link
            :icon="maximized ? CopyDocument : FullScreen"
            :title="maximized ? '还原' : '最大化'"
            @click="toggleMaximize"
          />
          <el-button link :icon="Close" title="关闭" @click="close" />
        </div>
      </div>

      <div class="fwt-toolbar" @mousedown.stop>
        <el-select
          v-model="selectedId"
          filterable
          clearable
          placeholder="选择设备"
          style="width: 100%"
          :loading="loadingDevices"
          teleported
          popper-class="fwt-device-select-popper"
          @change="onDeviceChange"
          @visible-change="onSelectVisible"
        >
          <el-option
            v-for="d in deviceOptions"
            :key="d.id"
            :label="d.label"
            :value="d.id"
            :disabled="d.disabled"
          />
        </el-select>
        <div v-if="!loadingDevices && !deviceOptions.length" class="fwt-hint">
          暂无设备，请先在设备管理中纳管设备
        </div>
        <div v-else-if="!loadingDevices && !sshReadyCount" class="fwt-hint">
          设备未配置 SSH 账号，请到设备管理填写用户名/密码后再连
        </div>
      </div>

      <div class="fwt-body">
        <XtermTerminal
          v-if="bus.deviceId"
          :key="bus.sessionKey + '-' + bus.deviceId"
          :device-id="bus.deviceId"
          :device-name="bus.deviceName"
          auto-connect
        />
        <el-empty v-else description="请选择已配置 SSH 账号的设备" :image-size="64" />
      </div>

      <!-- 八向缩放手柄（最大化时隐藏） -->
      <template v-if="!maximized">
        <div
          v-for="dir in resizeDirs"
          :key="dir"
          class="fwt-handle"
          :class="'fwt-handle-' + dir"
          @mousedown.stop.prevent="onResizeStart($event, dir)"
        />
      </template>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { Monitor, Minus, Close, FullScreen, CopyDocument } from '@element-plus/icons-vue'
import XtermTerminal from '@/components/XtermTerminal.vue'
import { deviceApi } from '@/api/device'
import { useWebTerminalBus, closeWebTerminal, setWebTerminalDevice } from '@/composables/webTerminalBus'
import { canUseWebSsh } from '@/utils/deviceCapabilities'

const STORAGE_KEY = 'ensp-web-terminal-geom'
const MIN_W = 480
const MIN_H = 300
const resizeDirs = ['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw']

const bus = useWebTerminalBus()
const panelRef = ref(null)
const devices = ref([])
const loadingDevices = ref(false)
const selectedId = ref(null)
const maximized = ref(false)
const sizeHintVisible = ref(false)
let sizeHintTimer = null

const pos = ref({ x: 80, y: 80 })
const size = ref({ w: 720, h: 480 })
const restoreGeom = ref(null)
const dragging = ref(false)
const resizeDir = ref('')
let dragOrigin = { mx: 0, my: 0, x: 0, y: 0 }
let resizeOrigin = { mx: 0, my: 0, x: 0, y: 0, w: 0, h: 0 }

const panelStyle = computed(() => {
  if (maximized.value) {
    return {
      left: '0px',
      top: '0px',
      width: '100vw',
      height: '100vh',
      borderRadius: '0'
    }
  }
  return {
    left: `${pos.value.x}px`,
    top: `${pos.value.y}px`,
    width: `${size.value.w}px`,
    height: `${size.value.h}px`
  }
})

const deviceOptions = computed(() =>
  (devices.value || []).map((d) => {
    const id = Number(d.id)
    const sshOk = canUseWebSsh(d)
    const name = d.name || `设备#${id}`
    const ip = d.ipAddress || '-'
    return {
      id,
      label: sshOk ? `${name} (${ip})` : `${name} (${ip}) · 未配 SSH`,
      disabled: !sshOk
    }
  }).filter((d) => Number.isFinite(d.id))
)

const sshReadyCount = computed(() => deviceOptions.value.filter((d) => !d.disabled).length)

function clampGeom(next) {
  const maxW = window.innerWidth
  const maxH = window.innerHeight
  let w = Math.min(maxW, Math.max(MIN_W, next.w))
  let h = Math.min(maxH, Math.max(MIN_H, next.h))
  let x = Math.min(Math.max(0, next.x), Math.max(0, maxW - w))
  let y = Math.min(Math.max(0, next.y), Math.max(0, maxH - h))
  return { x, y, w, h }
}

function saveGeom() {
  if (maximized.value) return
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      x: pos.value.x,
      y: pos.value.y,
      w: size.value.w,
      h: size.value.h
    }))
  } catch { /* ignore */ }
}

function loadGeom() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return false
    const g = JSON.parse(raw)
    if (!g || typeof g !== 'object') return false
    const c = clampGeom({
      x: Number(g.x) || 80,
      y: Number(g.y) || 80,
      w: Number(g.w) || 720,
      h: Number(g.h) || 480
    })
    pos.value = { x: c.x, y: c.y }
    size.value = { w: c.w, h: c.h }
    return true
  } catch {
    return false
  }
}

function flashSizeHint() {
  sizeHintVisible.value = true
  if (sizeHintTimer) clearTimeout(sizeHintTimer)
  sizeHintTimer = setTimeout(() => {
    sizeHintVisible.value = false
  }, 900)
}

async function loadDevices() {
  loadingDevices.value = true
  try {
    const res = await deviceApi.getDevices()
    let list = []
    if (Array.isArray(res)) list = res
    else if (Array.isArray(res?.content)) list = res.content
    else if (Array.isArray(res?.data)) list = res.data
    else if (Array.isArray(res?.data?.content)) list = res.data.content
    devices.value = list
  } catch {
    devices.value = []
  } finally {
    loadingDevices.value = false
  }
}

function onDeviceChange(id) {
  if (id == null || id === '') {
    setWebTerminalDevice({ deviceId: null, deviceName: '' })
    return
  }
  const d = deviceOptions.value.find((x) => Number(x.id) === Number(id))
  const raw = (devices.value || []).find((x) => Number(x.id) === Number(id))
  setWebTerminalDevice({
    deviceId: Number(id),
    deviceName: raw?.name || d?.label || ''
  })
}

function onSelectVisible(visible) {
  if (visible) loadDevices()
}

function close() {
  closeWebTerminal()
}

function minimize() {
  bus.open = false
}

function toggleMaximize() {
  if (maximized.value) {
    maximized.value = false
    if (restoreGeom.value) {
      const c = clampGeom(restoreGeom.value)
      pos.value = { x: c.x, y: c.y }
      size.value = { w: c.w, h: c.h }
    }
    restoreGeom.value = null
    flashSizeHint()
    saveGeom()
  } else {
    restoreGeom.value = {
      x: pos.value.x,
      y: pos.value.y,
      w: size.value.w,
      h: size.value.h
    }
    maximized.value = true
    // 最大化时角标显示视口尺寸
    size.value = { w: window.innerWidth, h: window.innerHeight }
    flashSizeHint()
  }
}

function onDragStart(e) {
  if (e.button !== 0 || maximized.value) return
  dragging.value = true
  dragOrigin = { mx: e.clientX, my: e.clientY, x: pos.value.x, y: pos.value.y }
  window.addEventListener('mousemove', onDragMove)
  window.addEventListener('mouseup', onDragEnd)
}

function onDragMove(e) {
  if (!dragging.value) return
  const dx = e.clientX - dragOrigin.mx
  const dy = e.clientY - dragOrigin.my
  const c = clampGeom({
    x: dragOrigin.x + dx,
    y: dragOrigin.y + dy,
    w: size.value.w,
    h: size.value.h
  })
  pos.value = { x: c.x, y: c.y }
}

function onDragEnd() {
  if (dragging.value) saveGeom()
  dragging.value = false
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', onDragEnd)
}

function onResizeStart(e, dir) {
  if (e.button !== 0 || maximized.value) return
  resizeDir.value = dir
  resizeOrigin = {
    mx: e.clientX,
    my: e.clientY,
    x: pos.value.x,
    y: pos.value.y,
    w: size.value.w,
    h: size.value.h
  }
  document.body.style.cursor = cursorFor(dir)
  document.body.style.userSelect = 'none'
  window.addEventListener('mousemove', onResizeMove)
  window.addEventListener('mouseup', onResizeEnd)
}

function cursorFor(dir) {
  const map = {
    n: 'ns-resize',
    s: 'ns-resize',
    e: 'ew-resize',
    w: 'ew-resize',
    ne: 'nesw-resize',
    sw: 'nesw-resize',
    nw: 'nwse-resize',
    se: 'nwse-resize'
  }
  return map[dir] || 'nwse-resize'
}

function onResizeMove(e) {
  const dir = resizeDir.value
  if (!dir) return
  const dx = e.clientX - resizeOrigin.mx
  const dy = e.clientY - resizeOrigin.my
  let { x, y, w, h } = resizeOrigin

  if (dir.includes('e')) w = resizeOrigin.w + dx
  if (dir.includes('s')) h = resizeOrigin.h + dy
  if (dir.includes('w')) {
    w = resizeOrigin.w - dx
    x = resizeOrigin.x + dx
  }
  if (dir.includes('n')) {
    h = resizeOrigin.h - dy
    y = resizeOrigin.y + dy
  }

  // 触达最小尺寸时锁住对应边，避免窗口跳动
  if (w < MIN_W) {
    if (dir.includes('w')) x = resizeOrigin.x + resizeOrigin.w - MIN_W
    w = MIN_W
  }
  if (h < MIN_H) {
    if (dir.includes('n')) y = resizeOrigin.y + resizeOrigin.h - MIN_H
    h = MIN_H
  }

  const c = clampGeom({ x, y, w, h })
  pos.value = { x: c.x, y: c.y }
  size.value = { w: c.w, h: c.h }
  flashSizeHint()
}

function onResizeEnd() {
  if (resizeDir.value) {
    saveGeom()
  }
  resizeDir.value = ''
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  window.removeEventListener('mousemove', onResizeMove)
  window.removeEventListener('mouseup', onResizeEnd)
}

function onWindowResize() {
  if (maximized.value) {
    size.value = { w: window.innerWidth, h: window.innerHeight }
    return
  }
  const c = clampGeom({
    x: pos.value.x,
    y: pos.value.y,
    w: size.value.w,
    h: size.value.h
  })
  pos.value = { x: c.x, y: c.y }
  size.value = { w: c.w, h: c.h }
}

watch(() => bus.open, (v) => {
  if (v) {
    selectedId.value = bus.deviceId != null ? Number(bus.deviceId) : null
    loadDevices()
  }
})

watch(() => bus.deviceId, (id) => {
  selectedId.value = id != null ? Number(id) : null
})

onMounted(() => {
  if (!loadGeom()) {
    pos.value = {
      x: Math.max(40, window.innerWidth - 760),
      y: 72
    }
    size.value = { w: 720, h: 480 }
  }
  window.addEventListener('resize', onWindowResize)
})

onBeforeUnmount(() => {
  onDragEnd()
  onResizeEnd()
  window.removeEventListener('resize', onWindowResize)
  if (sizeHintTimer) clearTimeout(sizeHintTimer)
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
})
</script>

<style scoped>
.fwt-panel {
  position: fixed;
  z-index: 4100;
  display: flex;
  flex-direction: column;
  background: #0b1220;
  border: 1px solid rgba(120, 160, 210, 0.35);
  border-radius: 10px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.45);
  overflow: hidden;
  min-width: 480px;
  min-height: 300px;
}
.fwt-panel.is-resizing {
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.55), 0 0 0 1px rgba(64, 158, 255, 0.45);
}
.fwt-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  background: linear-gradient(180deg, #1a2740, #132038);
  cursor: move;
  user-select: none;
  color: #e8eef7;
  flex-shrink: 0;
}
.fwt-panel.is-maximized .fwt-header {
  cursor: default;
}
.fwt-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
}
.fwt-size-badge {
  font-size: 11px;
  font-weight: 500;
  color: #9ec5ff;
  background: rgba(64, 158, 255, 0.18);
  border: 1px solid rgba(64, 158, 255, 0.35);
  border-radius: 4px;
  padding: 1px 6px;
}
.fwt-header-actions {
  display: flex;
  gap: 2px;
}
.fwt-header-actions :deep(.el-button) {
  color: #c5d4e8;
}
.fwt-toolbar {
  padding: 8px 10px;
  background: #111a2c;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}
.fwt-hint {
  margin-top: 6px;
  font-size: 12px;
  color: #9ab0c8;
  line-height: 1.4;
}
.fwt-body {
  flex: 1;
  min-height: 0;
  padding: 0 8px 8px;
  display: flex;
  flex-direction: column;
}
.fwt-body :deep(.xterm-terminal-container) {
  flex: 1;
  min-height: 0;
  height: 100%;
}

/* 边缘 / 角落缩放热区 */
.fwt-handle {
  position: absolute;
  z-index: 5;
  background: transparent;
}
.fwt-handle-n,
.fwt-handle-s {
  left: 8px;
  right: 8px;
  height: 6px;
  cursor: ns-resize;
}
.fwt-handle-n { top: 0; }
.fwt-handle-s { bottom: 0; }
.fwt-handle-e,
.fwt-handle-w {
  top: 8px;
  bottom: 8px;
  width: 6px;
  cursor: ew-resize;
}
.fwt-handle-e { right: 0; }
.fwt-handle-w { left: 0; }
.fwt-handle-ne,
.fwt-handle-nw,
.fwt-handle-se,
.fwt-handle-sw {
  width: 14px;
  height: 14px;
}
.fwt-handle-ne { top: 0; right: 0; cursor: nesw-resize; }
.fwt-handle-nw { top: 0; left: 0; cursor: nwse-resize; }
.fwt-handle-se {
  right: 0;
  bottom: 0;
  cursor: nwse-resize;
  background: linear-gradient(135deg, transparent 45%, rgba(144, 180, 230, 0.7) 45%);
}
.fwt-handle-sw { left: 0; bottom: 0; cursor: nesw-resize; }
</style>

<style>
.fwt-device-select-popper {
  z-index: 5200 !important;
}
</style>
