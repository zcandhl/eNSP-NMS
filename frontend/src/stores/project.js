import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import request from '@/api/index'

/**
 * 全局工程上下文：整个软件共享"当前在哪个工程"这件事。
 * - 工程档案来自验收工程的同一张表（工程是系统级实体，验收只是其上的活动）
 * - 顶栏切换器、设备管理、告警、验收各页都读这里
 * - projectId 持久化到 localStorage，刷新后保持
 */
const KEY = 'ensp_nms_project'

export const useProjectStore = defineStore('project', () => {
  const projects = ref([])
  const projectId = ref(Number(localStorage.getItem(KEY)) || null)
  const loading = ref(false)
  const loaded = ref(false)

  const current = computed(
    () => projects.value.find((p) => p.id === projectId.value) || null
  )

  function persist() {
    if (projectId.value) localStorage.setItem(KEY, String(projectId.value))
    else localStorage.removeItem(KEY)
  }

  /**
   * 拉取工程列表。默认只拉一次（顶栏与各页共享），CRUD 后传 force=true。
   * 登录后由顶栏切换器触发；未登录时由调用方兜底。
   */
  async function load(force = false) {
    if (loaded.value && !force) return projects.value
    loading.value = true
    try {
      projects.value = await request.get('/acceptance/projects')
      // 当前选中工程可能已被删除：回落到第一个工程
      if (projectId.value && !projects.value.some((p) => p.id === projectId.value)) {
        projectId.value = projects.value[0]?.id || null
        persist()
      }
      if (!projectId.value && projects.value.length) {
        projectId.value = projects.value[0].id
        persist()
      }
      loaded.value = true
    } finally {
      loading.value = false
    }
    return projects.value
  }

  /** 切换当前工程并持久化 */
  function switchTo(id) {
    if (projectId.value === id) return
    projectId.value = id
    persist()
  }

  /** 退出登录时清空（不删工程数据） */
  function reset() {
    projectId.value = null
    persist()
    loaded.value = false
    projects.value = []
  }

  return { projects, projectId, current, loading, loaded, load, switchTo, reset }
})
