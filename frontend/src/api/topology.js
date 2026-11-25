/**
 * @file    topology.js
 * @module  接口层/拓扑
 * @desc    拓扑模块后端接口封装。
 *
 * @author  ZC
 * @date    2026-08-15 * @version 1.0.0
 */

import request from './index'

export const topologyApi = {
  // projectId不传 = 全量拓扑；传了则由后端按工程裁设备与链路
  getFullTopology(projectId) {
    const params = projectId != null ? { projectId } : {}
    return request.get('/topology', { params })
  },

  saveNodePosition(deviceId, x, y) {
    return request.post(`/topology/nodes/${deviceId}/position`, { x, y })
  },

  createLink(sourceNodeId, targetNodeId, sourcePort, targetPort, bandwidth) {
    return request.post('/topology/links', {
      sourceNodeId,
      targetNodeId,
      sourcePort,
      targetPort,
      bandwidth
    })
  },

  addLink(data) {
    return request.post('/topology/links', data)
  },

  updateLink(id, data) {
    return request.put(`/topology/links/${id}`, data)
  },

  updateLinkStatus(linkId, status) {
    return request.put(`/topology/links/${linkId}/status`, { status })
  },

  deleteLink(id) {
    return request.delete(`/topology/links/${id}`)
  },

  getDeviceNeighbors(deviceId) {
    return request.get(`/topology/devices/${deviceId}/neighbors`)
  },

  discoverTopology(options = {}, config = {}) {
    return request.post('/topology/discover', options, config)
  },

  discoverDeviceNeighbors(deviceId) {
    return request.get(`/topology/discover/${deviceId}`)
  },

  getDiscoveryStatus() {
    return request.get('/topology/discover/status')
  },

  getLinks() {
    return request.get('/topology/links')
  }
}
