import { request } from './http'
import type { PageResult } from './types'

/** 单次问答运行状态。 */
export type RagRunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED'

/** 问答流水线执行模式。 */
export type RagExecutionMode = 'FULL_PIPELINE' | 'SYSTEM_CHAT' | 'FAST_PATH'

/** 单个阶段执行状态。 */
export type RagStageStatus = 'SUCCESS' | 'DEGRADED' | 'FAILED' | 'CANCELLED' | 'SKIPPED'

/** 后端稳定的阶段名称。 */
export type RagStageName =
  | 'MEMORY_LOAD'
  | 'MEMORY_SUMMARY'
  | 'QUERY_PLANNING'
  | 'INTENT_ROUTING'
  | 'SUBQUESTION_EXECUTION'
  | 'EMBEDDING'
  | 'DATABASE_RETRIEVAL'
  | 'MCP_EXECUTION'
  | 'CANDIDATE_MERGE'
  | 'DEDUPLICATION'
  | 'RERANK'
  | 'PROMPT_ASSEMBLY'
  | 'ANSWER_MODEL'
  | 'CITATION_VALIDATION'
  | 'RESULT_PERSISTENCE'

/** 列表与详情共用的运行摘要，仅包含原始问题正文，不包含回答或证据正文。 */
export interface RagRunSummary {
  id: string
  ownerId?: string
  username?: string
  displayName?: string
  requestId: string
  conversationId?: string
  userMessageId?: string
  assistantMessageId?: string
  question?: string
  status: RagRunStatus
  executionMode: RagExecutionMode
  modelId?: string
  provider?: string
  model?: string
  candidateCount: number
  evidenceCount: number
  degraded: boolean
  errorCode?: string
  startedAt: string
  firstTokenAt?: string
  completedAt?: string
  totalMs?: number
  endToEndTtftMs?: number
  modelTtftMs?: number
}

/** 单个流水线阶段的安全观测信息。 */
export interface RagStageRun {
  id: string
  stageName: RagStageName
  subQuestionId?: string
  sequenceNo: number
  status: RagStageStatus
  inputCount?: number
  outputCount?: number
  modelId?: string
  provider?: string
  model?: string
  reasonCode?: string
  errorCode?: string
  startedAt: string
  firstTokenAt?: string
  completedAt: string
  elapsedMs: number
  ttftMs?: number
}

/** 单次运行及其阶段瀑布。 */
export interface RagRunDetail {
  run: RagRunSummary
  stages: RagStageRun[]
  degradationReasons: string[]
}

/** 一项耗时指标的 P50 与 P95。 */
export interface RagPercentiles {
  p50Ms: number | null
  p95Ms: number | null
}

/** 当前筛选范围的聚合观测指标。 */
export interface RagRunAggregate {
  requestCount: number
  successRate: number
  degradedRate: number
  totalMs: RagPercentiles
  endToEndTtftMs: RagPercentiles
  modelTtftMs: RagPercentiles
}

/** 列表与汇总接口共用的业务筛选条件。 */
export interface RagRunFilters {
  from?: string
  to?: string
  status?: RagRunStatus
  model?: string
  executionMode?: RagExecutionMode
  userId?: string
}

/** 查询运行列表。 */
export function listRagRuns(filters: RagRunFilters, page = 1, pageSize = 20) {
  return request<PageResult<RagRunSummary>>({
    url: '/observability/rag-runs',
    params: { ...filters, page, pageSize },
  })
}

/** 查询单次运行详情。 */
export function getRagRun(id: string) {
  return request<RagRunDetail>({ url: `/observability/rag-runs/${id}` })
}

/** 查询当前筛选范围的聚合指标。 */
export function summarizeRagRuns(filters: RagRunFilters) {
  return request<RagRunAggregate>({
    url: '/observability/rag-runs/summary',
    params: filters,
  })
}
