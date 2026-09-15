import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElOption, ElSelect } from 'element-plus'
import CreateKnowledgeBaseDialog from './CreateKnowledgeBaseDialog.vue'
import { useKnowledgeBaseWorkspace } from './useKnowledgeBaseWorkspace'
import * as api from '../../api'

vi.mock('../../api', async () => ({
  ...(await vi.importActual<typeof import('../../api')>('../../api')),
  listKnowledgeBases: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10 }),
  listEmbeddingModels: vi.fn().mockResolvedValue([]),
  listDocuments: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10 }),
  getKnowledgeBase: vi.fn().mockResolvedValue({
    id: 'kb',
    name: '资料',
    embeddingModel: 'embed',
    embeddingDimensions: 1024,
    documentCount: 1,
  }),
  uploadDocument: vi
    .fn()
    .mockResolvedValue({ documentId: 'doc', status: 'UPLOADED', chunkCount: 0 }),
  createDocumentChunks: vi
    .fn()
    .mockResolvedValue({ documentId: 'doc', status: 'READY', chunkCount: 3 }),
}))

describe('real knowledge base workflow', () => {
  beforeEach(() => vi.clearAllMocks())
  it('uploads without chunking and only chunks on explicit request', async () => {
    let workspace!: ReturnType<typeof useKnowledgeBaseWorkspace>
    const wrapper = mount(
      defineComponent({
        setup() {
          workspace = useKnowledgeBaseWorkspace()
          return () => null
        },
      }),
    )
    await flushPromises()
    await workspace.openKnowledgeBase({
      id: 'kb',
      name: '资料',
      embeddingModel: 'embed',
      embeddingDimensions: 1024,
      documentCount: 0,
      createdAt: '2026-09-15T00:00:00Z',
    })
    const file = new File(['# 文档'], 'guide.md', { type: 'text/markdown' })
    await workspace.handleUpload(file)
    expect(api.uploadDocument).toHaveBeenCalledWith('kb', file)
    expect(api.createDocumentChunks).not.toHaveBeenCalled()
    await workspace.handleChunk({
      id: 'doc',
      name: 'guide.md',
      status: 'UPLOADED',
      errorCode: null,
      chunkCount: 0,
      createdAt: '2026-09-15T00:00:00Z',
    })
    expect(api.createDocumentChunks).toHaveBeenCalledExactlyOnceWith('kb', 'doc')
    expect(workspace.processingIds.value.size).toBe(0)
    wrapper.unmount()
  })

  it('requires a real model ID and accepts asynchronously loaded model choices', async () => {
    const wrapper = mount(CreateKnowledgeBaseDialog, {
      props: { modelValue: false, models: [], loading: false },
      global: {
        plugins: [ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElOption, ElSelect],
        stubs: { teleport: true, ElSelect: true, ElOption: true },
      },
    })
    await wrapper.setProps({ modelValue: true })
    await wrapper.findComponent(ElInput).setValue('手册')
    const model = {
      id: 'embedding-1024',
      provider: 'local',
      model: 'bge-m3',
      dimensions: 1024,
      defaultModel: true,
    }
    await wrapper.setProps({ models: [model] })
    expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('手册')
    expect(wrapper.findComponent(ElSelect).props('modelValue')).toBe(model.id)
    const submit = wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text().includes('创建知识库'))!
    await submit.trigger('click')
    expect(wrapper.emitted('submit')?.[0]).toEqual([{ name: '手册', embeddingModelId: model.id }])
    await wrapper.setProps({ models: [] })
    await submit.trigger('click')
    expect(wrapper.emitted('submit')).toHaveLength(1)
    wrapper.unmount()
  })
})
