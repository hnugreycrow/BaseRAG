import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElSelect,
  ElMessage,
  ElMessageBox,
} from 'element-plus'
import CreateKnowledgeBaseDialog from '../../../components/knowledge-base/CreateKnowledgeBaseDialog.vue'
import { useKnowledgeBaseWorkspace } from '../../../components/knowledge-base/useKnowledgeBaseWorkspace'
import * as api from '../../../api'

vi.mock('../../../api', async () => ({
  ...(await vi.importActual<typeof import('../../../api')>('../../../api')),
  listKnowledgeBases: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10 }),
  listEmbeddingModels: vi.fn().mockResolvedValue([]),
  listDocuments: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10 }),
  getKnowledgeBase: vi.fn().mockResolvedValue({
    id: 'kb',
    name: '资料',
    embeddingModelId: 'embed-id',
    embeddingProvider: 'supplier',
    embeddingModel: 'embed',
    embeddingDimensions: 1024,
    documentCount: 1,
  }),
  uploadDocuments: vi.fn().mockResolvedValue({
    results: [
      {
        index: 0,
        fileName: 'guide.md',
        status: 'UPLOADED',
        documentId: 'doc',
        errorCode: null,
        message: null,
      },
    ],
  }),
  createDocumentChunkBatch: vi
    .fn()
    .mockResolvedValue({ acceptedDocumentIds: ['a', 'b'], skippedDocumentIds: [] }),
  getDocument: vi.fn().mockResolvedValue({
    id: 'c',
    name: 'c.md',
    status: 'READY',
    errorCode: null,
    chunkCount: 2,
    createdAt: '2026-09-15T00:00:00Z',
  }),
  listDocumentChunks: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10 }),
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
      embeddingModelId: 'embed-id',
      embeddingProvider: 'supplier',
      embeddingModel: 'embed',
      embeddingDimensions: 1024,
      documentCount: 0,
      createdAt: '2026-09-15T00:00:00Z',
    })
    const file = new File(['# 文档'], 'guide.md', { type: 'text/markdown' })
    await workspace.handleUpload([file])
    expect(api.uploadDocuments).toHaveBeenCalledWith('kb', [file])
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

  it('keeps the dialog open on partial upload and refreshes successful documents', async () => {
    vi.mocked(api.uploadDocuments).mockResolvedValue({
      results: [
        {
          index: 0,
          fileName: 'good.md',
          status: 'UPLOADED',
          documentId: 'good',
          errorCode: null,
          message: null,
        },
        {
          index: 1,
          fileName: 'bad.md',
          status: 'FAILED',
          documentId: null,
          errorCode: 'IMPORT_FAILED',
          message: '文档入库失败',
        },
      ],
    })
    const warning = vi.spyOn(ElMessage, 'warning')
    let workspace!: ReturnType<typeof useKnowledgeBaseWorkspace>
    const wrapper = mount(
      defineComponent({
        setup() {
          workspace = useKnowledgeBaseWorkspace()
          return () => null
        },
      }),
    )
    try {
      await flushPromises()
      await workspace.openKnowledgeBase({
        id: 'kb',
        name: '资料',
        embeddingModelId: 'embed-id',
        embeddingProvider: 'supplier',
        embeddingModel: 'embed',
        embeddingDimensions: 1024,
        documentCount: 0,
        createdAt: '2026-09-15T00:00:00Z',
      })
      workspace.uploadDialogOpen.value = true
      const files = [new File(['# good'], 'good.md'), new File(['# bad'], 'bad.md')]
      await workspace.handleUpload(files)
      expect(api.uploadDocuments).toHaveBeenCalledWith('kb', files)
      expect(workspace.uploadDialogOpen.value).toBe(true)
      expect(workspace.uploadResult.value?.results[1].errorCode).toBe('IMPORT_FAILED')
      expect(api.listDocuments).toHaveBeenCalledTimes(2)
      expect(api.getKnowledgeBase).toHaveBeenCalledWith('kb')
      expect(api.createDocumentChunks).not.toHaveBeenCalled()
      expect(warning).toHaveBeenCalledWith(expect.stringContaining('1 篇失败'))
    } finally {
      wrapper.unmount()
      warning.mockRestore()
    }
  })

  it('selects every status, confirms READY once, and reports accepted and skipped rows', async () => {
    const rows = [
      {
        id: 'a',
        name: 'a.md',
        status: 'UPLOADED',
        errorCode: null,
        chunkCount: 0,
        createdAt: '2026-09-15T00:00:00Z',
      },
      {
        id: 'b',
        name: 'b.md',
        status: 'FAILED',
        errorCode: 'MODEL_TIMEOUT',
        chunkCount: 0,
        createdAt: '2026-09-15T00:00:00Z',
      },
      {
        id: 'c',
        name: 'c.md',
        status: 'READY',
        errorCode: null,
        chunkCount: 2,
        createdAt: '2026-09-15T00:00:00Z',
      },
      {
        id: 'd',
        name: 'd.md',
        status: 'PROCESSING',
        errorCode: 'KEEP',
        chunkCount: 0,
        createdAt: '2026-09-15T00:00:00Z',
      },
    ] as const
    vi.mocked(api.listDocuments).mockResolvedValue({
      items: rows.map((row) => ({ ...row })),
      total: 4,
      totalPages: 1,
      page: 1,
      pageSize: 10,
    })
    vi.mocked(api.createDocumentChunkBatch).mockResolvedValue({
      acceptedDocumentIds: ['a', 'b', 'c'],
      skippedDocumentIds: ['d'],
    })
    const confirm = vi
      .spyOn(ElMessageBox, 'confirm')
      .mockResolvedValue('confirm' as unknown as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
    const success = vi.spyOn(ElMessage, 'success')
    const warning = vi.spyOn(ElMessage, 'warning')
    let workspace!: ReturnType<typeof useKnowledgeBaseWorkspace>
    const wrapper = mount(
      defineComponent({
        setup() {
          workspace = useKnowledgeBaseWorkspace()
          return () => null
        },
      }),
    )
    try {
      await flushPromises()
      await workspace.openKnowledgeBase({
        id: 'kb',
        name: '资料',
        embeddingModelId: 'embed-id',
        embeddingProvider: 'supplier',
        embeddingModel: 'embed',
        embeddingDimensions: 1024,
        documentCount: 4,
        createdAt: '2026-09-15T00:00:00Z',
      })
      workspace.setSelection(workspace.documents.value)
      expect([...workspace.selectedIds.value]).toEqual(['a', 'b', 'c', 'd'])
      workspace.page.value = 2
      await flushPromises()
      expect(workspace.selectedCount.value).toBe(0)
      workspace.page.value = 1
      await flushPromises()
      workspace.setSelection(workspace.documents.value)
      await workspace.handleBatchChunk()
      expect(confirm).toHaveBeenCalledTimes(1)
      expect(api.createDocumentChunkBatch).toHaveBeenCalledExactlyOnceWith('kb', [
        'a',
        'b',
        'c',
        'd',
      ])
      expect(workspace.selectedCount.value).toBe(0)
      expect(
        workspace.documents.value.filter((row) => row.status === 'PROCESSING').map((row) => row.id),
      ).toEqual(['a', 'b', 'c', 'd'])
      expect(workspace.documents.value.find((row) => row.id === 'd')?.errorCode).toBe('KEEP')
      expect(success).toHaveBeenCalledWith(expect.stringContaining('3 篇'))
      expect(warning).toHaveBeenCalledWith(expect.stringContaining('1 篇'))
    } finally {
      wrapper.unmount()
      confirm.mockRestore()
      success.mockRestore()
      warning.mockRestore()
    }
  })

  it('reports an all-processing batch without submitting new tasks', async () => {
    vi.mocked(api.listDocuments).mockResolvedValue({
      items: [
        {
          id: 'd',
          name: 'd.md',
          status: 'PROCESSING',
          errorCode: null,
          chunkCount: 0,
          createdAt: '2026-09-15T00:00:00Z',
        },
      ],
      total: 1,
      totalPages: 1,
      page: 1,
      pageSize: 10,
    })
    vi.mocked(api.createDocumentChunkBatch).mockResolvedValue({
      acceptedDocumentIds: [],
      skippedDocumentIds: ['d'],
    })
    const warning = vi.spyOn(ElMessage, 'warning')
    let workspace!: ReturnType<typeof useKnowledgeBaseWorkspace>
    const wrapper = mount(
      defineComponent({
        setup() {
          workspace = useKnowledgeBaseWorkspace()
          return () => null
        },
      }),
    )
    try {
      await flushPromises()
      await workspace.openKnowledgeBase({
        id: 'kb',
        name: '资料',
        embeddingModelId: 'embed-id',
        embeddingProvider: 'supplier',
        embeddingModel: 'embed',
        embeddingDimensions: 1024,
        documentCount: 1,
        createdAt: '2026-09-15T00:00:00Z',
      })
      workspace.toggleSelection('d')
      await workspace.handleBatchChunk()
      expect(api.createDocumentChunkBatch).toHaveBeenCalledWith('kb', ['d'])
      expect(workspace.selectedCount.value).toBe(0)
      expect(warning).toHaveBeenCalledWith(expect.stringContaining('1 篇'))
    } finally {
      wrapper.unmount()
      warning.mockRestore()
    }
  })

  it('refreshes selected document status on the chunk page', async () => {
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
    workspace.selectedKnowledgeBase.value = {
      id: 'kb',
      name: '资料',
      embeddingModelId: 'embed-id',
      embeddingProvider: 'supplier',
      embeddingModel: 'embed',
      embeddingDimensions: 1024,
      documentCount: 1,
      createdAt: '2026-09-15T00:00:00Z',
    }
    workspace.selectedDocument.value = {
      id: 'c',
      name: 'c.md',
      status: 'PROCESSING',
      errorCode: null,
      chunkCount: 2,
      createdAt: '2026-09-15T00:00:00Z',
    }
    await workspace.refreshCurrent()
    expect(api.getDocument).toHaveBeenCalledWith('kb', 'c')
    expect(workspace.selectedDocument.value?.status).toBe('READY')
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
