import { request } from './http'

export type UserRole = 'ADMIN' | 'USER'

export interface AuthUser {
  id: string
  username: string
  displayName: string
  role: UserRole
  enabled: boolean
  lastLoginAt: string | null
  createdAt: string
  updatedAt: string
}

export interface AuthSession {
  user: AuthUser
  csrfToken: string
}

export function login(username: string, password: string) {
  return request<AuthSession>({ url: '/auth/login', method: 'post', data: { username, password } })
}

export function restoreSession() {
  return request<AuthSession>({ url: '/auth/session', method: 'get' })
}

export function logout() {
  return request<void>({ url: '/auth/logout', method: 'post' })
}

export function changePassword(oldPassword: string, newPassword: string) {
  return request<AuthSession>({
    url: '/auth/password',
    method: 'put',
    data: { oldPassword, newPassword },
  })
}
