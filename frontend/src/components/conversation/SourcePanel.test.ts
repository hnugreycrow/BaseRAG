import { mount } from '@vue/test-utils'
import { vi } from 'vitest'
import { nextTick } from 'vue'
import type { AssistantMessage, AnswerSource } from '../../api'
import SourcePanel from './SourcePanel.vue'

const location = (chunkId: string, start: number) => ({
  chunkId,
  heading: '规则',
  range: { unit: 'LINE' as const, start, end: start, label: `第 ${start} 行` },
})

function source(citationId: string): AnswerSource {
  return {
    schemaVersion: 2,
    citationId,
    knowledgeBaseId: 'kb',
    knowledgeBaseName: '制度库',
    documentId: citationId,
    versionId: 'version',
    documentName: '手册.md',
    format: 'MARKDOWN',
    content: '前段\n后段',
    primaryLocation: location('second', 8),
    locations: [location('first', 2), location('second', 8)],
  }
}

it('shows only cited documents and all selected positions in one card', async () => {
  const scrollIntoView = vi.fn()
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    value: scrollIntoView,
  })
  const message = {
    id: 'assistant',
    citations: ['S1'],
    sources: [source('S1'), source('S2')],
  } as AssistantMessage
  const wrapper = mount(SourcePanel, {
    props: { message, highlighted: 'S1', modelValue: true },
    global: {
      stubs: {
        ElDrawer: { template: '<div><slot /></div>' },
        ElEmpty: { template: '<div class="empty" />' },
      },
    },
  })
  await nextTick()

  expect(wrapper.get('.source-count').text()).toBe('1 个来源')
  expect(wrapper.findAll('.source-card')).toHaveLength(1)
  expect(wrapper.get('.source-card').attributes('id')).toBe('source-assistant-S1')
  expect(wrapper.get('.source-card blockquote').text()).toContain('前段')
  expect(wrapper.findAll('.source-locations li').map((item) => item.text())).toEqual([
    '规则 · 第 2 行',
    '规则 · 第 8 行',
  ])
  expect(wrapper.get('.source-card').classes()).toContain('highlighted')
  wrapper.unmount()
})
