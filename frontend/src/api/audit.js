import request from './index'

export const auditLogApi = {
  queryLogs(params = {}) {
    return request.get('/audit-logs', { params })
  },
  getById(id) {
    return request.get(`/audit-logs/${id}`)
  }
}
