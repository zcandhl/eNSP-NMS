import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import request from '@/api/index'

const TOKEN_KEY = 'ensp_nms_token'
const USER_KEY = 'ensp_nms_user'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref(parseUser(localStorage.getItem(USER_KEY)))

  const isAuthenticated = computed(() => !!token.value)
  const displayName = computed(() => user.value?.realName || user.value?.username || '未登录')
  const roles = computed(() => user.value?.roles || [])
  const permissions = computed(() => user.value?.permissions || [])
  const isAdmin = computed(() => roles.value.includes('ADMIN'))

  function parseUser(raw) {
    if (!raw) return null
    try {
      return JSON.parse(raw)
    } catch {
      return null
    }
  }

  function persist() {
    if (token.value) {
      localStorage.setItem(TOKEN_KEY, token.value)
    } else {
      localStorage.removeItem(TOKEN_KEY)
    }
    if (user.value) {
      localStorage.setItem(USER_KEY, JSON.stringify(user.value))
    } else {
      localStorage.removeItem(USER_KEY)
    }
  }

  function applyUserPayload(data, withToken = false) {
    if (withToken && data.token) {
      token.value = data.token
    }
    user.value = {
      username: data.username,
      realName: data.realName,
      email: data.email,
      roles: data.roles || [],
      permissions: data.permissions || [],
      mustChangePassword: !!data.mustChangePassword
    }
    persist()
  }

  function hasPermission(code) {
    if (!code) return true
    // 仅依据 JWT/会话中的权限列表，不再用角色名绕过
    return permissions.value.includes(code)
  }

  function hasAnyPermission(...codes) {
    if (!codes.length) return true
    return codes.some((c) => permissions.value.includes(c))
  }

  async function login(username, password) {
    const data = await request.post('/auth/login', { username, password })
    applyUserPayload(data, true)
    return data
  }

  async function logout() {
    const tokenSnapshot = token.value
    // 先清本地会话，避免路由守卫认为仍已登录而跳回概览
    token.value = ''
    user.value = null
    persist()
    if (tokenSnapshot) {
      try {
        await request.post('/auth/logout', null, {
          headers: { Authorization: `Bearer ${tokenSnapshot}` }
        })
      } catch {
        // 登出审计失败不阻断本地清理
      }
    }
  }

  async function fetchMe() {
    if (!token.value) return null
    const data = await request.get('/auth/me')
    // /me 会返回刷新后的 token（含最新权限），需写入本地
    applyUserPayload(data, true)
    return data
  }

  async function changePassword(oldPassword, newPassword) {
    const data = await request.post('/auth/change-password', {
      oldPassword: oldPassword || '',
      newPassword
    })
    applyUserPayload(data, true)
    return data
  }

  return {
    token,
    user,
    isAuthenticated,
    displayName,
    roles,
    permissions,
    isAdmin,
    hasPermission,
    hasAnyPermission,
    login,
    logout,
    fetchMe,
    changePassword
  }
})