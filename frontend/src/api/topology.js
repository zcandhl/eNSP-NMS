import request from './index'

export const topologyApi = {
  getFullTopology() {
    return request.get('/topology')
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
