import { defineStore } from 'pinia'

import * as authApi from '@/api/auth'
import type { AuthResponse, GlobalRole } from '@/types/api'

const STORAGE_KEY = 'knowsource.frontend.auth'

interface AuthState {
  accessToken: string | null
  refreshToken: string | null
  userId: number | null
  username: string | null
  globalRole: GlobalRole | null
}

function readStoredAuth(): AuthState {
  const raw = localStorage.getItem(STORAGE_KEY)
  if (!raw) {
    return emptyState()
  }
  try {
    return { ...emptyState(), ...JSON.parse(raw) }
  } catch {
    return emptyState()
  }
}

function emptyState(): AuthState {
  return {
    accessToken: null,
    refreshToken: null,
    userId: null,
    username: null,
    globalRole: null,
  }
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => readStoredAuth(),
  getters: {
    isAuthenticated: (state) => Boolean(state.accessToken),
    canWrite: (state) => state.globalRole === 'ADMIN' || state.globalRole === 'EDITOR',
  },
  actions: {
    setAuth(auth: AuthResponse) {
      this.accessToken = auth.accessToken
      this.refreshToken = auth.refreshToken
      this.userId = auth.userId
      this.username = auth.username
      this.globalRole = auth.globalRole
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this.$state))
    },
    async login(payload: authApi.LoginPayload) {
      this.setAuth(await authApi.login(payload))
    },
    async register(payload: authApi.RegisterPayload) {
      this.setAuth(await authApi.register(payload))
    },
    async refresh() {
      if (!this.refreshToken) {
        this.clear()
        return false
      }
      try {
        this.setAuth(await authApi.refresh(this.refreshToken))
        return true
      } catch {
        this.clear()
        return false
      }
    },
    async logout() {
      const token = this.refreshToken
      this.clear()
      if (token) {
        await authApi.logout(token).catch(() => undefined)
      }
    },
    clear() {
      Object.assign(this, emptyState())
      localStorage.removeItem(STORAGE_KEY)
    },
  },
})
