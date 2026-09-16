import { describe, expect, it, vi } from 'vitest'
import { createAdminUser, resetAdminUserPassword, setAdminUserEnabled } from '../../api/auth'
import { request } from '../../api/http'
vi.mock('../../api/http', () => ({ request: vi.fn().mockResolvedValue(undefined) }))
describe('admin user API contracts', () => {
  it('uses the existing server endpoints and field names', async () => {
    const input = {
      username: 'reader',
      displayName: '读者',
      password: 'test-password-123',
      role: 'USER' as const,
    }
    await createAdminUser(input)
    expect(request).toHaveBeenLastCalledWith({ url: '/admin/users', method: 'post', data: input })
    await setAdminUserEnabled('user-1', false)
    expect(request).toHaveBeenLastCalledWith({
      url: '/admin/users/user-1/status',
      method: 'patch',
      data: { enabled: false },
    })
    await resetAdminUserPassword('user-1', 'test-password-456')
    expect(request).toHaveBeenLastCalledWith({
      url: '/admin/users/user-1/password',
      method: 'put',
      data: { password: 'test-password-456' },
    })
  })
})
