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
  embeddingModel: string
  embeddingDimensions: number
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

export function uploadDocument(knowledgeBaseId: string, file: File) {
  const data = new FormData()
  data.append('file', file)

  return request<DocumentImportResult>({
    url: `/knowledge-bases/${knowledgeBaseId}/documents`,
    method: 'post',
    data,
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
    timeout: 0,
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
