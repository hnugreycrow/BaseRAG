import { createPinia } from 'pinia'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { vi } from 'vitest'

import { getConversation } from '../api'

import ConversationView from './ConversationView.vue'

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api')
  return {
    ...actual,
    listConversations: vi.fn().mockResolvedValue([]),
    getConversation: vi.fn(),
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

  it('restores the conversation toggle and a historical reasoning panel', async () => {
    Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
      configurable: true,
      value: vi.fn(),
    })
    vi.mocked(getConversation).mockResolvedValue({
      id: 'conversation',
      title: '测试',
      thinkingEnabled: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      turns: [
        {
          user: {
            id: 'user',
            turnIndex: 1,
            content: '问题',
            createdAt: '2026-01-01T00:00:00Z',
          },
          activeAssistantId: 'assistant',
          assistantVersions: [
            {
              id: 'assistant',
              replyToId: 'user',
              turnIndex: 1,
              variantIndex: 1,
              active: true,
              status: 'COMPLETED',
              content: '答案',
              thinkingEnabled: true,
              reasoningContent: '这里是历史思考内容',
              retrievalQuery: null,
              sources: [],
              citations: [],
              modelInfo: { id: 'deepseek-flash', provider: 'deepseek', model: 'deepseek-flash' },
              errorCode: null,
              errorMessage: null,
              createdAt: '2026-01-01T00:00:00Z',
              updatedAt: '2026-01-01T00:00:00Z',
              completedAt: '2026-01-01T00:00:00Z',
            },
          ],
        },
      ],
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/chat/:conversationId?', component: { template: '<div />' } }],
    })
    await router.push('/chat/conversation')
    await router.isReady()
    const wrapper = shallowMount(ConversationView, {
      global: { plugins: [createPinia(), router] },
    })
    await flushPromises()
    expect(wrapper.get('.thinking-toggle').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('.reasoning-panel summary').text()).toBe('深度思考')
    expect(wrapper.get('.reasoning-body').text()).toBe('这里是历史思考内容')
  })
})
