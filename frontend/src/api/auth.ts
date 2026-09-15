import { request } from './http'
import type { PageResult } from './types'

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

/** 管理员分页搜索用户，供需要按所属用户过滤的界面复用。 */
export function listAdminUsers(page = 1, pageSize = 50, query = '') {
  return request<PageResult<AuthUser>>({
    url: '/admin/users',
    params: { page, pageSize, ...(query.trim() ? { query: query.trim() } : {}) },
  })
}

export interface CreateUserInput {
  username: string
  displayName: string
  password: string
  role: UserRole
}

export function createAdminUser(data: CreateUserInput) {
  return request<AuthUser>({ url: '/admin/users', method: 'post', data })
}

export function setAdminUserEnabled(id: string, enabled: boolean) {
  return request<AuthUser>({ url: `/admin/users/${id}/status`, method: 'patch', data: { enabled } })
}

export function resetAdminUserPassword(id: string, password: string) {
  return request<void>({ url: `/admin/users/${id}/password`, method: 'put', data: { password } })
}
