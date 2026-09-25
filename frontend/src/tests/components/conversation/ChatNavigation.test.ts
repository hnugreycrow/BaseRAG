import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'

import AppSidebar from '../../../layout/AppSidebar.vue'
import AdminLayout from '../../../layout/AdminLayout.vue'
import { useAuthStore } from '../../../store/auth'
import ConversationHistory from '../../../components/conversation/ConversationHistory.vue'

async function testRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/chat', component: { template: '<div />' } },
      { path: '/admin', component: { template: '<div />' } },
    ],
  })
  await router.push('/chat')
  await router.isReady()
  return router
}

describe('chat and management navigation', () => {
  it('shows only conversation controls and the management entry in the chat sidebar', async () => {
    const router = await testRouter()
    const pinia = createPinia()
    useAuthStore(pinia).applySession({
      user: {
        id: 'admin',
        username: 'admin',
        displayName: '管理员',
        role: 'ADMIN',
        enabled: true,
        lastLoginAt: null,
        createdAt: '',
        updatedAt: '',
      },
      csrfToken: 'nonce',
    })
    const sidebar = mount(AppSidebar, {
      props: { mode: 'chat' },
      slots: { default: '<div class="conversation-slot" />' },
      global: { plugins: [pinia, router], stubs: { AccountMenu: true } },
    })
    expect(sidebar.find('.workspace-nav').exists()).toBe(false)
    expect(sidebar.find('.conversation-slot').exists()).toBe(true)

    const history = mount(ConversationHistory, {
      props: { groups: [], currentId: '', loading: false, query: '' },
      global: { plugins: [pinia, router] },
    })
    const newChat = history.get('.new-chat-button')
    const management = sidebar.get('.sidebar-account .management-link')
    expect(newChat.text()).toContain('新对话')
    expect(management.text()).toContain('管理后台')
    expect(management.attributes('href')).toBe('/admin')
    expect(history.find('.management-link').exists()).toBe(false)
    expect(newChat.element.nextElementSibling?.classList.contains('search-box')).toBe(true)
    await management.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/admin')
    expect(sidebar.emitted('navigate')).toHaveLength(1)
    await sidebar.setProps({ collapsed: true })
    expect(sidebar.get('.management-link').attributes('aria-label')).toBe('管理后台')
    expect(sidebar.find('.management-link span:not(.el-icon)').exists()).toBe(false)
  })

  it('hides management links from a regular user', async () => {
    const router = await testRouter()
    const pinia = createPinia()
    useAuthStore(pinia).applySession({
      user: {
        id: 'reader',
        username: 'reader',
        displayName: '读者',
        role: 'USER',
        enabled: true,
        lastLoginAt: null,
        createdAt: '',
        updatedAt: '',
      },
      csrfToken: 'nonce',
    })
    const history = mount(ConversationHistory, {
      props: { groups: [], currentId: '', loading: false, query: '' },
      global: { plugins: [pinia, router] },
    })
    expect(history.find('.management-link').exists()).toBe(false)
    const sidebar = mount(AppSidebar, {
      props: { mode: 'admin' },
      global: { plugins: [pinia, router], stubs: { AccountMenu: true } },
    })
    expect(sidebar.get('.brand').attributes('href')).toBe('/chat')
    expect(sidebar.get('.workspace-nav').findAll('a')).toHaveLength(0)
    expect(sidebar.get('.workspace-nav').text()).not.toContain('Dashboard')
  })

  it('keeps the management menu and a return link to chat on admin pages', async () => {
    const router = await testRouter()
    const pinia = createPinia()
    useAuthStore(pinia).applySession({
      user: {
        id: 'admin',
        username: 'admin',
        displayName: '管理员',
        role: 'ADMIN',
        enabled: true,
        lastLoginAt: null,
        createdAt: '',
        updatedAt: '',
      },
      csrfToken: 'nonce',
    })
    const sidebar = mount(AppSidebar, {
      props: { mode: 'admin' },
      global: { plugins: [pinia, router], stubs: { AccountMenu: true } },
    })
    const navigation = sidebar.get('.workspace-nav')
    const layout = mount(AdminLayout, {
      global: {
        plugins: [pinia, router],
        stubs: { AppSidebar: true, AccountMenu: true, 'el-drawer': true },
      },
    })
    expect(layout.get('.topbar .chat-link').text()).toContain('知识问答')
    expect(layout.get('.topbar .chat-link').attributes('href')).toBe('/chat')
    layout.unmount()
    expect(navigation.text()).toContain('Dashboard')
    expect(navigation.text()).toContain('知识库')
    expect(navigation.text()).toContain('链路追踪')
    expect(navigation.text()).toContain('模型')
  })
})
