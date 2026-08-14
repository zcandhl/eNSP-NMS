import { ElMessage, ElMessageBox } from 'element-plus'

/** 明确以「打开/跳转页面」为目的的工具 */
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

/**
 * 工具结果触发的页面跳转：
 * - 已在目标页：交给 onSamePage（本地聚焦），不重复 push
 * - 跨页：弹确认，避免诊断/误触把用户带走
 * @returns {Promise<boolean>} 是否已处理（含用户取消）
 */
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

  // 非导航类工具偶尔带 navigate 标记时，一律确认
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
    return true // 已处理（用户取消），调用方勿再 push
  }
}
