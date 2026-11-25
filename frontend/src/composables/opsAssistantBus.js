/**
 * @file    opsAssistantBus.js
 * @module  组合式逻辑
 * @desc    运维助手跨组件事件总线。
 *
 * @author  ZC
 * @date    2026-08-15 * @version 1.0.0
 * @requires vue
 */

import { reactive, readonly } from 'vue'

const state = reactive({
  deviceId: null,
  alarmId: null,
  title: '',
  deviceName: '',
  source: '',
  updatedAt: 0,
  expandRequest: 0,
  
  focusInputRequest: 0,
  lastResult: null,
  recommendedTools: [],
  autoAskToken: 0,
  autoAskQuestion: '',
  scenario: '',
  scenarioLabel: '',
  handled: false,
  primaryToolLabel: ''
})

export function setOpsAssistantContext(payload = {}) {
  if (payload.deviceId != null) state.deviceId = Number(payload.deviceId) || null
  else if (payload.clearDevice) state.deviceId = null

  if (payload.alarmId != null) state.alarmId = Number(payload.alarmId) || null
  else if (payload.clearAlarm) state.alarmId = null

  if (payload.title != null) state.title = String(payload.title)
  if (payload.deviceName != null) state.deviceName = String(payload.deviceName)
  if (payload.source != null) state.source = String(payload.source)
  if (payload.recommendedTools != null) {
    state.recommendedTools = Array.isArray(payload.recommendedTools)
      ? payload.recommendedTools
      : []
  }
  if (payload.scenario != null) state.scenario = String(payload.scenario)
  if (payload.scenarioLabel != null) state.scenarioLabel = String(payload.scenarioLabel)
  if (payload.handled != null) state.handled = !!payload.handled
  if (payload.primaryToolLabel != null) state.primaryToolLabel = String(payload.primaryToolLabel)
  state.updatedAt = Date.now()
  if (payload.expand) {
    state.expandRequest = Date.now()
  }
  if (payload.focusInput) {
    state.focusInputRequest = Date.now()
  }
  if (payload.autoAsk) {
    const label = state.primaryToolLabel || '推荐操作'
    state.autoAskQuestion = payload.autoAskQuestion
      || `请基于当前焦点诊断并给出可确认执行的工具；优先只读诊断，写操作需确认。优先对齐「${label}」。`
    state.autoAskToken = Date.now()
  }
}

export function openOpsAssistant(payload = {}) {
  setOpsAssistantContext({
    ...payload,
    expand: true,
    focusInput: true,
    autoAsk: false
  })
}

export function clearOpsAssistantContext() {
  state.deviceId = null
  state.alarmId = null
  state.title = ''
  state.deviceName = ''
  state.source = ''
  state.recommendedTools = []
  state.scenario = ''
  state.scenarioLabel = ''
  state.handled = false
  state.primaryToolLabel = ''
  state.autoAskQuestion = ''
  state.updatedAt = Date.now()
}

export function pushOpsAssistantResult(result) {
  state.lastResult = result
  state.updatedAt = Date.now()
}

export function useOpsAssistantBus() {
  return readonly(state)
}

export function getOpsAssistantContextSnapshot() {
  return {
    deviceId: state.deviceId,
    alarmId: state.alarmId,
    title: state.title,
    deviceName: state.deviceName,
    source: state.source,
    recommendedTools: state.recommendedTools,
    scenario: state.scenario
  }
}
