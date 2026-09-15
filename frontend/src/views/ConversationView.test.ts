import { createPinia } from 'pinia'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { vi } from 'vitest'

import ConversationView from './ConversationView.vue'

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api')
  return {
    ...actual,
    listConversations: vi.fn().mockResolvedValue([]),
  }
})

describe('ConversationView', () => {
  it('renders the concise empty state without promotional copy or suggestion cards', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/chat/:conversationId?', component: { template: '<div />' } }],
    })
    await router.push('/chat')
    await router.isReady()

    const wrapper = shallowMount(ConversationView, {
      global: { plugins: [createPinia(), router] },
    })
    await flushPromises()

    expect(wrapper.get('.welcome-state h1').text()).toBe('向知识库提问')
    expect(wrapper.get('.welcome-copy').text()).toBe('我会根据已入库资料回答，并标注可核对的来源。')
    expect(wrapper.find('.welcome-eyebrow').exists()).toBe(false)
    expect(wrapper.find('.suggestion-grid').exists()).toBe(false)
  })
})
