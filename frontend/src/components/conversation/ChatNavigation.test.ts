import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'

import AppSidebar from '../../layout/AppSidebar.vue'
import ConversationHistory from './ConversationHistory.vue'

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
    const sidebar = mount(AppSidebar, {
      props: { mode: 'chat' },
      slots: { default: '<div class="conversation-slot" />' },
      global: { plugins: [createPinia(), router], stubs: { AccountMenu: true } },
    })
    expect(sidebar.find('.workspace-nav').exists()).toBe(false)
    expect(sidebar.find('.conversation-slot').exists()).toBe(true)

    const history = mount(ConversationHistory, {
      props: { groups: [], currentId: '', loading: false, query: '' },
      global: { plugins: [createPinia(), router] },
    })
    const newChat = history.get('.new-chat-button')
    const management = history.get('.management-link')
    expect(newChat.text()).toContain('新对话')
    expect(management.text()).toContain('管理后台')
    expect(management.attributes('href')).toBe('/admin')
    expect(newChat.element.nextElementSibling).toBe(management.element)
    await management.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/admin')
  })

  it('keeps the management menu and a return link to chat on admin pages', async () => {
    const router = await testRouter()
    const sidebar = mount(AppSidebar, {
      props: { mode: 'admin' },
      global: { plugins: [createPinia(), router], stubs: { AccountMenu: true } },
    })
    const navigation = sidebar.get('.workspace-nav')
    expect(navigation.text()).toContain('知识问答')
    expect(navigation.text()).toContain('工作台')
    expect(navigation.text()).toContain('知识库')
    expect(navigation.text()).toContain('链路追踪')
    expect(navigation.text()).toContain('模型')
  })
})
