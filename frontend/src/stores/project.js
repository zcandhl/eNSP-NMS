/**
 * @file    project.js
 * @module  状态管理/项目
 * @desc    当前验收项目状态管理。
 *
 * @author  ZC
 * @date    2026-11-22 * @version 1.0.0
 * @requires pinia, vue, @/api/index
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import request from '@/api/index'

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

  
  function switchTo(id) {
    if (projectId.value === id) return
    projectId.value = id
    persist()
  }

  
  function reset() {
    projectId.value = null
    persist()
    loaded.value = false
    projects.value = []
  }

  return { projects, projectId, current, loading, loaded, load, switchTo, reset }
})
