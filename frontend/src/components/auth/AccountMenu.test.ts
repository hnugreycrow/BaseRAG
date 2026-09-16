import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import type { FormRules } from 'element-plus'
import { vi } from 'vitest'
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

  it('uses Element Plus rules and validates before submitting', async () => {
    vi.stubGlobal(
      'TextEncoder',
      class {
        encode(value: string) {
          return new Uint8Array(new Blob([value]).size)
        }
      },
    )
    const validate = vi.fn().mockResolvedValueOnce(false).mockResolvedValueOnce(true)
    const formStub = {
      name: 'ElForm',
      props: ['model', 'rules'],
      template: '<form><slot /></form>',
      methods: {
        clearValidate() {},
        validate() {
          return validate()
        },
      },
    }
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = user
    const changePassword = vi.spyOn(auth, 'changePassword').mockResolvedValue(undefined)
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
          ElForm: formStub,
          ElFormItem: { name: 'ElFormItem', props: ['prop'], template: '<label><slot /></label>' },
          ElButton: {
            emits: ['click'],
            template: '<button @click="$emit(\'click\')"><slot /></button>',
          },
          ElIcon: { template: '<i><slot /></i>' },
          ElInput: { template: '<input />' },
        },
      },
    })
    const formComponent = wrapper.findComponent(formStub)
    const model = formComponent.props('model') as {
      oldPassword: string
      newPassword: string
      confirmPassword: string
    }
    const rules = formComponent.props('rules') as FormRules
    expect(
      wrapper.findAllComponents({ name: 'ElFormItem' }).map((item) => item.props('prop')),
    ).toEqual(['oldPassword', 'newPassword', 'confirmPassword'])
    expect((rules.oldPassword as Array<{ required: boolean }>)[0]?.required).toBe(true)
    expect((rules.newPassword as Array<{ required: boolean }>)[0]?.required).toBe(true)
    expect((rules.confirmPassword as Array<{ required: boolean }>)[0]?.required).toBe(true)

    const newPasswordRule = (
      rules.newPassword as Array<{ validator: (_rule: unknown, value: string) => Promise<void> }>
    )[1]!
    await expect(newPasswordRule.validator(null, 'short')).rejects.toThrow('至少 12 个字符')
    await expect(newPasswordRule.validator(null, '😀'.repeat(19))).rejects.toThrow('72 字节')
    await expect(newPasswordRule.validator(null, 'a'.repeat(12) + '\0')).rejects.toThrow(
      '不能包含空字符',
    )
    await expect(newPasswordRule.validator(null, 'a-valid-new-password')).resolves.toBeUndefined()

    Object.assign(model, {
      oldPassword: 'current-password',
      newPassword: 'a-valid-new-password',
      confirmPassword: 'different-password',
    })
    const confirmRule = (
      rules.confirmPassword as Array<{
        validator: (_rule: unknown, value: string) => Promise<void>
      }>
    )[1]!
    await expect(confirmRule.validator(null, model.confirmPassword)).rejects.toThrow(
      '两次输入的新密码不一致',
    )
    model.confirmPassword = model.newPassword
    await expect(confirmRule.validator(null, model.confirmPassword)).resolves.toBeUndefined()

    const submit = wrapper.findAll('button').find((button) => button.text() === '确认修改')!
    await submit.trigger('click')
    await flushPromises()
    expect(changePassword).not.toHaveBeenCalled()

    await submit.trigger('click')
    await flushPromises()
    expect(validate).toHaveBeenCalledTimes(2)
    expect(changePassword).toHaveBeenCalledExactlyOnceWith(
      'current-password',
      'a-valid-new-password',
    )
    wrapper.unmount()
    vi.unstubAllGlobals()
  })
})
