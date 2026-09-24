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
import { useConversationGenerationStore } from '../../store'

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
              content:
                '采购审批要求：\n| 含税总额 | 最少报价数 | 审批要求 |\n' +
                '| --- | ---: | --- |\n' +
                '| 不超过 5,000 元 | 1 | **直属负责人** [S1] |\n' +
                '| 超过 5,000 元 | 3 | `采购经理` |\n\n' +
                '表格之后。\n\n```text\n| 原样 | 显示 |\n| --- | --- |\n```',
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
    expect(wrapper.getComponent({ name: 'ReasoningPanel' }).props('content')).toBe(
      '这里是历史思考内容',
    )
    expect(wrapper.findAll('.answer-table')).toHaveLength(1)
    expect(wrapper.findAll('.answer-table th').map((cell) => cell.text())).toEqual([
      '含税总额',
      '最少报价数',
      '审批要求',
    ])
    expect(wrapper.findAll('.answer-table tbody tr')).toHaveLength(2)
    expect(wrapper.findAll('.answer-table td')[1]!.attributes('style')).toContain(
      'text-align: right',
    )
    expect(wrapper.get('.answer-table strong').text()).toBe('直属负责人')
    expect(wrapper.get('.answer-table code').text()).toBe('采购经理')
    expect(wrapper.get('.answer-code').text()).toContain('| --- | --- |')
    expect(wrapper.findAll('.answer-paragraph').map((item) => item.text())).toEqual([
      '采购审批要求：',
      '表格之后。',
    ])
    await wrapper.get('.answer-table .citation').trigger('click')
    expect(wrapper.getComponent({ name: 'SourcePanel' }).props('highlighted')).toBe('S1')
    expect(wrapper.get('.turn').classes()).not.toContain('is-entering')

    const writeText = vi.fn().mockResolvedValue(undefined)
    const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    vi.useFakeTimers()
    try {
      const copyButton = wrapper.get('.copy-answer')
      await copyButton.trigger('click')
      await Promise.resolve()
      await wrapper.vm.$nextTick()
      expect(writeText).toHaveBeenCalledWith(expect.stringContaining('采购审批要求：'))
      expect(copyButton.attributes('aria-label')).toBe('已复制')
      expect(copyButton.classes()).toContain('is-copied')
      await vi.advanceTimersByTimeAsync(1800)
      expect(copyButton.attributes('aria-label')).toBe('复制回答')
      expect(copyButton.classes()).not.toContain('is-copied')
      const viewport = wrapper.get('.message-viewport')
      Object.defineProperties(viewport.element, {
        scrollHeight: { configurable: true, value: 1600 },
        clientHeight: { configurable: true, value: 600 },
        scrollTop: { configurable: true, writable: true, value: 100 },
      })
      const scrollTo = vi.fn()
      Object.defineProperty(viewport.element, 'scrollTo', { value: scrollTo })
      await viewport.trigger('scroll')
      expect(wrapper.get('.latest-button').text()).toContain('返回最新消息')
      const detail = await vi.mocked(getConversation).mock.results[0]!.value
      const turn = detail.turns[0]!
      const store = useConversationGenerationStore()
      store.tasks.conversation = {
        conversationId: 'conversation',
        user: turn.user,
        assistant: { ...turn.assistantVersions[0]!, status: 'STREAMING' },
        phase: 'streaming',
        generationId: 'generation',
        controller: new AbortController(),
        serverStarted: true,
        unread: false,
      }
      await wrapper.vm.$nextTick()
      store.tasks.conversation!.assistant.content += '新的增量'
      await wrapper.vm.$nextTick()
      expect(scrollTo).not.toHaveBeenCalled()
      vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: true }))
      await wrapper.get('.latest-button').trigger('click')
      expect(scrollTo).toHaveBeenCalledWith({ top: 1600, behavior: 'auto' })
      store.tasks.conversation!.assistant.content += '继续生成'
      await wrapper.vm.$nextTick()
      await wrapper.vm.$nextTick()
      expect(scrollTo).toHaveBeenCalledTimes(2)

      writeText.mockRejectedValueOnce(new Error('denied'))
      await copyButton.trigger('click')
      await Promise.resolve()
      await wrapper.vm.$nextTick()
      expect(copyButton.classes()).not.toContain('is-copied')
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
      vi.useRealTimers()
      if (clipboardDescriptor) {
        Object.defineProperty(navigator, 'clipboard', clipboardDescriptor)
      } else {
        Reflect.deleteProperty(navigator, 'clipboard')
      }
    }
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
