/**
 * @file    webTerminalBus.js
 * @module  组合式逻辑
 * @desc    Web 终端跨组件通信总线。
 *
 * @author  ZC
 * @date    2026-08-15 * @version 1.0.0
 * @requires vue
 */

import { reactive } from 'vue'

const state = reactive({
  open: false,
  deviceId: null,
  deviceName: '',
  
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
