import { setOpsAssistantContext } from '@/composables/opsAssistantBus'

/**
 * 业务页 → 全局运维助手。
 * 约定：
 * - 显式「智能分析 / 解读 / 诊断」按钮：传 autoAsk: true
 * - 选中行 / 业务操作完成后：用 syncOpsAssistantFocus，结果留在本页，不灌助手
 */
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

/** 只同步焦点，不展开、不自动提问（选中行 / 操作完成后用） */
export function syncOpsAssistantFocus(payload = {}) {
  askOpsAssistant({
    ...payload,
    expand: false,
    autoAsk: false
  })
}
