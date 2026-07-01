import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

import { useAuthStore } from '@/stores/auth'

export const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

let refreshing: Promise<boolean> | null = null

const ERROR_MESSAGE_TRANSLATIONS: Record<string, string> = {
  'Knowledge base must keep at least one OWNER.': '知识库必须至少保留一名 OWNER。',
  'Knowledge base member not found.': '知识库成员不存在。',
  'Knowledge base not found.': '知识库不存在或当前账号不可访问。',
  'Knowledge base owner access is required.': '只有知识库 OWNER 可以执行该操作。',
  'Knowledge base creation requires ADMIN or EDITOR access.': '创建知识库需要 ADMIN 或 EDITOR 权限。',
  'Username is required.': '请输入用户名。',
  'Member role is required.': '请选择成员角色。',
  'Member role must be OWNER, EDITOR, or VIEWER.': '成员角色必须是 OWNER、EDITOR 或 VIEWER。',
  'Write access is required.': '当前账号没有写入权限。',
  'ADMIN access is required.': '当前操作需要 ADMIN 权限。',
}

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
      return translateServerMessage(serverMessage)
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
    return translateServerMessage(error.message)
  }
  return '请求失败'
}

function translateServerMessage(message: string): string {
  return ERROR_MESSAGE_TRANSLATIONS[message] || message
}

export function showError(error: unknown) {
  ElMessage.error(extractErrorMessage(error))
}
