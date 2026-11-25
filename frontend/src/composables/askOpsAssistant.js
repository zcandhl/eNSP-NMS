/**
 * @file    askOpsAssistant.js
 * @module  组合式逻辑
 * @desc    调用运维助手问答接口的组合式封装。
 *
 * @author  ZC
 * @date    2026-08-15 * @version 1.0.0
 * @requires @/composables/opsAssistantBus
 */

import { setOpsAssistantContext } from '@/composables/opsAssistantBus'

export function askOpsAssistant(payload = {}) {
  const deviceId = payload.deviceId != null ? Number(payload.deviceId) : null
  const alarmId = payload.alarmId != null ? Number(payload.alarmId) : null
  const title = payload.title != null ? String(payload.title) : ''
  const deviceName = payload.deviceName != null ? String(payload.deviceName) : ''
  const source = payload.source != null ? String(payload.source) : 'page'
  const primaryToolLabel = payload.primaryToolLabel || '下一步'
  const autoAsk = payload.autoAsk === true
  const expand = payload.expand != null ? !!payload.expand : autoAsk

  let autoAskQuestion = payload.autoAskQuestion
  if (autoAsk && !autoAskQuestion) {
    autoAskQuestion = '诊断当前焦点'
  }

  setOpsAssistantContext({
    deviceId: Number.isFinite(deviceId) ? deviceId : null,
    alarmId: Number.isFinite(alarmId) ? alarmId : null,
    clearDevice: deviceId == null && payload.clearDevice,
    clearAlarm: alarmId == null && payload.clearAlarm,
    title,
    deviceName,
    source,
    scenario: payload.scenario || '',
    scenarioLabel: payload.scenarioLabel || '',
    handled: !!payload.handled,
    primaryToolLabel,
    recommendedTools: Array.isArray(payload.recommendedTools) ? payload.recommendedTools : [],
    expand,
    autoAsk,
    autoAskQuestion: autoAsk ? autoAskQuestion : ''
  })
}

export function syncOpsAssistantFocus(payload = {}) {
  askOpsAssistant({
    ...payload,
    expand: false,
    autoAsk: false
  })
}
