import axios from 'axios'
import { ElMessage } from 'element-plus'

const TOKEN_KEY = 'ensp_nms_token'

const request = axios.create({
  baseURL: '/api',
  timeout: 180000
})

async function readBlobJsonMessage(data) {
  if (!(data instanceof Blob)) return null
  if (!data.type.includes('json') && data.size > 4096) return null
  try {
    const json = JSON.parse(await data.text())
    return json.message || json.error || null
  } catch {
    return null
  }
}

request.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => response.data,
  async (error) => {
    const status = error?.response?.status
    let message = error?.response?.data?.message || error.message || '请求失败'
    const blobMsg = await readBlobJsonMessage(error?.response?.data)
    if (blobMsg) message = blobMsg

    if (status === 401) {
      const code = error?.response?.data?.code
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem('ensp_nms_user')
      if (!window.location.pathname.startsWith('/login')) {
        const hint = code === 'AUTHORITY_CHANGED'
          ? '权限已变更，请重新登录'
          : (message || '登录已过期')
        try {
          sessionStorage.setItem('ensp_nms_login_hint', hint)
        } catch {
          // ignore
        }
        const redirect = encodeURIComponent(window.location.pathname + window.location.search)
        window.location.href = `/login?redirect=${redirect}`
      }
    } else if (status === 403) {
      ElMessage.error(message || '无权限')
    }

    console.error('API Error:', error)
    return Promise.reject(error)
  }
)

export default request
