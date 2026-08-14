import { reactive, readonly } from 'vue'

/**
 * 业务页 PageContext + ToolResultSink。
 * 助手/工具执行后通过 publishToolResult 回灌当前页；页面用 onToolResult / watch sinkToken 消费。
 */
const pageContext = reactive({
  page: '',
  deviceId: null,
  alarmId: null,
  title: '',
  deviceName: '',
  scenario: '',
  meta: {},
  updatedAt: 0
})

const sink = reactive({
  token: 0,
  last: null
})

export function setPageContext(payload = {}) {
  if (payload.page != null) pageContext.page = String(payload.page)
  if (payload.deviceId !== undefined) {
    pageContext.deviceId = payload.deviceId != null ? Number(payload.deviceId) || null : null
  }
  if (payload.alarmId !== undefined) {
    pageContext.alarmId = payload.alarmId != null ? Number(payload.alarmId) || null : null
  }
  if (payload.title != null) pageContext.title = String(payload.title)
  if (payload.deviceName != null) pageContext.deviceName = String(payload.deviceName)
  if (payload.scenario != null) pageContext.scenario = String(payload.scenario)
  if (payload.meta != null && typeof payload.meta === 'object') {
    pageContext.meta = { ...payload.meta }
  }
  pageContext.updatedAt = Date.now()
}

export function clearPageContext() {
  pageContext.page = ''
  pageContext.deviceId = null
  pageContext.alarmId = null
  pageContext.title = ''
  pageContext.deviceName = ''
  pageContext.scenario = ''
  pageContext.meta = {}
  pageContext.updatedAt = Date.now()
}

/**
 * @param {object} result executeTool / assist 工具结果
 * @param {{ source?: string, tool?: string }} meta
 */
export function publishToolResult(result, meta = {}) {
  if (!result || result.cancelled) return
  sink.last = {
    result,
    tool: meta.tool || result.tool || result?.detail?.tool || '',
    source: meta.source || 'assistant',
    at: Date.now()
  }
  sink.token = Date.now()
}

export function usePageOpsBus() {
  return {
    pageContext: readonly(pageContext),
    sink: readonly(sink)
  }
}

export function getPageContextSnapshot() {
  return {
    page: pageContext.page,
    deviceId: pageContext.deviceId,
    alarmId: pageContext.alarmId,
    title: pageContext.title,
    deviceName: pageContext.deviceName,
    scenario: pageContext.scenario,
    meta: { ...pageContext.meta }
  }
}
