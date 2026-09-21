import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import type { KnowledgeDocument } from '../../../api'
import DocumentTable from '../../../components/knowledge-base/DocumentTable.vue'

const rows: KnowledgeDocument[] = (['UPLOADED', 'FAILED', 'READY', 'PROCESSING'] as const).map(
  (status, index) => ({
    id: String(index),
    name: `${status}.md`,
    status,
    errorCode: null,
    chunkCount: status === 'READY' ? 1 : 0,
    createdAt: '2026-09-15T00:00:00Z',
  }),
)

describe('document table selection', () => {
  it('renders native select-all for every status and forwards table selection', async () => {
    const wrapper = mount(DocumentTable, {
      props: {
        rows,
        loading: false,
        processingIds: new Set<string>(),
        selectedIds: new Set<string>(),
      },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    try {
      await flushPromises()
      expect(wrapper.findAll('.el-table__body .el-checkbox')).toHaveLength(4)
      expect(wrapper.find('.el-table__header .el-checkbox').exists()).toBe(true)
      wrapper.findComponent({ name: 'ElTable' }).vm.$emit('selection-change', rows)
      await flushPromises()
      expect(wrapper.emitted('selectionChange')?.at(-1)?.[0]).toEqual(rows)
    } finally {
      wrapper.unmount()
    }
  })

  it('previews every document status without opening chunk management', async () => {
    const wrapper = mount(DocumentTable, {
      props: {
        rows,
        loading: false,
        processingIds: new Set<string>(),
        selectedIds: new Set<string>(),
      },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    try {
      await flushPromises()
      const previewButtons = wrapper
        .findAll('.desktop-resource-table .el-button')
        .filter((button) => button.text() === '预览')
      expect(previewButtons).toHaveLength(4)
      expect(
        wrapper
          .findAll('.mobile-resource-list .el-button')
          .filter((button) => button.text() === '预览'),
      ).toHaveLength(4)

      await previewButtons[0]!.trigger('click')

      expect(wrapper.emitted('preview')?.[0]?.[0]).toEqual(rows[0])
      expect(wrapper.emitted('open')).toBeUndefined()
    } finally {
      wrapper.unmount()
    }
  })

  it('shows the safe failure reason before the diagnostic error code', async () => {
    const failedRows: KnowledgeDocument[] = [
      {
        ...rows[1]!,
        errorCode: 'MODEL_TIMEOUT',
        errorMessage: '模型请求超时，请稍后重试',
      },
    ]
    const wrapper = mount(DocumentTable, {
      props: {
        rows: failedRows,
        loading: false,
        processingIds: new Set<string>(),
        selectedIds: new Set<string>(),
      },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    try {
      await flushPromises()
      const text = wrapper.text()
      expect(text).toContain('模型请求超时，请稍后重试')
      expect(text).toContain('MODEL_TIMEOUT')
      expect(text.indexOf('模型请求超时，请稍后重试')).toBeLessThan(text.indexOf('MODEL_TIMEOUT'))
    } finally {
      wrapper.unmount()
    }
  })
})
