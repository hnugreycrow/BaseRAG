import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'

import { useAuthStore } from '../../store'
import AccountMenu from './AccountMenu.vue'

const user = {
  id: '00000000-0000-0000-0000-000000000010',
  username: 'admin',
  displayName: 'BaseRAG 管理员',
  role: 'ADMIN' as const,
  enabled: true,
  lastLoginAt: null,
  createdAt: '2026-09-15T00:00:00Z',
  updatedAt: '2026-09-15T00:00:00Z',
}

describe('AccountMenu', () => {
  it('shows only the display name and role while keeping account actions', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = user
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/chat')
    await router.isReady()

    const wrapper = mount(AccountMenu, {
      global: {
        plugins: [pinia, router],
        stubs: {
          ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
          ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
          ElDropdownItem: { template: '<button><slot /></button>' },
          ElDropdownMenu: { template: '<div><slot /></div>' },
          ElForm: { template: '<form><slot /></form>' },
          ElFormItem: { template: '<label><slot /></label>' },
          ElIcon: { template: '<i><slot /></i>' },
          ElInput: { template: '<input />' },
        },
      },
    })

    expect(wrapper.get('.copy strong').text()).toBe('BaseRAG 管理员')
    expect(wrapper.get('.copy small').text()).toBe('管理员')
    expect(wrapper.get('.copy').text()).not.toContain('admin')
    expect(wrapper.text()).toContain('修改密码')
    expect(wrapper.text()).toContain('退出登录')
  })
})
