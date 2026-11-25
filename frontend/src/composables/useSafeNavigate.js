/**
 * @file    useSafeNavigate.js
 * @module  组合式逻辑
 * @desc    带权限校验的路由跳转封装。
 *
 * @author  ZC
 * @date    2026-08-15 * @version 1.0.0
 * @requires element-plus
 */

import { ElMessage, ElMessageBox } from 'element-plus'

export const NAVIGATION_TOOLS = new Set([
  'navigate_hint',
  'open_workbench_event',
  'highlight_topology_nodes',
  'open_alarm_row'
])

export function isNavigationTool(name) {
  return NAVIGATION_TOOLS.has(String(name || '').toLowerCase())
}

function normalizePath(p) {
  const s = String(p || '').trim()
  if (!s) return '/'
  return s.length > 1 && s.endsWith('/') ? s.slice(0, -1) : s
}

export function isSamePage(currentPath, targetPath) {
  return normalizePath(currentPath) === normalizePath(targetPath)
}

export async function offerToolNavigate(router, route, { toolName, detail, onSamePage } = {}) {
  if (!detail?.navigate || !detail?.path) return false

  const path = detail.path
  const query = detail.query && typeof detail.query === 'object' ? { ...detail.query } : {}
  const label = detail.label || path

  if (isSamePage(route.path, path)) {
    if (typeof onSamePage === 'function') {
      await onSamePage(detail, query)
    } else {
      ElMessage.info(`已在「${label}」`)
    }
    return true
  }

  // 非导航类工具偶尔带navigate标记时，一律确认
  const title = isNavigationTool(toolName) ? '打开页面' : '页面跳转确认'
  try {
    await ElMessageBox.confirm(
      `即将离开当前页，前往「${label}」。是否继续？`,
      title,
      {
        type: 'info',
        confirmButtonText: '前往',
        cancelButtonText: '留在本页',
        distinguishCancelAndClose: true
      }
    )
    await router.push({ path, query })
    return true
  } catch {
    return true // 已处理，调用方勿再push
  }
}
