import { defineStore } from 'pinia'

import {
  ApiRequestError,
  changePassword as changePasswordRequest,
  login as loginRequest,
  logout as logoutRequest,
  restoreSession,
  setCsrfToken,
  type AuthSession,
  type AuthUser,
} from '../api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null as AuthUser | null,
    csrfToken: null as string | null,
    initialized: false,
  }),
  getters: {
    authenticated: (state) => state.user !== null,
  },
  actions: {
    applySession(session: AuthSession) {
      this.user = session.user
      this.csrfToken = session.csrfToken
      this.initialized = true
      setCsrfToken(session.csrfToken)
    },
    clear() {
      this.user = null
      this.csrfToken = null
      this.initialized = true
      setCsrfToken(null)
    },
    async login(username: string, password: string) {
      this.applySession(await loginRequest(username, password))
    },
    async restore() {
      try {
        this.applySession(await restoreSession())
        return true
      } catch (error) {
        this.clear()
        if (error instanceof ApiRequestError && error.status === 401) return false
        throw error
      }
    },
    async logout() {
      try {
        await logoutRequest()
      } finally {
        this.clear()
      }
    },
    async changePassword(oldPassword: string, newPassword: string) {
      this.applySession(await changePasswordRequest(oldPassword, newPassword))
    },
  },
})
