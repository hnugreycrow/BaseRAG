import { request } from './http'
import type { PageResult } from './types'

export type { PageResult } from './types'

export interface EmbeddingModel {
  id: string
  provider: string
  model: string
  dimensions: number
  defaultModel: boolean
}

export interface KnowledgeBase {
  id: string
  name: string
  embeddingModelId: string | null
  embeddingProvider: string | null
  embeddingModel: string | null
  embeddingDimensions: number | null
  documentCount: number
  createdAt: string
}

export type DocumentStatus = 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED'

export interface KnowledgeDocument {
  id: string
  name: string
  status: DocumentStatus
  errorCode: string | null
  chunkCount: number
  createdAt: string
}

export interface DocumentChunk {
  id: string
  chunkIndex: number
  heading: string | null
  lineStart: number
  lineEnd: number
  characterCount: number
  preview: string
}

export interface DocumentChunkDetail extends DocumentChunk {
  documentId: string
  versionId: string
  content: string
}

export interface DocumentImportResult {
  documentId: string
  status: DocumentStatus
  chunkCount: number
}

export interface DocumentBatchUploadItem {
  index: number
  fileName: string
  status: 'UPLOADED' | 'FAILED'
  documentId: string | null
  errorCode: string | null
  message: string | null
}

export interface DocumentBatchUploadResult {
  results: DocumentBatchUploadItem[]
}

function pageParams(page: number, pageSize: number, query = '') {
  return { page, pageSize, ...(query.trim() ? { query: query.trim() } : {}) }
}

export function listKnowledgeBases(page: number, pageSize: number, query = '') {
  return request<PageResult<KnowledgeBase>>({
    url: '/knowledge-bases',
    params: pageParams(page, pageSize, query),
  })
}

export function getKnowledgeBase(id: string) {
  return request<KnowledgeBase>({ url: `/knowledge-bases/${id}` })
}

export function listEmbeddingModels() {
  return request<EmbeddingModel[]>({ url: '/knowledge-bases/embedding-models' })
}

export function createKnowledgeBase(name: string, embeddingModelId: string) {
  return request<KnowledgeBase>({
    url: '/knowledge-bases',
    method: 'post',
    data: { name, embeddingModelId },
  })
}

export function renameKnowledgeBase(id: string, name: string) {
  return request<KnowledgeBase>({
    url: `/knowledge-bases/${id}`,
    method: 'patch',
    data: { name },
  })
}

export function deleteKnowledgeBase(id: string) {
  return request<void>({ url: `/knowledge-bases/${id}`, method: 'delete' })
}

export function listDocuments(knowledgeBaseId: string, page: number, pageSize: number, query = '') {
  return request<PageResult<KnowledgeDocument>>({
    url: `/knowledge-bases/${knowledgeBaseId}/documents`,
    params: pageParams(page, pageSize, query),
  })
}

export function getDocument(knowledgeBaseId: string, documentId: string) {
  return request<KnowledgeDocument>({
    url: `/knowledge-bases/${knowledgeBaseId}/documents/${documentId}`,
  })
}

export function uploadDocument(knowledgeBaseId: string, file: File) {
  const data = new FormData()
  data.append('file', file)

  return request<DocumentImportResult>({
    url: `/knowledge-bases/${knowledgeBaseId}/documents`,
    method: 'post',
    data,
  })
}

export function uploadDocuments(knowledgeBaseId: string, files: File[]) {
  const data = new FormData()
  files.forEach((file) => data.append('files', file))

  return request<DocumentBatchUploadResult>({
    url: `/knowledge-bases/${knowledgeBaseId}/documents/batch`,
    method: 'post',
    data,
    timeout: 120_000,
  })
}

export function renameDocument(knowledgeBaseId: string, documentId: string, name: string) {
  return request<KnowledgeDocument>({
    url: `/knowledge-bases/${knowledgeBaseId}/documents/${documentId}`,
    method: 'patch',
    data: { name },
  })
}

export function deleteDocument(knowledgeBaseId: string, documentId: string) {
  return request<void>({
    url: `/knowledge-bases/${knowledgeBaseId}/documents/${documentId}`,
    method: 'delete',
  })
}

export function createDocumentChunks(knowledgeBaseId: string, documentId: string) {
  return request<DocumentImportResult>({
    url: `/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/chunks`,
    method: 'post',
  })
}

export function createDocumentChunkBatch(knowledgeBaseId: string, documentIds: string[]) {
  return request<{ acceptedDocumentIds: string[]; skippedDocumentIds: string[] }>({
    url: `/knowledge-bases/${knowledgeBaseId}/documents/chunk-jobs`,
    method: 'post',
    data: { documentIds },
  })
}

export function listDocumentChunks(
  knowledgeBaseId: string,
  documentId: string,
  page: number,
  pageSize: number,
  query = '',
) {
  return request<PageResult<DocumentChunk>>({
    url: `/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/chunks`,
    params: pageParams(page, pageSize, query),
  })
}

export function getDocumentChunk(knowledgeBaseId: string, documentId: string, chunkId: string) {
  return request<DocumentChunkDetail>({
    url: `/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/chunks/${chunkId}`,
  })
}
