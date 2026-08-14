import request from './index'

export const deviceApi = {
  getDevices() {
    return request.get('/devices')
  },
  queryDevices(params = {}) {
    return request.get('/devices/query', { params })
  },
  getDevice(id) {
    return request.get(`/devices/${id}`)
  },
  getDeviceStats() {
    return request.get('/devices/stats')
  },
  exportDevices(params = {}) {
    return request.get('/devices/export', { params, responseType: 'blob' })
  },
  testConnectivity(id) {
    return request.post(`/devices/${id}/connectivity-test`)
  },
  batchRefreshDevices(ids) {
    return request.post('/devices/batch/refresh', { ids })
  },
  batchDeleteDevices(ids) {
    return request.post('/devices/batch/delete', { ids })
  },
  batchUpdateGroup(ids, groupId) {
    return request.post('/devices/batch/group', { ids, groupId })
  },
  batchUpdateCredentials(ids, credentials) {
    return request.post('/devices/batch/credentials', { ids, credentials })
  },
  createDevice(data) {
    return request.post('/devices', data)
  },
  updateDevice(id, data) {
    return request.put(`/devices/${id}`, data)
  },
  deleteDevice(id) {
    return request.delete(`/devices/${id}`)
  },
  refreshDevice(id) {
    return request.post(`/devices/${id}/refresh`)
  },
  /** 兼容：同步扫描并自动入库 */
  discoverDevices(network, timeout, community = 'public', snmpPort = 161) {
    return request.post('/devices/discover', { network, timeout, community, snmpPort })
  },
  /** 异步扫描（不入库） */
  startDiscoverScan(payload) {
    return request.post('/devices/discover/scan', payload)
  },
  getDiscoverJob(jobId) {
    return request.get(`/devices/discover/jobs/${jobId}`)
  },
  importDiscovered(candidates) {
    return request.post('/devices/discover/import', { candidates })
  },
  /** 从交换机 ARP 发现终端（虚拟 PC）候选 */
  discoverEndpoints() {
    return request.post('/devices/discover/endpoints')
  },
  getDevicePorts(id) {
    return request.get(`/devices/${id}/ports`)
  }
}

export const performanceApi = {
  getLatestPerformance(deviceId) {
    return request.get(`/performance/device/${deviceId}/latest`).catch((err) => {
      // 兼容旧后端 404：尚无性能样本时视为空
      if (err?.response?.status === 404) return null
      throw err
    })
  },
  getPerformanceHistory(deviceId, start, end) {
    return request.get(`/performance/device/${deviceId}/history`, {
      params: { start, end }
    })
  },
  getLatestPortMetrics(deviceId) {
    return request.get(`/performance/device/${deviceId}/ports`)
  },
  getDeviceAlerts(deviceId, status = 'active,acknowledged') {
    return request.get(`/performance-alerts/device/${deviceId}`, {
      params: { status }
    })
  },
  getActiveAlerts() {
    return request.get('/performance-alerts/active')
  },
  acknowledgeAlert(alertId) {
    return request.post(`/performance-alerts/${alertId}/acknowledge`)
  },
  resolveAlert(alertId) {
    return request.post(`/performance-alerts/${alertId}/resolve`)
  }
}

export const aiopsApi = {
  getOverview(force = false) {
    return request.get('/aiops/overview', { params: force ? { force: true } : {} })
  },
  getHealth() {
    return request.get('/aiops/health')
  },
  getHealthReport() {
    return request.get('/aiops/report/health')
  },
  runEscalation() {
    return request.post('/aiops/escalation/run')
  },
  getIncidents() {
    return request.get('/aiops/incidents')
  },
  getRca() {
    return request.get('/aiops/rca')
  },
  detectAnomaly() {
    return request.post('/aiops/anomaly/detect')
  },
  inspect() {
    return request.post('/aiops/inspect')
  },
  getScreenBrief() {
    return request.get('/aiops/screen-brief')
  },
  runUnattended(body = {}) {
    return request.post('/aiops/unattended/run', body || {})
  },
  getUnattendedStatus() {
    return request.get('/aiops/unattended/status')
  },
  pauseUnattended(paused = true) {
    return request.post('/aiops/unattended/pause', { paused: !!paused })
  },
  listUnattendedRuns(params = {}) {
    return request.get('/aiops/unattended/runs', { params })
  },
  getUnattendedRun(id) {
    return request.get(`/aiops/unattended/runs/${id}`)
  },
  retryUnattendedRun(id) {
    return request.post(`/aiops/unattended/runs/${id}/retry`)
  },
  getTrends() {
    return request.get('/aiops/trends')
  },
  getAlarmContext(id) {
    return request.get(`/aiops/alarms/${id}/context`)
  },
  getWorkbenchFocus(alarmId, deviceId) {
    const params = {}
    if (alarmId != null && alarmId !== '') params.alarmId = alarmId
    if (deviceId != null && deviceId !== '') params.deviceId = deviceId
    return request.get('/aiops/workbench/focus', { params })
  },
  ask(question, deviceId, alarmId) {
    const body = { question: question || '' }
    if (deviceId != null && deviceId !== '') body.deviceId = deviceId
    if (alarmId != null && alarmId !== '') body.alarmId = alarmId
    return request.post('/aiops/assistant', body)
  },
  feedback(payload) {
    return request.post('/aiops/feedback', payload)
  },
  getFeedbackStats() {
    return request.get('/aiops/feedback/stats')
  },
  getPolicy() {
    return request.get('/aiops/policy')
  },
  updatePolicy(payload) {
    return request.put('/aiops/policy', payload)
  },
  backupAction(deviceId, confirmed = true, reason) {
    return request.post('/aiops/actions/backup', { deviceId, confirmed, reason })
  },
  restoreAction(deviceId, confirmed = true, reason) {
    return request.post('/aiops/actions/restore', { deviceId, confirmed, reason })
  },
  ackSecondary(deviceId = null, confirmed = true, alarmId = null) {
    const body = { confirmed }
    if (deviceId != null) body.deviceId = deviceId
    if (alarmId != null) body.alarmId = alarmId
    return request.post('/aiops/actions/ack-secondary', body)
  },
  disposeIncident(alarmId, deviceId = null, confirmed = true) {
    const body = { confirmed, alarmId }
    if (deviceId != null) body.deviceId = deviceId
    return request.post('/aiops/actions/dispose-incident', body)
  },
  refreshOffline(confirmed = true) {
    return request.post('/aiops/actions/refresh-offline', { confirmed })
  },
  testWebhook() {
    return request.post('/aiops/webhook/test')
  },
  getPlaybook(deviceId, alarmId) {
    const params = {}
    if (deviceId != null) params.deviceId = deviceId
    if (alarmId != null) params.alarmId = alarmId
    return request.get('/aiops/playbook', { params })
  },
  getTimeline(alarmId, deviceId) {
    const params = {}
    if (alarmId != null) params.alarmId = alarmId
    if (deviceId != null) params.deviceId = deviceId
    return request.get('/aiops/timeline', { params })
  },
  executePlaybook(payload) {
    return request.post('/aiops/playbook/execute', payload)
  }
}

export const llmApi = {
  getSettings() {
    return request.get('/llm/settings')
  },
  updateSettings(payload) {
    return request.put('/llm/settings', payload)
  },
  chat(payload) {
    return request.post('/llm/chat', { assist: true, ...payload })
  },
  assist(payload) {
    return request.post('/llm/assist', payload)
  },
  test() {
    return request.post('/llm/test')
  },
  executeTool(payload) {
    return request.post('/llm/execute-tool', payload)
  }
}

/** 告警查询参数序列化：数组用重复键 status=A&status=B，避免 axios 默认 status[]= 导致后端收不到 */
function alarmQueryConfig(params = {}, extra = {}) {
  return {
    params: { ...params },
    paramsSerializer: (p) => {
      const sp = new URLSearchParams()
      Object.entries(p || {}).forEach(([k, v]) => {
        if (v === undefined || v === null || v === '') return
        if (Array.isArray(v)) {
          v.filter((x) => x !== undefined && x !== null && x !== '').forEach((item) => {
            sp.append(k, String(item))
          })
        } else {
          sp.append(k, String(v))
        }
      })
      return sp.toString()
    },
    ...extra
  }
}

export const alarmApi = {
  getAllAlarms() {
    return request.get('/alarms')
  },
  queryAlarms(params = {}) {
    return request.get('/alarms/query', alarmQueryConfig(params))
  },
  /** 按当前筛选导出 CSV（blob） */
  exportAlarms(params = {}) {
    return request.get('/alarms/export', alarmQueryConfig(params, { responseType: 'blob' }))
  },
  getAlarmsByStatus(status) {
    return request.get(`/alarms/status/${status}`)
  },
  getAlarmsByDeviceId(deviceId) {
    return request.get(`/alarms/device/${deviceId}`)
  },
  getAlarmsByDeviceIdPage(deviceId, page = 0, size = 20) {
    return request.get(`/alarms/device/${deviceId}/page`, { params: { page, size } })
  },
  getAlarm(id) {
    return request.get(`/alarms/${id}`)
  },
  getAlarmStats() {
    return request.get('/alarms/stats')
  },
  acknowledgeAlarm(id, note) {
    const params = {}
    if (note) params.note = note
    return request.put(`/alarms/${id}/acknowledge`, null, { params })
  },
  clearAlarm(id) {
    return request.put(`/alarms/${id}/clear`)
  },
  deleteAlarm(id) {
    return request.delete(`/alarms/${id}`)
  },
  batchAcknowledge(ids, note) {
    return request.post('/alarms/batch-acknowledge', { ids, note })
  },
  batchClear(ids) {
    return request.post('/alarms/batch-clear', { ids })
  },
  batchDelete(ids) {
    return request.post('/alarms/batch-delete', { ids })
  }
}

export const configApi = {
  getConfigsByDeviceId(deviceId) {
    return request.get(`/configs/device/${deviceId}`)
  },
  getConfigsByDeviceIdPage(deviceId, page = 0, size = 20) {
    return request.get(`/configs/device/${deviceId}/page`, { params: { page, size } })
  },
  getConfig(id) {
    return request.get(`/configs/${id}`)
  },
  getBackupHealth() {
    return request.get('/configs/health')
  },
  pullLiveConfig(deviceId, type = 'running') {
    return request.get(`/configs/live/${deviceId}`, { params: { type } })
  },
  backupConfig(data) {
    return request.post('/configs/backup', data)
  },
  deleteConfig(id) {
    return request.delete(`/configs/${id}`)
  },
  batchDeleteConfigs(ids, deviceId = null) {
    return request.post('/configs/batch-delete', { ids, deviceId })
  },
  batchExportConfigs(ids) {
    return request.get('/configs/batch-export', {
      params: { ids: ids.join(',') },
      responseType: 'blob'
    })
  },
  testSshConnection(deviceId) {
    return request.post(`/configs/test-ssh/${deviceId}`)
  },
  restoreConfig(deviceId, configId) {
    return request.post(`/configs/restore/${deviceId}/${configId}`)
  },
  startRestoreTask(deviceId, configId, preBackup = true) {
    return request.post('/configs/tasks/restore', { deviceId, configId, preBackup })
  },
  startBatchApplyTask(deviceIds, params) {
    return request.post('/configs/tasks/batch-apply', {
      deviceIds,
      content: params.content,
      enableVariables: params.enableVariables || false,
      parallel: params.parallel || false,
      reason: params.reason || undefined,
      waveSize: params.waveSize || undefined,
      approvedRequestId: params.approvedRequestId || undefined
    })
  },
  previewBatchApply(deviceIds, params) {
    return request.post('/configs/batch-apply/preview', {
      deviceIds,
      content: params.content,
      enableVariables: params.enableVariables || false
    })
  },
  startBatchBackupTask(deviceIds, params = {}) {
    return request.post('/configs/tasks/batch-backup', {
      deviceIds,
      configType: params.configType || 'running',
      description: params.description || undefined
    })
  },
  getConfigTask(taskId) {
    return request.get(`/configs/tasks/${taskId}`)
  },
  listConfigTasks() {
    return request.get('/configs/tasks')
  },
  cancelConfigTask(taskId) {
    return request.post(`/configs/tasks/${taskId}/cancel`)
  },
  continueConfigTask(taskId, waveSize) {
    return request.post(`/configs/tasks/${taskId}/continue`, { waveSize: waveSize || undefined })
  },
  retryFailedConfigTask(taskId) {
    return request.post(`/configs/tasks/${taskId}/retry-failed`)
  },
  getCompliance(deviceId, source) {
    return request.get(`/configs/compliance/${deviceId}`, { params: source ? { source } : {} })
  },
  getComplianceRules() {
    return request.get('/configs/compliance/rules')
  },
  applyConfigTemplate(deviceId, content, reason) {
    return request.post(`/configs/apply-template/${deviceId}`, { content, reason })
  },
  batchApplyConfig(deviceIds, params) {
    return request.post('/configs/batch-apply', { 
      deviceIds, 
      content: params.content,
      enableVariables: params.enableVariables || false,
      parallel: params.parallel || false,
      reason: params.reason || undefined
    })
  },
  exportConfig(id) {
    return request.get(`/configs/${id}/export`, { responseType: 'blob' })
  }
}

export const backupScheduleApi = {
  getAllSchedules() {
    return request.get('/backup-schedules')
  },
  getSchedulesByDeviceId(deviceId) {
    return request.get(`/backup-schedules/device/${deviceId}`)
  },
  getSchedule(id) {
    return request.get(`/backup-schedules/${id}`)
  },
  createSchedule(data) {
    return request.post('/backup-schedules', data)
  },
  createPolicy(data) {
    return request.post('/backup-schedules/policy', data)
  },
  syncGroupPolicy(data) {
    return request.post('/backup-schedules/sync-group', data)
  },
  updateSchedule(id, data) {
    return request.put(`/backup-schedules/${id}`, data)
  },
  deleteSchedule(id) {
    return request.delete(`/backup-schedules/${id}`)
  },
  executeSchedule(id) {
    return request.post(`/backup-schedules/${id}/execute`)
  }
}

export const deviceGroupApi = {
  list() {
    return request.get('/device-groups')
  },
  get(id) {
    return request.get(`/device-groups/${id}`)
  },
  devices(id) {
    return request.get(`/device-groups/${id}/devices`)
  },
  create(data) {
    return request.post('/device-groups', data)
  },
  update(id, data) {
    return request.put(`/device-groups/${id}`, data)
  },
  setMembers(id, deviceIds) {
    return request.put(`/device-groups/${id}/members`, { deviceIds })
  },
  previewMembers(id, deviceIds) {
    return request.post(`/device-groups/${id}/members/preview`, { deviceIds })
  },
  remove(id) {
    return request.delete(`/device-groups/${id}`)
  }
}

export const userApi = {
  getUsers() {
    return request.get('/users')
  },
  getUser(id) {
    return request.get(`/users/${id}`)
  },
  createUser(data) {
    return request.post('/users', data)
  },
  updateUser(id, data) {
    return request.put(`/users/${id}`, data)
  },
  updatePassword(id, newPassword) {
    return request.put(`/users/${id}/password`, { newPassword })
  },
  unlockUser(id) {
    return request.post(`/users/${id}/unlock`)
  },
  getEffectivePermissions(id) {
    return request.get(`/users/${id}/effective-permissions`)
  },
  deleteUser(id) {
    return request.delete(`/users/${id}`)
  }
}

export const roleApi = {
  getRoles() {
    return request.get('/roles')
  },
  getRole(id) {
    return request.get(`/roles/${id}`)
  },
  createRole(data) {
    return request.post('/roles', data)
  },
  updateRole(id, data) {
    return request.put(`/roles/${id}`, data)
  },
  deleteRole(id) {
    return request.delete(`/roles/${id}`)
  }
}

export const permissionApi = {
  getPermissions() {
    return request.get('/permissions')
  },
  getPermissionGroups() {
    return request.get('/permissions/groups')
  }
}

export const configTemplateApi = {
  getTemplates() {
    return request.get('/config-templates')
  },
  getTemplate(id) {
    return request.get(`/config-templates/${id}`)
  },
  getTemplatesByDeviceType(deviceType) {
    return request.get(`/config-templates/device-type/${deviceType}`)
  },
  getTemplatesByCategory(category) {
    return request.get(`/config-templates/category/${category}`)
  },
  createTemplate(data) {
    return request.post('/config-templates', data)
  },
  updateTemplate(id, data) {
    return request.put(`/config-templates/${id}`, data)
  },
  deleteTemplate(id) {
    return request.delete(`/config-templates/${id}`)
  }
}

export const configChangeLogApi = {
  getLogs() {
    return request.get('/config-change-logs')
  },
  queryLogs(params = {}) {
    return request.get('/config-change-logs/query', { params })
  },
  getLogsByDevice(deviceId) {
    return request.get(`/config-change-logs/device/${deviceId}`)
  },
  getLog(id) {
    return request.get(`/config-change-logs/${id}`)
  },
  createLog(data) {
    return request.post('/config-change-logs', data)
  },
  updateLogStatus(id, data) {
    return request.put(`/config-change-logs/${id}/status`, data)
  },
  deleteLog(id) {
    return request.delete(`/config-change-logs/${id}`)
  }
}

