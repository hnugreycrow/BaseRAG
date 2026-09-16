import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'

import {
  createDocumentChunks,
  createDocumentChunkBatch,
  createKnowledgeBase,
  deleteDocument,
  deleteKnowledgeBase,
  getDocumentChunk,
  getDocument,
  getErrorMessage,
  getKnowledgeBase,
  listEmbeddingModels,
  renameDocument,
  renameKnowledgeBase,
  uploadDocuments,
  type DocumentBatchUploadResult,
  type DocumentChunk,
  type DocumentChunkDetail,
  type EmbeddingModel,
  type KnowledgeBase,
  type KnowledgeDocument,
} from '../../api'
import { useKnowledgeBasePaging } from './useKnowledgeBasePaging'

type RenameTarget =
  { kind: 'knowledge-base'; value: KnowledgeBase } | { kind: 'document'; value: KnowledgeDocument }

export function useKnowledgeBaseWorkspace() {
  const paging = useKnowledgeBasePaging()
  const {
    chunks,
    documents,
    knowledgeBases,
    level,
    loadChunks,
    loadCurrent,
    loadDocuments,
    loadKnowledgeBases,
    page,
    resetTable,
    selectedDocument,
    selectedKnowledgeBase,
  } = paging
  const models = ref<EmbeddingModel[]>([])
  const selectedChunk = ref<DocumentChunk | null>(null)
  const chunkDetail = ref<DocumentChunkDetail | null>(null)
  const saving = ref(false)
  const uploading = ref(false)
  const uploadResult = ref<DocumentBatchUploadResult | null>(null)
  const detailLoading = ref(false)
  const processingIds = ref(new Set<string>())
  const selectedIds = ref(new Set<string>())
  const batchSubmitting = ref(false)
  const selectedCount = computed(() => selectedIds.value.size)
  watch([page, paging.pageSize, paging.query, selectedKnowledgeBase], () => {
    selectedIds.value = new Set()
  })

  function toggleSelection(id: string) {
    const row = documents.value.find((item) => item.id === id)
    if (!row) return
    const next = new Set(selectedIds.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    selectedIds.value = next
  }

  function setSelection(rows: KnowledgeDocument[]) {
    selectedIds.value = new Set(rows.map((row) => row.id))
  }

  const createDialogOpen = ref(false)
  const uploadDialogOpen = ref(false)
  watch(uploadDialogOpen, (open) => {
    if (open) uploadResult.value = null
  })
  const renameDialogOpen = ref(false)
  const detailDrawerOpen = ref(false)
  const renameTarget = ref<RenameTarget | null>(null)
  const renameTitle = computed(() =>
    renameTarget.value?.kind === 'document' ? '重命名文档' : '重命名知识库',
  )
  const renameMaxLength = computed(() => (renameTarget.value?.kind === 'document' ? 255 : 200))

  async function openKnowledgeBase(row: KnowledgeBase) {
    selectedKnowledgeBase.value = row
    selectedDocument.value = null
    documents.value = []
    chunks.value = []
    resetTable()
    await loadDocuments()
  }

  async function openDocument(row: KnowledgeDocument) {
    if (!selectedKnowledgeBase.value) return
    selectedDocument.value = row
    chunks.value = []
    resetTable()
    await loadChunks()
  }

  async function goToKnowledgeBases() {
    selectedKnowledgeBase.value = null
    selectedDocument.value = null
    resetTable()
    await loadKnowledgeBases()
  }

  async function goToDocuments() {
    selectedDocument.value = null
    chunks.value = []
    resetTable()
    await loadDocuments()
  }

  async function handleCreate(payload: { name: string; embeddingModelId: string }) {
    saving.value = true
    try {
      await createKnowledgeBase(payload.name, payload.embeddingModelId)
      createDialogOpen.value = false
      resetTable()
      await loadKnowledgeBases(false)
      ElMessage.success('知识库已创建')
    } catch (error) {
      ElMessage.error(getErrorMessage(error))
    } finally {
      saving.value = false
    }
  }

  function openRename(target: RenameTarget) {
    renameTarget.value = target
    renameDialogOpen.value = true
  }

  async function handleRename(name: string) {
    if (!renameTarget.value) return
    saving.value = true
    try {
      if (renameTarget.value.kind === 'knowledge-base') {
        const updated = await renameKnowledgeBase(renameTarget.value.value.id, name)
        knowledgeBases.value = knowledgeBases.value.map((item) =>
          item.id === updated.id ? updated : item,
        )
      } else if (selectedKnowledgeBase.value) {
        const updated = await renameDocument(
          selectedKnowledgeBase.value.id,
          renameTarget.value.value.id,
          name,
        )
        documents.value = documents.value.map((item) => (item.id === updated.id ? updated : item))
        if (selectedDocument.value?.id === updated.id) selectedDocument.value = updated
      }
      renameDialogOpen.value = false
      ElMessage.success('名称已更新')
    } catch (error) {
      ElMessage.error(getErrorMessage(error))
    } finally {
      saving.value = false
    }
  }

  async function reloadAfterDelete() {
    await loadCurrent(false)
    const rows = level.value === 'documents' ? documents.value : knowledgeBases.value
    if (page.value > 1 && rows.length === 0) {
      page.value -= 1
      await loadCurrent(false)
    }
  }

  async function handleRemoveKnowledgeBase(row: KnowledgeBase) {
    try {
      await ElMessageBox.confirm(
        `删除“${row.name}”后，其中的 ${row.documentCount} 份文档和全部分块也会被删除。`,
        '删除知识库',
        { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
      )
      await deleteKnowledgeBase(row.id)
      await reloadAfterDelete()
      ElMessage.success('知识库已删除')
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
      ElMessage.error(getErrorMessage(error))
    }
  }

  async function refreshSelectedKnowledgeBase() {
    if (selectedKnowledgeBase.value) {
      selectedKnowledgeBase.value = await getKnowledgeBase(selectedKnowledgeBase.value.id)
    }
  }

  async function handleRemoveDocument(row: KnowledgeDocument) {
    if (!selectedKnowledgeBase.value) return
    try {
      await ElMessageBox.confirm(`“${row.name}”及其全部分块将被永久删除。`, '删除文档', {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning',
      })
      await deleteDocument(selectedKnowledgeBase.value.id, row.id)
      await Promise.all([reloadAfterDelete(), refreshSelectedKnowledgeBase()])
      ElMessage.success('文档已删除')
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
      ElMessage.error(getErrorMessage(error))
    }
  }

  async function handleUpload(files: File[]) {
    if (!selectedKnowledgeBase.value || files.length === 0) return
    uploading.value = true
    uploadResult.value = null
    try {
      const result = await uploadDocuments(selectedKnowledgeBase.value.id, files)
      uploadResult.value = result
      const succeeded = result.results.filter((item) => item.status === 'UPLOADED').length
      const failed = result.results.length - succeeded
      if (succeeded > 0) {
        resetTable()
        await Promise.all([loadDocuments(false), refreshSelectedKnowledgeBase()])
      }
      if (failed === 0) {
        uploadDialogOpen.value = false
        ElMessage.success(`已上传 ${succeeded} 篇文档，等待分块`)
      } else if (succeeded > 0) {
        ElMessage.warning(`已上传 ${succeeded} 篇，${failed} 篇失败，请查看原因后重试`)
      } else {
        ElMessage.warning(`${failed} 篇上传失败，请查看原因后重试`)
      }
    } catch (error) {
      ElMessage.error(getErrorMessage(error))
    } finally {
      uploading.value = false
    }
  }

  function markProcessing(id: string, processing: boolean) {
    const next = new Set(processingIds.value)
    if (processing) next.add(id)
    else next.delete(id)
    processingIds.value = next
  }

  async function handleChunk(row: KnowledgeDocument) {
    if (!selectedKnowledgeBase.value) return
    const managingChunks = level.value === 'chunks'
    if (row.status === 'READY') {
      try {
        await ElMessageBox.confirm(
          '重新分块完成后会替换当前分块及其向量，处理期间旧分块仍可检索。',
          '重新分块',
          { confirmButtonText: '继续分块', cancelButtonText: '取消', type: 'warning' },
        )
      } catch {
        return
      }
    }
    markProcessing(row.id, true)
    try {
      await createDocumentChunks(selectedKnowledgeBase.value.id, row.id)
      selectedIds.value = new Set([...selectedIds.value].filter((id) => id !== row.id))
      if (managingChunks) {
        selectedDocument.value = { ...row, status: 'PROCESSING' }
      } else {
        documents.value = documents.value.map((item) =>
          item.id === row.id ? { ...item, status: 'PROCESSING', errorCode: null } : item,
        )
      }
      ElMessage.success('分块任务已提交，请刷新查看结果')
    } catch (error) {
      ElMessage.error(getErrorMessage(error))
      if (!managingChunks) await loadDocuments(false)
    } finally {
      markProcessing(row.id, false)
    }
  }

  async function handleBatchChunk() {
    if (!selectedKnowledgeBase.value || selectedIds.value.size === 0) return
    const selected = documents.value.filter((row) => selectedIds.value.has(row.id))
    const ids = selected.map((row) => row.id)
    if (ids.length === 0) return
    if (selected.some((row) => row.status === 'READY')) {
      try {
        await ElMessageBox.confirm(
          '已完成文档会在后台重新分块；新分块生成后将替换旧分块，处理期间旧分块仍可检索。',
          '批量重新分块',
          { confirmButtonText: '提交任务', cancelButtonText: '取消', type: 'warning' },
        )
      } catch {
        return
      }
    }
    batchSubmitting.value = true
    try {
      const result = await createDocumentChunkBatch(selectedKnowledgeBase.value.id, ids)
      const accepted = new Set(result.acceptedDocumentIds)
      documents.value = documents.value.map((row) =>
        accepted.has(row.id) ? { ...row, status: 'PROCESSING', errorCode: null } : row,
      )
      selectedIds.value = new Set()
      if (result.acceptedDocumentIds.length > 0)
        ElMessage.success(`已提交 ${result.acceptedDocumentIds.length} 篇文档，请刷新查看结果`)
      if (result.skippedDocumentIds.length > 0)
        ElMessage.warning(`跳过 ${result.skippedDocumentIds.length} 篇正在处理的文档`)
    } catch (error) {
      ElMessage.error(getErrorMessage(error))
      await loadDocuments(false)
    } finally {
      batchSubmitting.value = false
    }
  }

  async function openChunk(row: DocumentChunk) {
    if (!selectedKnowledgeBase.value || !selectedDocument.value) return
    selectedChunk.value = row
    chunkDetail.value = null
    detailDrawerOpen.value = true
    detailLoading.value = true
    try {
      chunkDetail.value = await getDocumentChunk(
        selectedKnowledgeBase.value.id,
        selectedDocument.value.id,
        row.id,
      )
    } catch (error) {
      ElMessage.error(getErrorMessage(error))
    } finally {
      detailLoading.value = false
    }
  }

  onMounted(() => {
    void listEmbeddingModels()
      .then((items) => (models.value = items))
      .catch((error) => ElMessage.error(getErrorMessage(error)))
  })

  return {
    ...paging,
    chunkDetail,
    batchSubmitting,
    createDialogOpen,
    detailDrawerOpen,
    detailLoading,
    goToDocuments,
    goToKnowledgeBases,
    handleChunk,
    handleBatchChunk,
    handleCreate,
    handleRemoveDocument,
    handleRemoveKnowledgeBase,
    handleRename,
    handleUpload,
    models,
    openChunk,
    openDocument,
    openKnowledgeBase,
    openRename,
    processingIds,
    selectedIds,
    selectedCount,
    toggleSelection,
    setSelection,
    refreshCurrent: async () => {
      selectedIds.value = new Set()
      if (selectedKnowledgeBase.value && selectedDocument.value) {
        try {
          selectedDocument.value = await getDocument(
            selectedKnowledgeBase.value.id,
            selectedDocument.value.id,
          )
        } catch (error) {
          ElMessage.error(getErrorMessage(error))
        }
      }
      await loadCurrent()
    },
    renameDialogOpen,
    renameMaxLength,
    renameTarget,
    renameTitle,
    saving,
    selectedChunk,
    uploading,
    uploadResult,
    uploadDialogOpen,
  }
}
