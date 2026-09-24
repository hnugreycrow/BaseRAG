import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { vi } from 'vitest'
import type { AssistantMessage, AnswerSource } from '../../../api'
import SourcePanel from '../../../components/conversation/SourcePanel.vue'

const location = (chunkId: string, start: number) => ({
  chunkId,
  heading: '规则',
  range: { unit: 'LINE' as const, start, end: start, label: '第 ' + start + ' 行' },
})

function source(citationId: string, content = '# 标题\n\n**前段**\n\n后段'): AnswerSource {
  return {
    schemaVersion: 2,
    citationId,
    knowledgeBaseId: 'kb',
    knowledgeBaseName: '制度库',
    documentId: citationId,
    versionId: 'version',
    documentName: citationId + '-手册.md',
    format: 'MARKDOWN',
    content,
    primaryLocation: location('second', 8),
    locations: [location('first', 2), location('second', 8)],
  }
}

function message(id: string, citations: string[], sources: AnswerSource[]) {
  return { id, citations, sources } as AssistantMessage
}

function mountPanel(currentMessage: AssistantMessage) {
  return mount(SourcePanel, {
    props: { message: currentMessage, highlighted: 'S1', modelValue: true },
    global: {
      stubs: {
        ElDrawer: { template: '<div><slot /></div>' },
        ElDialog: {
          props: ['modelValue', 'title'],
          template:
            '<div v-if="modelValue" class="preview-dialog" role="dialog"><h2>{{ title }}</h2><slot /></div>',
        },
        ElEmpty: { template: '<div class="empty" />' },
      },
    },
  })
}

it('shows only cited sources as concise, keyboard accessible cards', async () => {
  const scrollIntoView = vi.fn()
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    value: scrollIntoView,
  })
  const wrapper = mountPanel(message('assistant', ['S1'], [source('S1'), source('S2')]))
  await nextTick()

  expect(wrapper.get('.source-count').text()).toBe('1 个来源')
  expect(wrapper.findAll('.source-card')).toHaveLength(1)
  const card = wrapper.get('.source-card')
  expect(card.attributes('id')).toBe('source-assistant-S1')
  expect(card.element.tagName).toBe('BUTTON')
  expect(card.attributes('type')).toBe('button')
  expect(card.attributes('aria-label')).toContain('预览来源 S1')
  expect(card.get('.source-summary').text()).toBe('标题 前段 后段')
  expect(card.text()).toContain('第 8 行 · 共 2 处')
  expect(card.classes()).toContain('highlighted')
  expect(wrapper.find('.preview-dialog').exists()).toBe(false)

  await card.trigger('click')
  expect(wrapper.get('.preview-dialog h2').text()).toContain('S1-手册.md')
  expect(wrapper.findAll('.preview-locations li').map((item) => item.text())).toEqual([
    '规则 · 第 2 行',
    '规则 · 第 8 行',
  ])
  expect(wrapper.get('.preview-content h1').text()).toBe('标题')
  expect(wrapper.get('.preview-content strong').text()).toBe('前段')
  wrapper.unmount()
})

it('clears the highlight and repeats it when the same citation is requested again', async () => {
  vi.useFakeTimers()
  const wrapper = mountPanel(message('assistant', ['S1'], [source('S1')]))
  try {
    await nextTick()
    await nextTick()
    expect(wrapper.get('.source-card').classes()).toContain('highlighted')
    await vi.advanceTimersByTimeAsync(1600)
    expect(wrapper.get('.source-card').classes()).not.toContain('highlighted')
    await wrapper.setProps({ locateRequest: 1 })
    await nextTick()
    await nextTick()
    expect(wrapper.get('.source-card').classes()).toContain('highlighted')
    await wrapper.setProps({ modelValue: false })
    await nextTick()
    expect(wrapper.get('.source-card').classes()).not.toContain('highlighted')
  } finally {
    wrapper.unmount()
    vi.useRealTimers()
  }
})

it('renders Markdown blocks and sanitizes untrusted HTML and links', async () => {
  const content =
    '# 标题\n\n- 项目一\n- 项目二\n\n| 名称 | 值 |\n| --- | --- |\n| A | B |\n\n' +
    '~~~js\nconst answer = 1\n~~~\n\n<script>alert(1)</script>\n\n' +
    '[危险链接](javascript:alert(1)) <img src=x onerror="alert(1)">'
  const wrapper = mountPanel(message('assistant', ['S1'], [source('S1', content)]))

  expect(wrapper.get('.source-summary').text()).not.toContain('#')
  expect(wrapper.get('.source-summary').text()).not.toContain('**')
  await wrapper.get('.source-card').trigger('click')
  expect(wrapper.findAll('.preview-content li')).toHaveLength(2)
  expect(wrapper.get('.preview-content table').text()).toContain('A')
  expect(wrapper.get('.preview-content pre code').text()).toContain('const answer = 1')
  expect(wrapper.find('.preview-content script').exists()).toBe(false)
  expect(wrapper.find('.preview-content [onerror]').exists()).toBe(false)
  expect(wrapper.find('.preview-content a[href^="javascript:"]').exists()).toBe(false)
  wrapper.unmount()
})

it('switches sources and clears the preview when the message changes or drawer closes', async () => {
  const wrapper = mountPanel(
    message('first', ['S1', 'S2'], [source('S1'), source('S2', '## 第二份')]),
  )
  await wrapper.findAll('.source-card')[0]!.trigger('click')
  expect(wrapper.get('.preview-dialog h2').text()).toContain('S1-手册.md')

  await wrapper.findAll('.source-card')[1]!.trigger('click')
  expect(wrapper.get('.preview-dialog h2').text()).toContain('S2-手册.md')
  expect(wrapper.get('.preview-content h2').text()).toBe('第二份')

  await wrapper.setProps({ message: message('second', ['S1'], [source('S1', '新回答')]) })
  expect(wrapper.find('.preview-dialog').exists()).toBe(false)
  await wrapper.get('.source-card').trigger('click')
  expect(wrapper.get('.preview-content').text()).toBe('新回答')

  await wrapper.setProps({ modelValue: false })
  expect(wrapper.find('.preview-dialog').exists()).toBe(false)
  wrapper.unmount()
})

it('offers the cited original only for a completed conversation answer', async () => {
  const wrapper = mount(SourcePanel, {
    props: {
      message: { ...message('assistant', ['S1'], [source('S1')]), status: 'COMPLETED' },
      highlighted: null,
      conversationId: 'conversation',
      modelValue: true,
    },
    global: {
      stubs: {
        ElDrawer: { template: '<div><slot /></div>' },
        ElDialog: {
          props: ['modelValue', 'title'],
          template: '<div v-if="modelValue"><slot /></div>',
        },
        ElEmpty: { template: '<div />' },
      },
    },
  })
  await wrapper.get('.source-card').trigger('click')
  const link = wrapper.get('.source-original-link')
  expect(link.attributes('href')).toBe(
    '/api/conversations/conversation/messages/assistant/sources/S1/content',
  )
  expect(link.attributes('target')).toBe('_blank')
  wrapper.unmount()
})
