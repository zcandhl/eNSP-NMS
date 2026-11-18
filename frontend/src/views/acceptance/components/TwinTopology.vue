<template>
  <div class="twin-card">
    <div class="twin-head">
      <span class="twin-title">数字孪生视图</span>
      <span class="twin-sub">整体拓扑 · 实时状态叠加{{ batchOverlayHint }}</span>
      <div class="twin-legend">
        <span class="lg"><i class="dot dot-ok"></i>可达/在线</span>
        <span class="lg"><i class="dot dot-bad"></i>不可达</span>
        <span class="lg"><i class="dot dot-unknown"></i>未知</span>
      </div>
      <el-button text size="small" class="twin-refresh" @click="load">刷新</el-button>
    </div>

    <div v-if="loading" class="twin-loading">拓扑加载中…</div>
    <div v-else-if="!nodes.length" class="twin-loading">暂无拓扑数据（先在拓扑页发现/保存设备）</div>
    <svg v-else class="twin-svg" :viewBox="viewBox" preserveAspectRatio="xMidYMid meet" @click="selected = null">
      <!-- 链路 -->
      <line
        v-for="l in renderedLinks"
        :key="l.key"
        :x1="l.x1" :y1="l.y1" :x2="l.x2" :y2="l.y2"
        :stroke="l.color"
        stroke-width="1.5"
      />
      <!-- 节点 -->
      <g
        v-for="n in styled"
        :key="n.id"
        class="twin-node"
        @click.stop="selected = n"
      >
        <rect
          :x="n.px - 13" :y="n.py - 13" width="26" height="26" rx="6"
          :fill="n.fill" :stroke="n.stroke" stroke-width="1.5"
        />
        <text :x="n.px" :y="n.py + 4.5" text-anchor="middle" class="node-glyph">{{ n.glyph }}</text>
        <text :x="n.px" :y="n.py + 38" text-anchor="middle" class="node-label">{{ n.name }}</text>
      </g>
    </svg>

    <div v-if="selected" class="twin-info">
      <span class="ti-name">{{ selected.name }}</span>
      <span class="ti-item">{{ selected.ipAddress }}</span>
      <span class="ti-item">{{ typeLabel(selected.deviceType) }}</span>
      <span class="ti-item">状态：{{ selected.status }}</span>
      <span v-if="selected.reachText" class="ti-item" :class="selected.reachOk ? 'ti-ok' : 'ti-bad'">
        {{ selected.reachText }}
      </span>
      <span class="ti-close" @click="selected = null">收起</span>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { topologyApi } from '../../../api/topology'

/**
 * 验收数字孪生视图：复用平台拓扑数据（/api/topology），
 * 在真实拓扑坐标上叠加设备在线状态与本批次可达性结果。
 * 采集手段仍是 SSH/探测，呈现层是结构化的"整体拓扑 + 状态着色"，
 * 对应答辩口径：采集层（SSH）→ 数据层 → 呈现层（孪生视图）解耦，采集器未来可换 NETCONF 遥测。
 */
const props = defineProps({
  batch: { type: Object, default: null }
})

const nodes = ref([])
const rawLinks = ref([])
const loading = ref(false)
const selected = ref(null)

const TYPE_GLYPH = { router: 'R', switch: 'S', ac: 'A', firewall: 'F', other: 'D' }
const TYPE_LABEL = { router: '路由器', switch: '交换机', ac: '无线控制器', firewall: '防火墙', other: '设备' }

/** 批次可达性结果按设备名索引（REACHABILITY 项的 detail 列表） */
const reachByName = computed(() => {
  const map = {}
  if (!props.batch || !Array.isArray(props.batch.items)) return map
  const item = props.batch.items.find((i) => i.type === 'REACHABILITY')
  const detail = item?.metrics?.detail
  if (Array.isArray(detail)) {
    detail.forEach((d) => {
      if (d && d.name != null) map[String(d.name)] = !!d.up
    })
  }
  return map
})

const hasReach = computed(() => Object.keys(reachByName.value).length > 0)
const batchOverlayHint = computed(() =>
  hasReach.value ? '（本批次可达性）' : (props.batch?.batchId ? '（设备在线状态）' : ''))

const nodesWithPos = computed(() => {
  const list = nodes.value
  const positioned = list.filter((n) => (n.x || 0) !== 0 || (n.y || 0) !== 0)
  // 全部无坐标时环形兜底排布，保证演示环境未摆位也能看
  if (positioned.length < list.length || positioned.length === 0) {
    const cx = 260
    const cy = 150
    const rx = 210
    const ry = 105
    return list.map((n, i) => {
      const angle = (Math.PI * 2 * i) / Math.max(list.length, 1) - Math.PI / 2
      const px = positioned.some((p) => p.id === n.id) && (n.x || 0) !== 0 ? n.x : cx + rx * Math.cos(angle)
      const py = positioned.some((p) => p.id === n.id) && (n.y || 0) !== 0 ? n.y : cy + ry * Math.sin(angle)
      return { ...n, px, py }
    })
  }
  return list.map((n) => ({ ...n, px: n.x || 0, py: n.y || 0 }))
})

const bounds = computed(() => {
  const xs = nodesWithPos.value.map((n) => n.px)
  const ys = nodesWithPos.value.map((n) => n.py)
  const pad = 40
  const minX = Math.min(0, ...xs) - pad
  const minY = Math.min(0, ...ys) - pad
  return { minX, minY, w: Math.max(...xs, 520) - minX + pad, h: Math.max(...ys, 300) - minY + pad }
})

const viewBox = computed(() => {
  const b = bounds.value
  return `${b.minX} ${b.minY} ${b.w} ${b.h}`
})

const nodeByName = computed(() => {
  const m = {}
  nodesWithPos.value.forEach((n) => { m[n.name] = n })
  return m
})

const renderedLinks = computed(() =>
  rawLinks.value.map((l, i) => {
    const a = nodeByName.value[l.sourceName] || nodesWithPos.value.find((n) => n.id === l.sourceNodeId)
    const b = nodeByName.value[l.targetName] || nodesWithPos.value.find((n) => n.id === l.targetNodeId)
    if (!a || !b) return null
    return {
      key: l.id ?? i,
      x1: a.px, y1: a.py, x2: b.px, y2: b.py,
      color: l.status === 'down' ? '#f56c6c' : '#c0c4cc'
    }
  }).filter(Boolean)
)

const styled = computed(() =>
  nodesWithPos.value.map((n) => {
    const reach = reachByName.value[n.name]
    let fill = '#eef2f7'
    let stroke = '#909399'
    if (reach === true || (reach === undefined && n.status === 'online')) {
      fill = '#e8f6ee'
      stroke = '#67c23a'
    } else if (reach === false || n.status === 'offline') {
      fill = '#fdecec'
      stroke = '#f56c6c'
    }
    return {
      ...n, fill, stroke,
      glyph: TYPE_GLYPH[n.deviceType] || TYPE_GLYPH.other,
      reachOk: reach === true,
      reachText: reach === undefined ? '' : (reach ? '本批次可达' : '本批次不可达')
    }
  })
)

const typeLabel = (t) => TYPE_LABEL[t] || '设备'

async function load() {
  loading.value = true
  try {
    const data = await topologyApi.getFullTopology()
    const list = Array.isArray(data?.nodes) ? data.nodes : []
    nodes.value = list.map((n) => ({
      id: n.id,
      name: n.name,
      ipAddress: n.ipAddress,
      status: n.status,
      deviceType: n.deviceType || n.iconType || 'other',
      model: n.model,
      x: Number(n.x) || 0,
      y: Number(n.y) || 0
    }))
    rawLinks.value = Array.isArray(data?.links) ? data.links : []
  } catch (e) {
    nodes.value = []
    rawLinks.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped lang="scss">
.twin-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
  padding: 10px 12px 12px;
}
.twin-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.twin-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.twin-sub {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.twin-legend {
  margin-left: auto;
  display: flex;
  gap: 10px;
  .lg {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 11px;
    color: var(--el-text-color-secondary);
  }
  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    display: inline-block;
  }
  .dot-ok { background: #67c23a; }
  .dot-bad { background: #f56c6c; }
  .dot-unknown { background: #909399; }
}
.twin-refresh {
  flex: 0 0 auto;
}
.twin-svg {
  width: 100%;
  /* 视口矮时自动收缩，避免把上方运行结果区挤掉 */
  height: clamp(200px, 26vh, 300px);
  background:
    radial-gradient(circle, var(--el-fill-color-light) 1px, transparent 1px) 0 0 / 22px 22px;
  border-radius: 6px;
}
.node-glyph {
  font-size: 13px;
  font-weight: 700;
  fill: var(--el-text-color-primary);
}
.node-label {
  font-size: 11px;
  fill: var(--el-text-color-regular);
}
.twin-node {
  cursor: pointer;
  &:hover rect {
    filter: brightness(0.96);
  }
}
.twin-loading {
  height: 300px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.twin-info {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  padding: 6px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  .ti-name { font-weight: 600; }
  .ti-item { color: var(--el-text-color-regular); }
  .ti-ok { color: #67c23a; }
  .ti-bad { color: #f56c6c; }
  .ti-close {
    margin-left: auto;
    color: var(--el-text-color-secondary);
    cursor: pointer;
    &:hover { color: var(--el-text-color-primary); }
  }
}
</style>
