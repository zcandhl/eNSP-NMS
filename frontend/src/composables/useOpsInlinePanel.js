/**
 * @file    useOpsInlinePanel.js
 * @module  组合式逻辑
 * @desc    行内运维面板显隐与状态管理。
 *
 * @author  ZC
 * @date    2026-08-15 * @version 1.0.0
 * @requires vue, @/composables/opsAssistantBus
 */

import { ref, watch } from 'vue'
import { useOpsAssistantBus } from '@/composables/opsAssistantBus'

const openRequests = ref({ key: '', token: 0 })

export function requestOpenOpsInline(storageKey) {
  openRequests.value = { key: String(storageKey || ''), token: Date.now() }
}

export function useOpsInlinePanel(storageKey, { defaultOpen = false } = {}) {
  const key = `ops_inline_open_${storageKey || 'default'}`
  const bus = useOpsAssistantBus()
  const pageKey = String(storageKey || 'default')

  function readStored() {
    try {
      const v = localStorage.getItem(key)
      if (v === '1') return true
      if (v === '0') return false
    } catch {  }
    return defaultOpen
  }

  const open = ref(readStored())

  function setOpen(next) {
    open.value = !!next
    try {
      localStorage.setItem(key, open.value ? '1' : '0')
    } catch {  }
  }

  function toggle() {
    setOpen(!open.value)
  }

  function expand() {
    setOpen(true)
  }

  function collapse() {
    setOpen(false)
  }

  watch(() => bus.autoAskToken, (t) => {
    if (t) expand()
  })
  watch(() => bus.expandRequest, (t) => {
    if (t) expand()
  })
  watch(
    () => openRequests.value.token,
    () => {
      if (openRequests.value.key === pageKey) expand()
    }
  )

  // 挂载时补一次：按钮已点但侧栏当时未挂载
  if (
    openRequests.value.key === pageKey &&
    openRequests.value.token &&
    Date.now() - openRequests.value.token < 3000
  ) {
    expand()
  }

  return { open, setOpen, toggle, expand, collapse }
}
