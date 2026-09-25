import { defineStore } from 'pinia'
import { markRaw, reactive, ref } from 'vue'

import {
  askConversation,
  cancelGeneration,
  getErrorMessage,
  regenerateAnswer,
  retryAnswer,
  type AssistantMessage,
  type ConversationDetail,
  type ConversationStreamEvent,
  type UserMessage,
} from '../api'

export type GenerationPhase =
  'starting' | 'streaming' | 'stopping' | 'completed' | 'failed' | 'cancelled'

export interface ConversationGenerationTask {
  conversationId: string
  user: UserMessage
  assistant: AssistantMessage
  phase: GenerationPhase
  generationId: string | null
  controller: AbortController
  serverStarted: boolean
  unread: boolean
}

type StreamRunner = (
  callback: (event: ConversationStreamEvent) => void,
  signal: AbortSignal,
) => Promise<void>

const activePhases: GenerationPhase[] = ['starting', 'streaming', 'stopping']

/**
 * 生成任务放在页面组件之外，切换会话或后台路由时不会销毁正在读取的 SSE 连接。
 */
export const useConversationGenerationStore = defineStore('conversation-generation', () => {
  const tasks = reactive<Record<string, ConversationGenerationTask>>({})
  const terminalRevision = ref(0)

  function taskFor(conversationId: string) {
    return conversationId ? tasks[conversationId] || null : null
  }

  function isActive(conversationId: string) {
    const task = taskFor(conversationId)
    return Boolean(task && activePhases.includes(task.phase))
  }

  function finishTask(
    task: ConversationGenerationTask,
    phase: Extract<GenerationPhase, 'completed' | 'failed' | 'cancelled'>,
  ) {
    task.phase = phase
    task.unread = true
    terminalRevision.value += 1
  }

  function handleEvent(task: ConversationGenerationTask, event: ConversationStreamEvent) {
    if (event.type === 'started') {
      task.serverStarted = true
      task.generationId = event.data.generationId
      task.assistant.id = event.data.assistantMessageId
      task.assistant.status = 'STREAMING'
      task.phase = 'streaming'
      return
    }

    if (event.type === 'delta') {
      task.assistant.status = 'STREAMING'
      task.assistant.content += event.data.text
      if (task.phase !== 'stopping') task.phase = 'streaming'
      return
    }

    if (event.type === 'reasoning_delta') {
      task.assistant.status = 'STREAMING'
      task.assistant.reasoningContent += event.data.text
      if (task.phase !== 'stopping') task.phase = 'streaming'
      return
    }

    if (event.type === 'reset') {
      task.assistant.content = ''
      task.assistant.reasoningContent = ''
      return
    }

    if (event.data.assistantMessage) {
      Object.assign(task.assistant, event.data.assistantMessage)
      task.serverStarted = true
    } else {
      task.assistant.status = event.type === 'cancelled' ? 'CANCELLED' : 'FAILED'
      task.assistant.errorCode = event.data.code || null
      task.assistant.errorMessage = event.data.message || '回答生成失败，请重试'
    }

    finishTask(
      task,
      event.type === 'complete' ? 'completed' : event.type === 'cancelled' ? 'cancelled' : 'failed',
    )
  }

  async function launch(
    conversationId: string,
    user: UserMessage,
    assistant: AssistantMessage,
    stream: StreamRunner,
  ) {
    if (isActive(conversationId)) return

    tasks[conversationId] = {
      conversationId,
      user,
      assistant,
      phase: 'starting',
      generationId: null,
      controller: markRaw(new AbortController()),
      serverStarted: false,
      unread: false,
    }
    // 后续必须通过 reactive 容器取回 Proxy。若继续使用上面的原始对象，
    // delta 虽然会累加到 content，却不会触发组件更新和增量滚动。
    const task = tasks[conversationId]!

    try {
      await stream((event) => handleEvent(task, event), task.controller.signal)
      if (activePhases.includes(task.phase)) {
        task.assistant.status = 'FAILED'
        task.assistant.errorMessage = '回答连接意外结束，请重试'
        finishTask(task, 'failed')
      }
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return
      task.assistant.status = 'FAILED'
      task.assistant.errorMessage = getErrorMessage(error)
      finishTask(task, 'failed')
    }
  }

  function startAsk(
    conversationId: string,
    user: UserMessage,
    assistant: AssistantMessage,
    content: string,
  ) {
    return launch(conversationId, user, assistant, (callback, signal) =>
      askConversation(conversationId, user.id, content, callback, signal),
    )
  }

  function restartAnswer(
    conversationId: string,
    user: UserMessage,
    assistant: AssistantMessage,
    previousAssistantId: string,
    requestId: string,
    mode: 'retry' | 'regenerate',
  ) {
    return launch(conversationId, user, assistant, (callback, signal) =>
      mode === 'retry'
        ? retryAnswer(conversationId, previousAssistantId, requestId, callback, signal)
        : regenerateAnswer(conversationId, previousAssistantId, requestId, callback, signal),
    )
  }

  async function stop(conversationId: string) {
    const task = taskFor(conversationId)
    if (!task || !activePhases.includes(task.phase) || !task.generationId) return
    const previousPhase = task.phase
    task.phase = 'stopping'
    try {
      await cancelGeneration(conversationId, task.generationId)
    } catch (error) {
      task.phase = previousPhase
      throw error
    }
  }

  /** 将后台任务的最新回答合并进接口详情，切回会话后继续使用同一个响应式对象。 */
  function mergeIntoDetail(detail: ConversationDetail) {
    const task = taskFor(detail.id)
    if (!task) return detail
    if (!task.serverStarted && !isActive(detail.id)) return detail

    let turn = detail.turns.find(
      (item) => item.user.id === task.user.id || item.user.turnIndex === task.user.turnIndex,
    )
    if (!turn) {
      // 浏览历史窗口时不追加窗口之外的后台生成轮次，避免消息断层。
      if (
        detail.hasNewer ||
        (detail.hasOlder && task.user.turnIndex < (detail.turns[0]?.user.turnIndex ?? 0))
      ) {
        return detail
      }
      turn = {
        user: task.user,
        assistantVersions: [],
        activeAssistantId: task.assistant.id,
      }
      detail.turns.push(turn)
    }

    const versionIndex = turn.assistantVersions.findIndex(
      (item) => item.id === task.assistant.id || item.variantIndex === task.assistant.variantIndex,
    )
    if (versionIndex >= 0) turn.assistantVersions[versionIndex] = task.assistant
    else turn.assistantVersions.push(task.assistant)

    turn.assistantVersions.forEach((item) => {
      item.active = item.id === task.assistant.id
    })
    turn.activeAssistantId = task.assistant.id
    return detail
  }

  function markSeen(conversationId: string) {
    const task = taskFor(conversationId)
    if (!task || activePhases.includes(task.phase)) return
    delete tasks[conversationId]
  }

  function discard(conversationId: string) {
    const task = taskFor(conversationId)
    if (task && !activePhases.includes(task.phase)) delete tasks[conversationId]
  }

  return {
    tasks,
    terminalRevision,
    taskFor,
    isActive,
    startAsk,
    restartAnswer,
    stop,
    mergeIntoDetail,
    markSeen,
    discard,
  }
})
