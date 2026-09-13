import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'

import {
  createDocumentChunks,
  createKnowledgeBase,
  deleteDocument,
  deleteKnowledgeBase,
  getDocumentChunk,
  getErrorMessage,
  getKnowledgeBase,
  listEmbeddingModels,
  renameDocument,
  renameKnowledgeBase,
  uploadDocument,
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
  const detailLoading = ref(false)
  const processingIds = ref(new Set<string>())
  const createDialogOpen = ref(false)
  const uploadDialogOpen = ref(false)
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

  async function handleUpload(file: File) {
    if (!selectedKnowledgeBase.value) return
    uploading.value = true
    try {
      await uploadDocument(selectedKnowledgeBase.value.id, file)
      uploadDialogOpen.value = false
      resetTable()
      await Promise.all([loadDocuments(false), refreshSelectedKnowledgeBase()])
      ElMessage.success('文档已上传，等待分块')
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
          '重新分块会替换当前分块及其向量，完成前可能影响检索。',
          '重新分块',
          { confirmButtonText: '继续分块', cancelButtonText: '取消', type: 'warning' },
        )
      } catch {
        return
      }
    }
    markProcessing(row.id, true)
    try {
      const result = await createDocumentChunks(selectedKnowledgeBase.value.id, row.id)
      if (managingChunks) {
        selectedDocument.value = { ...row, status: 'READY', chunkCount: result.chunkCount }
        await loadChunks(false)
      } else {
        await loadDocuments(false)
      }
      ElMessage.success(`分块完成，共生成 ${result.chunkCount} 个分块`)
    } catch (error) {
      ElMessage.error(getErrorMessage(error))
      if (!managingChunks) await loadDocuments(false)
    } finally {
      markProcessing(row.id, false)
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
    createDialogOpen,
    detailDrawerOpen,
    detailLoading,
    goToDocuments,
    goToKnowledgeBases,
    handleChunk,
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
    refreshCurrent: () => loadCurrent(),
    renameDialogOpen,
    renameMaxLength,
    renameTarget,
    renameTitle,
    saving,
    selectedChunk,
    uploading,
    uploadDialogOpen,
  }
}
