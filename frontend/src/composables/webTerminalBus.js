import { reactive } from 'vue'

/**
 * 全局 Web 终端浮窗总线：导航栏 / 拓扑右键 / 其它页可打开同一可拖动终端。
 * 与运维助手解耦：终端只负责 SSH，不做 LLM 联动。
 */
const state = reactive({
  open: false,
  deviceId: null,
  deviceName: '',
  /** 递增以强制重新挂载终端会话 */
  sessionKey: 0
})

export function useWebTerminalBus() {
  return state
}

export function openWebTerminal({ deviceId = null, deviceName = '' } = {}) {
  const id = deviceId != null && deviceId !== '' ? Number(deviceId) : null
  state.deviceId = Number.isFinite(id) ? id : null
  state.deviceName = deviceName || ''
  state.sessionKey += 1
  state.open = true
}

export function closeWebTerminal() {
  state.open = false
}

export function setWebTerminalDevice({ deviceId, deviceName } = {}) {
  const id = deviceId != null && deviceId !== '' ? Number(deviceId) : null
  state.deviceId = Number.isFinite(id) ? id : null
  state.deviceName = deviceName || ''
  state.sessionKey += 1
}
