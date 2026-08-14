import { ref } from 'vue'
import request from '@/api/index'

const info = ref(null)
let pending = null

export function useLicenseInfo() {
  async function load(force = false) {
    if (info.value && !force) return info.value
    if (!pending) {
      pending = request
        .get('/system/info')
        .then((data) => {
          info.value = data || null
          return info.value
        })
        .catch(() => null)
        .finally(() => {
          pending = null
        })
    }
    return pending
  }

  return { info, load }
}
