import { ApiRequestError, request } from './http'

export interface ConversationSummary {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface ModelInfo {
  id: string
  provider: string
  model: string
}

export interface AnswerSource {
  citationId: string
  knowledgeBaseId: string
  knowledgeBaseName: string
  chunkId: string
  documentId: string
  versionId: string
  documentName: string
  heading: string | null
  lineStart: number
  lineEnd: number
  similarity: number
  content: string
}

export type AssistantStatus = 'PENDING' | 'STREAMING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface UserMessage {
  id: string
  turnIndex: number
  content: string
  createdAt: string
}

export interface AssistantMessage {
  id: string
  replyToId: string
  turnIndex: number
  variantIndex: number
  active: boolean
  status: AssistantStatus
  content: string
  retrievalQuery: string | null
  sources: AnswerSource[]
  citations: string[]
  modelInfo: ModelInfo | null
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
  completedAt: string | null
}

export interface ConversationTurn {
  user: UserMessage
  assistantVersions: AssistantMessage[]
  activeAssistantId: string | null
}

export interface ConversationDetail extends ConversationSummary {
  turns: ConversationTurn[]
}

interface StartedEvent {
  schemaVersion: number
  conversationId: string
  userMessageId: string
  assistantMessageId: string
  generationId: string
  turnIndex: number
  variantIndex: number
}

interface DeltaEvent {
  schemaVersion: number
  text: string
}

interface ResetEvent {
  schemaVersion: number
  reason: string
}

interface TerminalEvent {
  schemaVersion: number
  assistantMessage?: AssistantMessage
  code?: string
  message?: string
  requestId?: string
  retryable?: boolean
}

export type ConversationStreamEvent =
  | { type: 'started'; data: StartedEvent }
  | { type: 'delta'; data: DeltaEvent }
  | { type: 'reset'; data: ResetEvent }
  | { type: 'complete' | 'cancelled' | 'error'; data: TerminalEvent }

export function listConversations(query = '', limit = 100) {
  return request<ConversationSummary[]>({
    url: '/conversations',
    params: { q: query.trim(), limit },
  })
}

export function createConversation(title: string) {
  return request<ConversationSummary>({ url: '/conversations', method: 'post', data: { title } })
}

export function getConversation(id: string) {
  return request<ConversationDetail>({ url: `/conversations/${id}` })
}

export function renameConversation(id: string, title: string) {
  return request<ConversationSummary>({
    url: `/conversations/${id}`,
    method: 'patch',
    data: { title },
  })
}

export function deleteConversation(id: string) {
  return request<void>({ url: `/conversations/${id}`, method: 'delete' })
}

export function cancelGeneration(conversationId: string, generationId: string) {
  return request<void>({
    url: `/conversations/${conversationId}/generations/${generationId}/cancel`,
    method: 'post',
  })
}

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')

/**
 * fetch 原生支持读取 POST 响应流；这里按 SSE 规范逐帧解析，避免 EventSource 只能 GET 的限制。
 */
async function streamRequest(
  path: string,
  body: unknown,
  onEvent: (event: ConversationStreamEvent) => void,
  signal?: AbortSignal,
) {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
    signal,
  })

  if (!response.ok) {
    let message = `服务请求失败（${response.status}）`
    let code: string | undefined
    let requestId: string | undefined
    try {
      const error = (await response.json()) as {
        message?: string
        code?: string
        requestId?: string
      }
      message = error.message || message
      code = error.code
      requestId = error.requestId
    } catch {
      // 非 JSON 错误响应使用上方的通用提示。
    }
    throw new ApiRequestError(message, { code, requestId, status: response.status })
  }

  if (!response.body) throw new ApiRequestError('浏览器未收到回答数据流')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const emitFrame = (frame: string) => {
    let eventName = 'message'
    const dataLines: string[] = []
    frame.split(/\r?\n/).forEach((line) => {
      if (line.startsWith('event:')) eventName = line.slice(6).trim()
      if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
    })
    if (!dataLines.length || eventName === 'message') return

    const data = JSON.parse(dataLines.join('\n')) as Record<string, unknown>
    onEvent({ type: eventName, data } as unknown as ConversationStreamEvent)
  }

  while (true) {
    const { done, value } = await reader.read()
    buffer += decoder.decode(value, { stream: !done })
    let boundary = buffer.match(/\r?\n\r?\n/)
    while (boundary?.index !== undefined) {
      const frame = buffer.slice(0, boundary.index)
      buffer = buffer.slice(boundary.index + boundary[0].length)
      if (frame.trim()) emitFrame(frame)
      boundary = buffer.match(/\r?\n\r?\n/)
    }
    if (done) break
  }

  if (buffer.trim()) emitFrame(buffer)
}

export function askConversation(
  conversationId: string,
  clientMessageId: string,
  content: string,
  onEvent: (event: ConversationStreamEvent) => void,
  signal?: AbortSignal,
) {
  return streamRequest(
    `/conversations/${conversationId}/messages`,
    { clientMessageId, content },
    onEvent,
    signal,
  )
}

export function retryAnswer(
  conversationId: string,
  assistantMessageId: string,
  clientRequestId: string,
  onEvent: (event: ConversationStreamEvent) => void,
  signal?: AbortSignal,
) {
  return streamRequest(
    `/conversations/${conversationId}/messages/${assistantMessageId}/retry`,
    { clientRequestId },
    onEvent,
    signal,
  )
}

export function regenerateAnswer(
  conversationId: string,
  assistantMessageId: string,
  clientRequestId: string,
  onEvent: (event: ConversationStreamEvent) => void,
  signal?: AbortSignal,
) {
  return streamRequest(
    `/conversations/${conversationId}/messages/${assistantMessageId}/regenerate`,
    { clientRequestId },
    onEvent,
    signal,
  )
}
