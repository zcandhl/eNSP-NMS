import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { deviceApi } from '@/api/device'

const CACHE_TTL_MS = 30_000

export const useDeviceStore = defineStore('device', () => {
  const devices = ref([])
  const loading = ref(false)
  const lastFetchedAt = ref(0)
  const error = ref(null)

  const onlineCount = computed(() => devices.value.filter((d) => d.status === 'online').length)
  const totalCount = computed(() => devices.value.length)

  async function fetchDevices(force = false) {
    const now = Date.now()
    if (!force && devices.value.length && now - lastFetchedAt.value < CACHE_TTL_MS) {
      return devices.value
    }
    loading.value = true
    error.value = null
    try {
      const res = await deviceApi.getDevices()
      devices.value = Array.isArray(res) ? res : (res?.data || [])
      lastFetchedAt.value = Date.now()
      return devices.value
    } catch (e) {
      error.value = e
      throw e
    } finally {
      loading.value = false
    }
  }

  function getById(id) {
    return devices.value.find((d) => String(d.id) === String(id)) || null
  }

  function invalidate() {
    lastFetchedAt.value = 0
  }

  return {
    devices,
    loading,
    error,
    onlineCount,
    totalCount,
    fetchDevices,
    getById,
    invalidate
  }
})
