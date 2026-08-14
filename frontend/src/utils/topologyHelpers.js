export function normalizeLink(raw) {
  const l = raw || {}
  return {
    id: l.id,
    sourceNodeId: Number(l.sourceNodeId || l.sourceNode || l.source),
    targetNodeId: Number(l.targetNodeId || l.targetNode || l.target),
    sourcePort: l.sourcePort || '',
    targetPort: l.targetPort || '',
    bandwidth: l.bandwidth || '',
    status: l.status || 'up',
    ...l
  }
}

export function deriveNeighbors(deviceId, linkList) {
  const id = Number(deviceId)
  return (linkList || [])
    .filter(l => Number(l.sourceNodeId) === id || Number(l.targetNodeId) === id)
    .map(l => {
      const isSource = Number(l.sourceNodeId) === id
      return {
        id: l.id,
        neighborNodeId: isSource ? l.targetNodeId : l.sourceNodeId,
        localPort: isSource ? l.sourcePort : l.targetPort,
        remotePort: isSource ? l.targetPort : l.sourcePort,
        bandwidth: l.bandwidth || '',
        status: l.status
      }
    })
}

export function mapPortMetrics(portRes) {
  let portList = []
  if (Array.isArray(portRes)) {
    portList = portRes
  } else if (portRes && Array.isArray(portRes.data)) {
    portList = portRes.data
  }
  return portList
    .filter(p => p && (p.portName || p.name || p.ifName))
    .map(p => ({
      portName: p.portName || p.name || p.ifName,
      operStatus: p.portOperStatus || p.operStatus || p.status || 'unknown',
      ifInRate: p.ifInRate,
      ifOutRate: p.ifOutRate,
      ...p
    }))
}

export function findPortMetric(ports, portName) {
  if (!portName || !Array.isArray(ports) || !ports.length) return null
  const n = String(portName).trim().toLowerCase()
  if (!n) return null
  return (
    ports.find((p) => String(p.portName || '').trim().toLowerCase() === n) ||
    ports.find((p) => {
      const pn = String(p.portName || '').toLowerCase()
      return pn.includes(n) || n.includes(pn)
    }) ||
    null
  )
}

/** 解析带宽字符串为 bps，如 1G / 100Mbps / 1000 */
export function parseBandwidthBps(bw) {
  if (bw == null || bw === '') return null
  if (typeof bw === 'number' && Number.isFinite(bw)) return bw > 1e6 ? bw : bw * 1e6
  const s = String(bw).trim().toLowerCase().replace(/,/g, '')
  const m = s.match(/^([\d.]+)\s*(gbps|mbps|kbps|bps|g|m|k)?/)
  if (!m) return null
  const v = Number(m[1])
  if (!Number.isFinite(v)) return null
  const u = m[2] || 'mbps'
  if (u === 'gbps' || u === 'g') return v * 1e9
  if (u === 'kbps' || u === 'k') return v * 1e3
  if (u === 'bps') return v
  return v * 1e6
}

function isPortDown(metric) {
  if (!metric) return false
  const s = String(metric.operStatus || metric.portOperStatus || '').toLowerCase()
  return s === 'down' || s === '2' || s === 'lowerlayerdown'
}

function isPortUp(metric) {
  if (!metric) return false
  const s = String(metric.operStatus || metric.portOperStatus || '').toLowerCase()
  return s === 'up' || s === '1'
}

/**
 * 综合链路配置状态、端点在线、端口 operStatus、利用率，生成画布边样式
 */
export function resolveLinkVisual({
  link,
  sourceOnline = true,
  targetOnline = true,
  sourcePortMetric = null,
  targetPortMetric = null
}) {
  const bothOnline = sourceOnline && targetOnline
  const srcDown = isPortDown(sourcePortMetric)
  const tgtDown = isPortDown(targetPortMetric)
  const portDown = srcDown || tgtDown

  const inRate = Number(sourcePortMetric?.ifInRate || 0)
  const outRate = Number(sourcePortMetric?.ifOutRate || 0)
  const peerIn = Number(targetPortMetric?.ifInRate || 0)
  const peerOut = Number(targetPortMetric?.ifOutRate || 0)
  const maxRate = Math.max(inRate, outRate, peerIn, peerOut, 0)
  const cap = parseBandwidthBps(link?.bandwidth) || 1e9
  const utilPct = Math.max(0, Math.min(100, (maxRate / cap) * 100))

  let strokeColor = '#1f9d6a'
  let lineWidth = 2.5
  let lineDash = []
  let health = 'up'

  if (link?.status && link.status !== 'up') {
    strokeColor = '#d4380d'
    lineWidth = 2.5
    lineDash = [6, 4]
    health = 'admin-down'
  } else if (!bothOnline) {
    strokeColor = '#c0c4cc'
    lineWidth = 2
    lineDash = [4, 4]
    health = 'device-offline'
  } else if (portDown) {
    strokeColor = '#d4380d'
    lineWidth = 2.5
    lineDash = [5, 3]
    health = 'port-down'
  } else if (utilPct >= 80) {
    strokeColor = '#d4380d'
    lineWidth = 3.5
    health = 'congested'
  } else if (utilPct >= 50) {
    strokeColor = '#d48806'
    lineWidth = 3
    health = 'busy'
  } else if (maxRate > 0 || isPortUp(sourcePortMetric) || isPortUp(targetPortMetric)) {
    strokeColor = '#1f9d6a'
    lineWidth = 2.5 + Math.min(1.5, utilPct / 40)
    health = 'up'
  } else {
    strokeColor = '#67c23a'
    lineWidth = 2.5
    health = 'up'
  }

  const tipParts = []
  if (link?.sourcePort || link?.targetPort) {
    tipParts.push(`${link.sourcePort || '?'} ↔ ${link.targetPort || '?'}`)
  }
  if (link?.bandwidth) tipParts.push(`带宽 ${link.bandwidth}`)
  if (maxRate > 0) {
    const fmt = (r) => {
      if (r >= 1e9) return `${(r / 1e9).toFixed(2)} Gbps`
      if (r >= 1e6) return `${(r / 1e6).toFixed(2)} Mbps`
      if (r >= 1e3) return `${(r / 1e3).toFixed(1)} Kbps`
      return `${r.toFixed(0)} bps`
    }
    tipParts.push(`速率 ${fmt(maxRate)}`)
    tipParts.push(`利用率约 ${utilPct.toFixed(1)}%`)
  }
  if (health === 'port-down') tipParts.push('端口 down')
  if (health === 'device-offline') tipParts.push('端点设备离线')
  if (health === 'admin-down') tipParts.push('链路管理 down')
  if (health === 'congested') tipParts.push('利用率偏高')
  if (health === 'busy') tipParts.push('利用率中等')

  return {
    strokeColor,
    lineWidth,
    lineDash,
    health,
    utilPct,
    maxRate,
    tip: tipParts.join(' · ') || '链路'
  }
}

export function applyAlertSummaryToDevices(deviceList, alertSummary) {
  if (!alertSummary || !Array.isArray(deviceList)) return deviceList
  const byDevice = alertSummary.byDevice || {}
  const byIp = alertSummary.byIp || {}
  return deviceList.map(d => {
    const key = String(d.id)
    const ipKey = (d.ipAddress || '').trim()
    const fromDevice = byDevice[key]
    const fromIp = ipKey ? byIp[ipKey] : null
    // byIp 仅孤儿告警，与 byDevice 累加，与告警查询口径一致
    const count = Number(fromDevice?.count || 0) + Number(fromIp?.count || 0)
    const severe = !!(fromDevice?.severe || fromIp?.severe)
    return { ...d, alertCount: count, alertSevere: severe }
  })
}

/** BFS 最短路径（无向） */
export function findShortestPath(links, fromId, toId) {
  const from = String(fromId)
  const to = String(toId)
  if (!from || !to || from === to) return { nodes: [], edges: [] }
  const adj = new Map()
  const edgeByPair = new Map()
  ;(links || []).forEach((l) => {
    const a = String(l.sourceNodeId)
    const b = String(l.targetNodeId)
    if (!a || !b || a === 'NaN' || b === 'NaN' || a === b) return
    if (!adj.has(a)) adj.set(a, [])
    if (!adj.has(b)) adj.set(b, [])
    adj.get(a).push(b)
    adj.get(b).push(a)
    const key = [a, b].sort().join('-')
    if (!edgeByPair.has(key)) edgeByPair.set(key, String(l.id))
  })
  const q = [from]
  const prev = new Map([[from, null]])
  while (q.length) {
    const cur = q.shift()
    if (cur === to) break
    for (const nxt of adj.get(cur) || []) {
      if (prev.has(nxt)) continue
      prev.set(nxt, cur)
      q.push(nxt)
    }
  }
  if (!prev.has(to)) return { nodes: [], edges: [] }
  const nodes = []
  let walk = to
  while (walk != null) {
    nodes.push(walk)
    walk = prev.get(walk)
  }
  nodes.reverse()
  const edges = []
  for (let i = 0; i < nodes.length - 1; i++) {
    const key = [nodes[i], nodes[i + 1]].sort().join('-')
    const eid = edgeByPair.get(key)
    if (eid) edges.push(eid)
  }
  return { nodes, edges }
}
