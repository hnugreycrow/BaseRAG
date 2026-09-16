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
})
