import { request } from './http'

export interface ConfiguredModel {
  id: string
  provider: string
  model: string
  defaultModel: boolean
  timeoutMs: number
  dimensions: number
  supportsThinking: boolean
  credentialConfigured: boolean
  localFallback: boolean
}

export interface ModelSettings {
  chatTier: string
  chat: ConfiguredModel[]
  embedding: ConfiguredModel[]
  rerank: ConfiguredModel[]
  maxRetries: number
  failureThreshold: number
  openDurationMs: number
  embeddingBatchSize: number
}

export function getModelSettings() {
  return request<ModelSettings>({ url: '/admin/settings/models', method: 'GET' })
}

export interface RetrievalSettings {
  recallBudget: number
  vectorEnabled: boolean
  channelTimeoutMs: number
  fusionStrategy: string
  rrfK: number
  vectorWeight: number
  deduplicationOverlapThreshold: number
  rerankEnabled: boolean
  rerankInputLimit: number
  selectedEvidenceLimit: number
  maxSubQuestions: number
  planningRecentTurns: number
  routingConfidenceThreshold: number
  routingTimeoutMs: number
  maxQuestionChars: number
  recentTurns: number
  summaryBatchTurns: number
  summaryMaxChars: number
}

export function getRetrievalSettings() {
  return request<RetrievalSettings>({ url: '/admin/settings/retrieval', method: 'GET' })
}
