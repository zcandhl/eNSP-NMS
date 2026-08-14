/**
 * 最近一次智能巡检报告（本页展示用，不推运维助手）。
 */
const KEY = 'ensp_last_inspect_report'

export function saveInspectReport(res) {
  if (!res || typeof res !== 'object') return null
  const payload = {
    at: Date.now(),
    ok: res.ok !== false,
    generatedAt: res.generatedAt || null,
    summary: res.summary || '',
    lines: Array.isArray(res.lines) ? res.lines : [],
    evidence: res.evidence && typeof res.evidence === 'object' ? res.evidence : {},
    insights: Array.isArray(res.insights) ? res.insights : []
  }
  try {
    sessionStorage.setItem(KEY, JSON.stringify(payload))
  } catch { /* ignore quota */ }
  return payload
}

export function loadInspectReport() {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
}
