import { ElMessage } from 'element-plus'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

import {
  getErrorMessage,
  listDocumentChunks,
  listDocuments,
  listKnowledgeBases,
  type DocumentChunk,
  type KnowledgeBase,
  type KnowledgeDocument,
  type PageResult,
} from '../../api'

export type ResourceLevel = 'knowledge-bases' | 'documents' | 'chunks'

export function useKnowledgeBasePaging() {
  const knowledgeBases = ref<KnowledgeBase[]>([])
  const documents = ref<KnowledgeDocument[]>([])
  const chunks = ref<DocumentChunk[]>([])
  const selectedKnowledgeBase = ref<KnowledgeBase | null>(null)
  const selectedDocument = ref<KnowledgeDocument | null>(null)
  const query = ref('')
  const page = ref(1)
  const pageSize = ref(10)
  const total = ref(0)
  const loading = ref(false)
  let loadSequence = 0
  let searchTimer: number | undefined
  let ignoreNextQueryChange = false

  const level = computed<ResourceLevel>(() => {
    if (selectedDocument.value) return 'chunks'
    if (selectedKnowledgeBase.value) return 'documents'
    return 'knowledge-bases'
  })
  const pageTitle = computed(
    () => selectedDocument.value?.name ?? selectedKnowledgeBase.value?.name ?? '知识库',
  )
  const currentCount = computed(() => {
    if (level.value === 'chunks') return `${total.value} 个分块`
    if (level.value === 'documents') return `${total.value} 份文档`
    return `${total.value} 个知识库`
  })
  const searchPlaceholder = computed(() => {
    if (level.value === 'chunks') return '搜索标题或内容'
    if (level.value === 'documents') return '搜索文档'
    return '搜索知识库或模型'
  })

  function applyPage<T>(result: PageResult<T>) {
    total.value = result.total
    page.value = result.page
    pageSize.value = result.pageSize
    return result.items
  }

  async function loadKnowledgeBases(showLoading = true) {
    const sequence = ++loadSequence
    if (showLoading) loading.value = true
    try {
      const result = await listKnowledgeBases(page.value, pageSize.value, query.value)
      if (sequence === loadSequence) knowledgeBases.value = applyPage(result)
    } catch (error) {
      if (sequence === loadSequence) ElMessage.error(getErrorMessage(error))
    } finally {
      if (showLoading && sequence === loadSequence) loading.value = false
    }
  }

  async function loadDocuments(showLoading = true) {
    if (!selectedKnowledgeBase.value) return
    const sequence = ++loadSequence
    if (showLoading) loading.value = true
    try {
      const result = await listDocuments(
        selectedKnowledgeBase.value.id,
        page.value,
        pageSize.value,
        query.value,
      )
      if (sequence === loadSequence) documents.value = applyPage(result)
    } catch (error) {
      if (sequence === loadSequence) ElMessage.error(getErrorMessage(error))
    } finally {
      if (showLoading && sequence === loadSequence) loading.value = false
    }
  }

  async function loadChunks(showLoading = true) {
    if (!selectedKnowledgeBase.value || !selectedDocument.value) return
    const sequence = ++loadSequence
    if (showLoading) loading.value = true
    try {
      const result = await listDocumentChunks(
        selectedKnowledgeBase.value.id,
        selectedDocument.value.id,
        page.value,
        pageSize.value,
        query.value,
      )
      if (sequence === loadSequence) chunks.value = applyPage(result)
    } catch (error) {
      if (sequence === loadSequence) ElMessage.error(getErrorMessage(error))
    } finally {
      if (showLoading && sequence === loadSequence) loading.value = false
    }
  }

  function loadCurrent(showLoading = true) {
    if (level.value === 'chunks') return loadChunks(showLoading)
    if (level.value === 'documents') return loadDocuments(showLoading)
    return loadKnowledgeBases(showLoading)
  }

  function resetTable() {
    page.value = 1
    total.value = 0
    if (query.value) {
      ignoreNextQueryChange = true
      query.value = ''
    }
  }

  function handlePageChange(nextPage: number) {
    page.value = nextPage
    void loadCurrent()
  }

  function handlePageSizeChange(nextSize: number) {
    pageSize.value = nextSize
    page.value = 1
    void loadCurrent()
  }

  watch(
    query,
    () => {
      if (ignoreNextQueryChange) {
        ignoreNextQueryChange = false
        return
      }
      window.clearTimeout(searchTimer)
      searchTimer = window.setTimeout(() => {
        page.value = 1
        void loadCurrent()
      }, 320)
    },
    { flush: 'sync' },
  )

  onMounted(() => void loadKnowledgeBases())
  onUnmounted(() => window.clearTimeout(searchTimer))

  return {
    chunks,
    currentCount,
    documents,
    handlePageChange,
    handlePageSizeChange,
    knowledgeBases,
    level,
    loadChunks,
    loadCurrent,
    loadDocuments,
    loadKnowledgeBases,
    loading,
    page,
    pageSize,
    pageTitle,
    query,
    resetTable,
    searchPlaceholder,
    selectedDocument,
    selectedKnowledgeBase,
    total,
  }
}
