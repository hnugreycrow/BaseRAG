import { ElMessageBox } from 'element-plus'
import { createPinia } from 'pinia'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { vi } from 'vitest'

import {
  deleteConversation,
  getConversation,
  listConversations,
  renameConversation,
} from '../../api'
import ConversationHistory from '../../components/conversation/ConversationHistory.vue'

import ConversationView from '../../views/ConversationView.vue'

vi.mock('../../api', async () => {
  const actual = await vi.importActual<typeof import('../../api')>('../../api')
  return {
    ...actual,
    listConversations: vi.fn().mockResolvedValue([]),
    getConversation: vi.fn(),
    renameConversation: vi.fn(),
    deleteConversation: vi.fn(),
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

    expect(wrapper.get('.chat-title').text()).toBe('新对话')
    expect(wrapper.find('.chat-topbar el-button-stub').exists()).toBe(false)
    expect(wrapper.get('.welcome-state h1').text()).toBe('向知识库提问')
    expect(wrapper.get('.welcome-copy').text()).toBe('我会根据已入库资料回答，并标注可核对的来源。')
    expect(wrapper.get('.chat-workspace').classes()).toContain('is-empty')
    const composer = wrapper.get('.composer-shell')
    expect(composer.element.firstElementChild?.tagName.toLowerCase()).toBe('el-input')
    expect(composer.get('.composer-actions').get('.thinking-toggle').text()).toContain('深度思考')
    expect(composer.get('.composer-actions').get('.send-button').attributes('aria-label')).toBe(
      '发送问题',
    )
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
    expect(wrapper.get('.chat-title').text()).toBe('测试')
    expect(wrapper.get('.chat-workspace').classes()).not.toContain('is-empty')
    expect(wrapper.get('.thinking-toggle').attributes('aria-pressed')).toBe('true')
    expect(wrapper.find('.composer-shell .composer-actions .thinking-toggle').exists()).toBe(true)
    expect(wrapper.get('.reasoning-panel summary').text()).toBe('深度思考')
    expect(wrapper.get('.reasoning-body').text()).toBe('这里是历史思考内容')
  })

  it('updates the visible title after renaming and resets it after deleting the current conversation', async () => {
    const summary = {
      id: 'conversation',
      title: '原标题',
      thinkingEnabled: false,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    }
    vi.mocked(listConversations).mockResolvedValue([summary])
    vi.mocked(getConversation).mockResolvedValue({ ...summary, turns: [] })
    vi.mocked(renameConversation).mockResolvedValue({ ...summary, title: '新标题' })
    vi.mocked(deleteConversation).mockResolvedValue()
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({
      value: '新标题',
      action: 'confirm',
    } as never)
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/chat/:conversationId?', component: { template: '<div />' } }],
    })
    await router.push('/chat/conversation')
    await router.isReady()

    const wrapper = shallowMount(ConversationView, {
      global: {
        plugins: [createPinia(), router],
        stubs: { AppSidebar: { template: '<div><slot /></div>' } },
      },
    })
    await flushPromises()
    expect(wrapper.get('.chat-title').text()).toBe('原标题')

    const history = wrapper.findComponent(ConversationHistory)
    history.vm.$emit('rename', summary)
    await flushPromises()
    expect(wrapper.get('.chat-title').text()).toBe('新标题')
    expect(wrapper.get('.chat-title').attributes('title')).toBe('新标题')

    history.vm.$emit('remove', summary)
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/chat')
    expect(wrapper.get('.chat-title').text()).toBe('新对话')

    prompt.mockRestore()
    confirm.mockRestore()
  })
})
