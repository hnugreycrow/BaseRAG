import { createPinia } from 'pinia'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { vi } from 'vitest'
import { getConversation, type ConversationDetail } from '../../api'
import ConversationView from '../../views/ConversationView.vue'
import QuestionDirectory from '../../components/conversation/QuestionDirectory.vue'

vi.mock('../../api', async () => ({
  ...(await vi.importActual<typeof import('../../api')>('../../api')),
  listConversations: vi.fn().mockResolvedValue([]),
  getConversation: vi.fn(),
}))

function page(indices: number[], options: Partial<ConversationDetail> = {}): ConversationDetail {
  return {
    id: 'one',
    title: '分页会话',
    thinkingEnabled: false,
    createdAt: '',
    updatedAt: '',
    latestTurnIndex: 50,
    hasOlder: true,
    hasNewer: false,
    turns: indices.map((index) => ({
      user: { id: `question-${index}`, turnIndex: index, content: `提问 ${index}`, createdAt: '' },
      assistantVersions: [],
      activeAssistantId: null,
    })),
    ...options,
  }
}

async function mount() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/chat/:conversationId?', component: { template: '<div />' } }],
  })
  await router.push('/chat/one')
  const wrapper = shallowMount(ConversationView, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  vi.clearAllMocks()
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable: true, value: vi.fn() })
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    value: vi.fn(),
  })
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: () => ({ matches: true }),
  })
})

it('prepends older turns once and preserves the reading position', async () => {
  vi.mocked(getConversation).mockResolvedValueOnce(page([31, 32]))
  const { wrapper } = await mount()
  let resolve!: (value: ConversationDetail) => void
  vi.mocked(getConversation).mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done
      }),
  )
  const viewport = wrapper.get('.message-viewport').element as HTMLElement
  Object.defineProperty(viewport, 'scrollHeight', {
    configurable: true,
    get: () => wrapper.findAll('.turn').length * 100,
  })
  viewport.scrollTop = 20
  await wrapper.get('.load-turns').trigger('click')
  await wrapper.get('.load-turns').trigger('click')
  expect(getConversation).toHaveBeenCalledTimes(2)
  expect(getConversation).toHaveBeenLastCalledWith('one', { before: 31 })
  resolve(page([29, 30, 31]))
  await flushPromises()
  expect(wrapper.findAll('.turn').map((turn) => turn.attributes('data-turn-index'))).toEqual([
    '29',
    '30',
    '31',
    '32',
  ])
  expect(viewport.scrollTop).toBe(220)
  wrapper.unmount()
})

it('jumps directly to an unloaded question and can return to the latest window', async () => {
  vi.mocked(getConversation).mockResolvedValueOnce(page([49, 50]))
  const { wrapper } = await mount()
  vi.mocked(getConversation).mockResolvedValueOnce(page([6, 7], { hasNewer: true }))
  wrapper.getComponent(QuestionDirectory).vm.$emit('jump', 7)
  await flushPromises()
  expect(getConversation).toHaveBeenLastCalledWith('one', { target: 7 })
  expect(wrapper.get('[data-turn-index="7"]').classes()).toContain('is-highlighted')
  expect(wrapper.find('[data-turn-index="50"]').exists()).toBe(false)
  vi.mocked(getConversation).mockResolvedValueOnce(page([49, 50]))
  await wrapper.get('.latest-button').trigger('click')
  await flushPromises()
  expect(getConversation).toHaveBeenLastCalledWith('one', {})
  expect(wrapper.find('[data-turn-index="50"]').exists()).toBe(true)
  wrapper.unmount()
})

it('ignores older-page responses after switching conversations', async () => {
  vi.mocked(getConversation).mockResolvedValueOnce(page([31]))
  const { wrapper, router } = await mount()
  let resolve!: (value: ConversationDetail) => void
  vi.mocked(getConversation).mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done
      }),
  )
  await wrapper.get('.load-turns').trigger('click')
  vi.mocked(getConversation).mockResolvedValueOnce(page([1], { id: 'two', hasOlder: false }))
  await router.push('/chat/two')
  await flushPromises()
  resolve(page([21]))
  await flushPromises()
  expect(wrapper.findAll('.turn').map((turn) => turn.attributes('data-turn-index'))).toEqual(['1'])
  wrapper.unmount()
})

it('keeps loaded turns when an older-page request fails and supports retry', async () => {
  vi.mocked(getConversation).mockResolvedValueOnce(page([31]))
  const { wrapper } = await mount()
  vi.mocked(getConversation).mockRejectedValueOnce(new Error('网络异常'))
  await wrapper.get('.load-turns').trigger('click')
  await flushPromises()
  expect(wrapper.find('.history-error').exists()).toBe(true)
  expect(wrapper.findAll('.turn')).toHaveLength(1)
  vi.mocked(getConversation).mockResolvedValueOnce(page([30]))
  await wrapper.get('.load-turns').trigger('click')
  await flushPromises()
  expect(wrapper.findAll('.turn')).toHaveLength(2)
  wrapper.unmount()
})
