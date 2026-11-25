/**
 * @file    inspectReportStore.js
 * @module  组合式逻辑
 * @desc    巡检报告本地暂存与读取。
 *
 * @author  ZC
 * @date    2026-08-15 * @version 1.0.0
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
  } catch {  }
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
