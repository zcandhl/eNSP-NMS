import { ElMessage, ElMessageBox } from 'element-plus'
import { llmApi } from '@/api/device'

/**
 * 页内半闭环工具执行（确认后调用 /api/llm/execute-tool）。
 */
export function useOpsTools() {
  async function executeTool(tool, { skipConfirm = false } = {}) {
    const name = tool?.name || tool?.id
    if (!name) {
      return { ok: false, error: '缺少工具名' }
    }
    const args = { ...(tool?.params || tool?.args || {}) }
    const READ_ONLY = new Set([
      'inspect',
      'pull_live_config',
      'get_device_summary',
      'get_topo_neighbors',
      'get_perf_snapshot',
      'get_config_diff_summary',
      'list_active_alarms_for_device',
      'run_path_hint',
      'navigate_hint',
      'search_devices',
      'get_network_overview',
      'explain_cli_output',
      'suggest_config_commands',
      'get_alarm_detail',
      'list_config_backups',
      'get_backup_schedule_status',
      'ping_check',
      'probe_device',
      'traceroute_hint',
      'highlight_topology_nodes',
      'open_workbench_event',
      'get_interface_brief',
      'run_show_command',
      'get_config_compliance_score'
    ])
    const needConfirm = tool?.needConfirm !== false && !READ_ONLY.has(name)

    if (needConfirm && !skipConfirm) {
      const label = tool?.label || name
      try {
        await ElMessageBox.confirm(
          `将在当前页真实执行「${label}」。备份/回滚/确认会写库，不会无人值守改配。`,
          '确认执行',
          {
            type: name === 'restore_latest' ? 'error' : 'warning',
            confirmButtonText: '确认执行',
            cancelButtonText: '取消'
          }
        )
      } catch {
        return { ok: false, cancelled: true }
      }
    }

    try {
      const res = await llmApi.executeTool({
        name,
        args,
        confirmed: true
      })
      if (res?.ok === false) {
        ElMessage.error(res.error || '执行失败')
      } else {
        ElMessage.success(res?.message || '已执行')
      }
      return res || { ok: false, error: '空响应' }
    } catch (e) {
      const msg = e?.response?.data?.message || e?.message || '执行失败'
      ElMessage.error(msg)
      return { ok: false, error: msg }
    }
  }

  function formatToolResult(res) {
    if (!res) return '无结果'
    if (res.cancelled) return '已取消'
    if (res.ok === false) return `失败：${res.error || '未知错误'}`
    let text = res.message || '已完成'
    if (res.configText) {
      const preview = String(res.configText)
      const clipped = preview.length > 4000 ? preview.slice(0, 4000) + '\n…(已截断)' : preview
      text += `\n\n—— 配置内容 ——\n${clipped}`
    }
    if (res.steps?.length) {
      text += '\n' + res.steps.map((s, i) => `${i + 1}. ${s.title || s.code}: ${s.message || ''}`).join('\n')
    }
    return text
  }

  return { executeTool, formatToolResult }
}
