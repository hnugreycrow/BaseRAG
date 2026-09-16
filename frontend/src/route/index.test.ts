import { beforeEach, describe, expect, it } from 'vitest'

import { router } from '.'
import { pinia } from '../store'
import { useAuthStore } from '../store/auth'

describe('authentication route guard', () => {
  beforeEach(() => {
    const auth = useAuthStore(pinia)
    auth.clear()
  })

  it('redirects an anonymous user and preserves the target route', async () => {
    await router.push('/admin/knowledge-bases')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/knowledge-bases')
  })

  it('allows a restored user to enter protected routes', async () => {
    const auth = useAuthStore(pinia)
    auth.applySession({
      user: {
        id: '00000000-0000-0000-0000-000000000010',
        username: 'user',
        displayName: '用户',
        role: 'USER',
        enabled: true,
        lastLoginAt: null,
        createdAt: '2026-09-15T00:00:00Z',
        updatedAt: '2026-09-15T00:00:00Z',
      },
      csrfToken: 'nonce',
    })

    await router.push('/chat')

    expect(router.currentRoute.value.name).toBe('chat')
  })

  it('blocks a regular user from every admin route and old address', async () => {
    const auth = useAuthStore(pinia)
    auth.applySession({
      user: {
        id: '00000000-0000-0000-0000-000000000010',
        username: 'user',
        displayName: '用户',
        role: 'USER',
        enabled: true,
        lastLoginAt: null,
        createdAt: '2026-09-15T00:00:00Z',
        updatedAt: '2026-09-15T00:00:00Z',
      },
      csrfToken: 'nonce',
    })

    for (const path of [
      '/admin',
      '/admin/knowledge-bases',
      '/admin/models',
      '/admin/observability/run-1',
      '/admin/users',
      '/knowledge-bases',
      '/models',
      '/observability',
    ]) {
      await router.push(path)
      expect(router.currentRoute.value.name).toBe('chat')
    }
  })

  it('allows administrators into every management section', async () => {
    const auth = useAuthStore(pinia)
    const user = {
      id: 'user-1',
      username: 'reader',
      displayName: '读者',
      role: 'USER' as const,
      enabled: true,
      lastLoginAt: null,
      createdAt: '',
      updatedAt: '',
    }
    auth.applySession({ user, csrfToken: 'nonce' })
    await router.push('/admin/users')
    expect(router.currentRoute.value.name).toBe('chat')
    auth.applySession({ user: { ...user, role: 'ADMIN' }, csrfToken: 'nonce' })
    for (const [path, name] of [
      ['/admin', 'dashboard'],
      ['/admin/knowledge-bases', 'knowledge-bases'],
      ['/admin/models', 'models'],
      ['/admin/observability', 'observability'],
      ['/admin/users', 'users'],
    ]) {
      await router.push(path)
      expect(router.currentRoute.value.name).toBe(name)
    }
  })
})
