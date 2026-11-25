/**
 * @file    permission.js
 * @module  自定义指令
 * @desc    按钮级权限指令，按权限编码控制元素显隐。
 *
 * @author  ZC
 * @date    2026-08-15 * @version 1.0.0
 * @requires vue, @/stores/auth
 */

import { watchEffect } from 'vue'
import { useAuthStore } from '@/stores/auth'

function check(value) {
  const auth = useAuthStore()
  if (value == null || value === '') return true
  if (Array.isArray(value)) return auth.hasAnyPermission(...value)
  return auth.hasPermission(value)
}

export const permission = {
  mounted(el, binding) {
    el.__permCleanup = watchEffect(() => {
      const ok = check(binding.value)
      el.style.display = ok ? '' : 'none'
      el.setAttribute('aria-hidden', ok ? 'false' : 'true')
      if (ok) {
        el.removeAttribute('disabled')
      }
    })
  },
  unmounted(el) {
    if (typeof el.__permCleanup === 'function') {
      el.__permCleanup()
    }
  },
  updated(el, binding) {
    const ok = check(binding.value)
    el.style.display = ok ? '' : 'none'
  }
}

export default permission
