import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PrototypeApp from './PrototypeApp.vue'

let wrapper: VueWrapper
beforeEach(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', { value: vi.fn(), configurable: true })
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    value: vi.fn(),
    configurable: true,
  })
  wrapper = mount(PrototypeApp, { global: { plugins: [ElementPlus] }, attachTo: document.body })
})
afterEach(() => {
  wrapper.unmount()
  document.body.innerHTML = ''
  vi.useRealTimers()
})

describe('independent UI prototype', () => {
  it('preserves a partial answer on stop and permits a new answer version', async () => {
    vi.useFakeTimers()
    await wrapper.get('textarea').setValue('如何查看引用？')
    await wrapper.get('.composer').trigger('submit')
    await vi.advanceTimersByTimeAsync(260)
    const partial = wrapper.findAll('.answer-body').at(-1)!.text()
    expect(partial).toContain('文档处理')
    await wrapper.get('[aria-label="停止生成"]').trigger('click')
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.findAll('.answer-body').at(-1)!.text()).toContain('已停止生成')
    expect(wrapper.findAll('.answer-body').at(-1)!.text()).not.toContain('步骤检查')
    await wrapper.findAll('[aria-label="重新生成"]').at(-1)!.trigger('click')
    await vi.advanceTimersByTimeAsync(6000)
    expect(wrapper.findAll('.answer-body').at(-1)!.text()).toContain('先确认文档状态')
    await wrapper.findAll('[aria-label="重新生成"]').at(-1)!.trigger('click')
    await vi.advanceTimersByTimeAsync(6000)
    expect(wrapper.find('.version-controls').text()).toContain('2 / 2')
  })

  it('limits ordinary users to their trace records and removes user management', async () => {
    await wrapper.get('.prototype-toggle').trigger('click')
    const roleSelect = wrapper.get('#prototype-controls').findComponent({ name: 'ElSelect' })
    roleSelect.vm.$emit('update:modelValue', 'user')
    await flushPromises()
    expect(wrapper.findAll('.nav-item').map((item) => item.text())).not.toContain('用户管理')
    await wrapper
      .findAll('.nav-item')
      .find((item) => item.text() === '链路追踪')!
      .trigger('click')
    expect(wrapper.find('[aria-label="用户筛选"]').exists()).toBe(false)
    expect(wrapper.find('.pagination').text()).toContain('共 9 条')
    expect(wrapper.find('.trace-table').text()).not.toContain('Qwen3')
  })

  it('opens a library and document with consistent counts and resets paging for search', async () => {
    await wrapper
      .findAll('.nav-item')
      .find((item) => item.text() === '知识库')!
      .trigger('click')
    await wrapper.findAll('.resource-name')[0]!.trigger('click')
    expect(wrapper.find('.pagination').text()).toContain('共 12 条')
    await wrapper.get('input[aria-label="搜索内容"]').setValue('知识库使用指南.md')
    expect(wrapper.findAll('.resource-row')).toHaveLength(1)
    await wrapper.findAll('.resource-name')[0]!.trigger('click')
    expect(wrapper.find('h1').text()).toBe('知识库使用指南.md')
    expect(wrapper.find('.pagination').text()).toContain('共 24 条')
  })

  it('selects the cited source without changing the answer', async () => {
    const original = wrapper.get('.answer-body').text()
    await wrapper.get('[aria-label="查看来源 2"]').trigger('click')
    await flushPromises()
    expect(document.querySelector('.source-active')?.textContent).toContain('文档上传与处理规范.md')
    expect(wrapper.get('.answer-body').text()).toBe(original)
  })
})
