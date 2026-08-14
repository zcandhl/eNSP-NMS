/**
 * 用法：
 *   v-permission="'devices:write'"
 *   v-permission="['alarms:handle', 'alarms:write']"  // 任一即可
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
