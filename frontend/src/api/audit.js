/**
 * @file    audit.js
 * @module  接口层/审计
 * @desc    审计日志后端接口封装。
 *
 * @author  ZC
 * @date    2026-08-15
 * @version 1.0.0
 */

import request from './index'

export const auditLogApi = {
  queryLogs(params = {}) {
    return request.get('/audit-logs', { params })
  },
  getById(id) {
    return request.get(`/audit-logs/${id}`)
  }
}
