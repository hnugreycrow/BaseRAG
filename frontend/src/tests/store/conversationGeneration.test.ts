import { createPinia, setActivePinia } from 'pinia'
import { vi } from 'vitest'

import { askConversation, type AssistantMessage, type UserMessage } from '../../api'
import { useConversationGenerationStore } from '../../store/conversationGeneration'

vi.mock('../../api', async () => {
  const actual = await vi.importActual<typeof import('../../api')>('../../api')
  return { ...actual, askConversation: vi.fn() }
})

describe('conversation thinking stream', () => {
  it('keeps reasoning separate from the answer and resets both on a new attempt', async () => {
    setActivePinia(createPinia())
    const user: UserMessage = {
      id: 'user',
      turnIndex: 1,
      content: '问题',
      createdAt: '2026-01-01T00:00:00Z',
    }
    const assistant: AssistantMessage = {
      id: 'assistant',
      replyToId: user.id,
      turnIndex: 1,
      variantIndex: 1,
      active: true,
      status: 'PENDING',
      content: '',
      thinkingEnabled: true,
      reasoningContent: '',
      retrievalQuery: null,
      sources: [],
      citations: [],
      modelInfo: null,
      errorCode: null,
      errorMessage: null,
      createdAt: user.createdAt,
      updatedAt: user.createdAt,
      completedAt: null,
    }
    vi.mocked(askConversation).mockImplementation(async (_id, _clientId, _content, onEvent) => {
      onEvent({
        type: 'started',
        data: {
          schemaVersion: 1,
          conversationId: 'conversation',
          userMessageId: user.id,
          assistantMessageId: assistant.id,
          generationId: 'generation',
          turnIndex: 1,
          variantIndex: 1,
        },
      })
      onEvent({ type: 'reasoning_delta', data: { schemaVersion: 1, text: '旧思考' } })
      onEvent({ type: 'delta', data: { schemaVersion: 1, text: '旧回答' } })
      onEvent({ type: 'reset', data: { schemaVersion: 1, reason: 'INVALID_CITATIONS' } })
      onEvent({ type: 'reasoning_delta', data: { schemaVersion: 1, text: '新思考' } })
      onEvent({ type: 'delta', data: { schemaVersion: 1, text: '新回答' } })
      onEvent({ type: 'reset', data: { schemaVersion: 1, reason: 'CITATION_NORMALIZED' } })
      onEvent({ type: 'reasoning_delta', data: { schemaVersion: 1, text: '新思考' } })
      onEvent({ type: 'delta', data: { schemaVersion: 1, text: '规范化回答 [S1]' } })
      onEvent({
        type: 'complete',
        data: { schemaVersion: 1, assistantMessage: { ...assistant, status: 'COMPLETED' } },
      })
    })

    const store = useConversationGenerationStore()
    await store.startAsk('conversation', user, assistant, user.content)
    expect(store.taskFor('conversation')?.assistant.content).toBe('规范化回答 [S1]')
    expect(store.taskFor('conversation')?.assistant.reasoningContent).toBe('新思考')
    expect(store.taskFor('conversation')?.phase).toBe('completed')
  })
})
