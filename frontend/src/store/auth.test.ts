import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as api from '../api'
import { useAuthStore } from './auth'

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api')
  return {
    ...actual,
    login: vi.fn(),
    restoreSession: vi.fn(),
    logout: vi.fn(),
    changePassword: vi.fn(),
    setCsrfToken: vi.fn(),
  }
})

const user = {
  id: '00000000-0000-0000-0000-000000000010',
  username: 'admin',
  displayName: '管理员',
  role: 'ADMIN' as const,
  enabled: true,
  lastLoginAt: null,
  createdAt: '2026-09-15T00:00:00Z',
  updatedAt: '2026-09-15T00:00:00Z',
}

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('keeps authenticated user and CSRF nonce in memory after login', async () => {
    vi.mocked(api.login).mockResolvedValue({ user, csrfToken: 'nonce-1' })
    const store = useAuthStore()

    await store.login(' Admin ', 'password')

    expect(store.user).toEqual(user)
    expect(store.csrfToken).toBe('nonce-1')
    expect(api.setCsrfToken).toHaveBeenCalledWith('nonce-1')
    expect(localStorage).toHaveLength(0)
  })

  it('clears authentication when session restore returns 401', async () => {
    vi.mocked(api.restoreSession).mockRejectedValue(
      new api.ApiRequestError('需要登录', { status: 401 }),
    )
    const store = useAuthStore()

    await expect(store.restore()).resolves.toBe(false)

    expect(store.authenticated).toBe(false)
    expect(store.initialized).toBe(true)
    expect(api.setCsrfToken).toHaveBeenCalledWith(null)
  })

  it('replaces the nonce after changing password', async () => {
    vi.mocked(api.changePassword).mockResolvedValue({ user, csrfToken: 'nonce-2' })
    const store = useAuthStore()

    await store.changePassword('old-password', 'new-password')

    expect(store.csrfToken).toBe('nonce-2')
    expect(api.setCsrfToken).toHaveBeenLastCalledWith('nonce-2')
  })
})
