import { flushPromises, mount } from '@vue/test-utils'
import { vi } from 'vitest'
import { listQuestions } from '../../../api'
import QuestionDirectory from '../../../components/conversation/QuestionDirectory.vue'

vi.mock('../../../api', async () => ({
  ...(await vi.importActual<typeof import('../../../api')>('../../../api')),
  listQuestions: vi.fn(),
}))

beforeEach(() => vi.clearAllMocks())

it('shows only ticks at rest and reveals the active question on hover', async () => {
  const questions = [
    { id: 'q1', turnIndex: 1, preview: '第一个问题' },
    { id: 'q2', turnIndex: 2, preview: '当前问题' },
  ]
  vi.mocked(listQuestions).mockResolvedValue({ items: questions, hasMore: false })
  const wrapper = mount(QuestionDirectory, {
    props: { conversationId: 'one', activeTurn: 2, visibleQuestions: questions },
  })
  expect(wrapper.find('.directory-panel').exists()).toBe(false)
  expect(wrapper.findAll('.rail-tick')).toHaveLength(2)
  expect(wrapper.get('.directory-toggle').text()).toBe('')
  await wrapper.get('.question-directory').trigger('mouseenter')
  await flushPromises()
  expect(wrapper.get('[aria-current="location"]').text()).toBe('当前问题')
  await wrapper.get('.question-directory').trigger('mouseleave')
  expect(wrapper.find('.directory-panel').exists()).toBe(false)
  wrapper.unmount()
})

it('loads previews only on opening, paginates and emits the selected turn', async () => {
  vi.mocked(listQuestions).mockResolvedValueOnce({
    items: [{ id: 'q51', turnIndex: 51, preview: '较新的问题' }],
    hasMore: true,
  })
  const wrapper = mount(QuestionDirectory, { props: { conversationId: 'one' } })
  expect(listQuestions).not.toHaveBeenCalled()
  await wrapper.get('.directory-toggle').trigger('click')
  await flushPromises()
  expect(listQuestions).toHaveBeenLastCalledWith('one', undefined)
  vi.mocked(listQuestions).mockResolvedValueOnce({
    items: [{ id: 'q50', turnIndex: 50, preview: '更早的问题' }],
    hasMore: false,
  })
  await wrapper.get('.directory-more').trigger('click')
  await flushPromises()
  expect(listQuestions).toHaveBeenLastCalledWith('one', 51)
  expect(wrapper.findAll('.question-link').map((item) => item.text())).toEqual([
    '更早的问题',
    '较新的问题',
  ])
  await wrapper.findAll('.question-link')[0]!.trigger('click')
  expect(wrapper.emitted('jump')).toEqual([[50]])
  expect(wrapper.find('.directory-panel').exists()).toBe(false)
  wrapper.unmount()
})

it('discards a pending directory request when switching conversations', async () => {
  let resolve!: (value: Awaited<ReturnType<typeof listQuestions>>) => void
  vi.mocked(listQuestions).mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done
      }),
  )
  const wrapper = mount(QuestionDirectory, { props: { conversationId: 'one' } })
  await wrapper.get('.directory-toggle').trigger('click')
  await wrapper.setProps({ conversationId: 'two' })
  resolve({ items: [{ id: 'old', turnIndex: 9, preview: '旧会话问题' }], hasMore: false })
  await flushPromises()
  vi.mocked(listQuestions).mockResolvedValueOnce({ items: [], hasMore: false })
  await wrapper.get('.directory-toggle').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('旧会话问题')
  expect(wrapper.text()).toContain('暂无提问')
  wrapper.unmount()
})
