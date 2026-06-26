import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

import { useAuthStore } from '@/stores/auth'

export const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

let refreshing: Promise<boolean> | null = null

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const auth = useAuthStore()
  if (auth.accessToken) {
    config.headers.Authorization = `Bearer ${auth.accessToken}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const auth = useAuthStore()
    const config = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined

    if (error.response?.status === 401 && config && !config._retried && auth.refreshToken) {
      config._retried = true
      refreshing ||= auth.refresh().finally(() => {
        refreshing = null
      })
      const ok = await refreshing
      if (ok) {
        return http(config)
      }
    }

    if (error.response?.status === 401) {
      auth.clear()
      if (!window.location.pathname.startsWith('/login')) {
        window.location.assign(`/login?redirect=${encodeURIComponent(window.location.pathname + window.location.search)}`)
      }
    }

    return Promise.reject(new Error(extractErrorMessage(error)))
  },
)

export function extractErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const payload = error.response?.data as { message?: string; error?: string } | undefined
    const serverMessage = payload?.message || payload?.error
    if (serverMessage) {
      return serverMessage
    }
    if (error.response?.status === 400) {
      return '参数校验失败，请检查输入内容。'
    }
    if (error.response?.status === 401) {
      return '登录已过期，请重新登录。'
    }
    if (error.response?.status === 403) {
      return '权限不足，当前账号不能执行该操作。'
    }
    if (error.response?.status === 404) {
      return '资源不存在或当前账号不可访问。'
    }
    return error.message || '请求失败'
  }
  if (error instanceof Error) {
    return error.message
  }
  return '请求失败'
}

export function showError(error: unknown) {
  ElMessage.error(extractErrorMessage(error))
}
