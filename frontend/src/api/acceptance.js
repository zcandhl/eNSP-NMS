import { ref } from 'vue'
import request from './index'
import { mockApi } from './acceptance.mock'

/**
 * 验收工作台接口封装
 *
 * 后端 /api/acceptance/** 未就绪时自动降级到演示数据，并在界面顶部标注"演示数据"，
 * 不把假数据伪装成真实采集结果。
 */
export const mockMode = ref(import.meta.env.VITE_ACCEPTANCE_MOCK === '1')

export function setMockMode(v) {
  mockMode.value = !!v
}

async function call(real, fallback) {
  if (mockMode.value) return fallback()
  try {
    return await real()
  } catch (e) {
    const status = e?.response?.status
    // 网络不可达 / 接口未实现（含网关代理报错）：切到演示模式，避免每次请求都等超时。
    // 切换后界面顶部会显示"演示数据"标记，不把演示结果当作真实采集。
    if (!e?.response || status >= 404) {
      mockMode.value = true
      return fallback()
    }
    throw e
  }
}

export const acceptanceApi = {
  listProjects() {
    return call(() => request.get('/acceptance/projects'), () => mockApi.listProjects())
  },
  createProject(payload) {
    return call(() => request.post('/acceptance/projects', payload), () => ({ ...payload, id: Date.now() }))
  },
  updateProject(id, payload) {
    return call(() => request.put(`/acceptance/projects/${id}`, payload), () => mockApi.updateProject(id, payload))
  },
  deleteProject(id) {
    return call(() => request.delete(`/acceptance/projects/${id}`), () => mockApi.deleteProject(id))
  },

  // 新建方案：projectId 必填；template=true（默认）带标准六项检查模板
  createPlan(payload) {
    return call(() => request.post('/acceptance/plans', payload), () => mockApi.createPlan(payload))
  },
  deletePlan(id) {
    return call(() => request.delete(`/acceptance/plans/${id}`), () => mockApi.deletePlan(id))
  },

  listPlans(projectId) {
    return call(
      () => request.get('/acceptance/plans', { params: { projectId } }),
      () => mockApi.listPlans(projectId)
    )
  },
  getPlan(id) {
    return call(() => request.get(`/acceptance/plans/${id}`), () => mockApi.getPlan(id))
  },
  savePlan(id, payload) {
    return call(() => request.put(`/acceptance/plans/${id}`, payload), () => mockApi.savePlan(id, payload))
  },

  checkTypes() {
    return call(() => request.get('/acceptance/check-types'), () => mockApi.checkTypes())
  },

  // 成员列表（负责人下拉数据源）：owner 绑定系统账号 username，realName 仅用于展示
  members() {
    return call(() => request.get('/acceptance/members'), () => mockApi.members())
  },

  // 试运行：只跑当前一项，不产生正式批次
  dryRun(planId, checkId, deviceId) {
    return call(
      () => request.post(`/acceptance/plans/${planId}/checks/${checkId}/dry-run`, { deviceId }),
      () => mockApi.dryRun(planId, checkId, deviceId)
    )
  },

  runPlan(planId, payload = {}) {
    return call(
      () => request.post(`/acceptance/plans/${planId}/run`, payload),
      () => mockApi.runPlan(planId)
    )
  },
  getBatch(batchId) {
    return call(() => request.get(`/acceptance/batches/${batchId}`), () => mockApi.getBatch(batchId))
  },
  listBatches(planId) {
    return call(
      () => request.get('/acceptance/batches', { params: { planId } }),
      () => mockApi.listBatches(planId)
    )
  },
  // 全局视角：跨方案查询所有运行中的验收批次（方案名/工程名 + 实时进度）
  listRunningBatches() {
    return call(
      () => request.get('/acceptance/batches/running'),
      () => mockApi.listRunningBatches()
    )
  },
  confirmItem(batchId, checkId, payload = {}) {
    return call(
      () => request.post(`/acceptance/batches/${batchId}/items/${checkId}/confirm`, payload),
      () => mockApi.getBatch(batchId)
    )
  },
  stopBatch(batchId) {
    return call(
      () => request.post(`/acceptance/batches/${batchId}/stop`),
      () => mockApi.stopBatch(batchId)
    )
  },
  voidBatch(batchId, reason = '') {
    return call(
      () => request.post(`/acceptance/batches/${batchId}/void`, { reason }),
      () => mockApi.voidBatch(batchId, reason)
    )
  },
  deleteBatch(batchId) {
    return call(
      () => request.delete(`/acceptance/batches/${batchId}`),
      () => mockApi.deleteBatch(batchId)
    )
  },

  generateReport(batchId) {
    return call(
      () => request.post(`/acceptance/batches/${batchId}/report`),
      () => mockApi.generateReport(batchId)
    )
  },
  downloadReport(reportId) {
    return call(
      () => request.get(`/acceptance/reports/${reportId}/download`, { responseType: 'blob' }),
      () => mockApi.downloadReport(reportId)
    )
  },

  // ── AI 摘要 ──
  // 注意：aiSummary 故意不走 call() 降级——AI 未启用/调用失败（503）必须如实报错，
  // 不能被">=404 切演示模式"的逻辑吞掉，更不能拿演示数据冒充 AI 结果。
  aiSummary(batchId) {
    if (mockMode.value) return mockApi.aiSummary(batchId)
    return request.post(`/acceptance/batches/${batchId}/ai-summary`)
  },
  async aiStatus() {
    if (mockMode.value) return mockApi.aiStatus()
    try {
      return await request.get('/acceptance/ai/status')
    } catch (e) {
      return { enabled: false, provider: '-', model: '-', providerLabel: '不可用' }
    }
  }
}

// 报告编号规则：YS-{yyyyMMdd}-{批次序号}，与方案里的 reportNoRule 对应
export function nextReportNo(date, batchSeq) {
  const d = new Date(date)
  const ymd = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`
  return `YS-${ymd}-${String(batchSeq).padStart(3, '0')}`
}
