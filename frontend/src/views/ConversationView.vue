<script setup lang="ts">
import {
  ArrowLeft,
  ArrowRight,
  ChatDotRound,
  Close,
  CopyDocument,
  Delete,
  Document,
  EditPen,
  Menu as MenuIcon,
  Plus,
  Position,
  RefreshRight,
  Search,
  Setting,
  VideoPause,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  createConversation,
  deleteConversation,
  getConversation,
  getErrorMessage,
  listConversations,
  renameConversation,
  type AnswerSource,
  type AssistantMessage,
  type ConversationDetail,
  type ConversationSummary,
  type ConversationTurn,
} from '../api'
import { useConversationGenerationStore } from '../store'

const route = useRoute()
const router = useRouter()
const generationStore = useConversationGenerationStore()

const conversations = ref<ConversationSummary[]>([])
const conversation = ref<ConversationDetail | null>(null)
const searchQuery = ref('')
const draft = ref('')
const listLoading = ref(false)
const detailLoading = ref(false)
const mobileSidebarOpen = ref(false)
const sourcePanelOpen = ref(false)
const sourceMessage = ref<AssistantMessage | null>(null)
const highlightedCitation = ref<string | null>(null)
const messageViewport = ref<HTMLElement | null>(null)
const viewedVersions = ref<Record<number, string>>({})

let listSequence = 0
let searchTimer: ReturnType<typeof setTimeout> | undefined
let skipRouteLoadId: string | null = null

const currentConversationId = computed(() => {
  const value = route.params.conversationId
  return typeof value === 'string' ? value : ''
})

const isEmpty = computed(() => !detailLoading.value && !conversation.value?.turns.length)
const currentTask = computed(() => generationStore.taskFor(currentConversationId.value))
const sending = computed(() => generationStore.isActive(currentConversationId.value))
const stopping = computed(() => currentTask.value?.phase === 'stopping')
const activeGenerationId = computed(() => currentTask.value?.generationId || null)

const groupedConversations = computed(() => {
  const groups = new Map<string, ConversationSummary[]>()
  conversations.value.forEach((item) => {
    const label = dateGroup(item.updatedAt)
    groups.set(label, [...(groups.get(label) || []), item])
  })
  return Array.from(groups, ([label, items]) => ({ label, items }))
})

const suggestions = [
  '概括知识库中的核心内容',
  '文档里有哪些重要规则？',
  '帮我查找与当前问题相关的依据',
]

interface AnswerBlock {
  type: 'paragraph' | 'heading' | 'unordered-list' | 'ordered-list' | 'quote' | 'code'
  content?: string
  items?: string[]
  level?: number
  language?: string
}

function dateGroup(value: string) {
  const date = new Date(value)
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const day = 86_400_000
  if (date.getTime() >= start) return '今天'
  if (date.getTime() >= start - day) return '昨天'
  if (date.getTime() >= start - day * 7) return '最近 7 天'
  return '更早'
}

function newUuid() {
  return crypto.randomUUID()
}

function makeAssistant(
  id: string,
  replyToId: string,
  turnIndex: number,
  variantIndex: number,
): AssistantMessage {
  const now = new Date().toISOString()
  return {
    id,
    replyToId,
    turnIndex,
    variantIndex,
    active: true,
    status: 'PENDING',
    content: '',
    retrievalQuery: null,
    sources: [],
    citations: [],
    modelInfo: null,
    errorCode: null,
    errorMessage: null,
    createdAt: now,
    updatedAt: now,
    completedAt: null,
  }
}

async function loadConversationList(query = searchQuery.value) {
  const sequence = ++listSequence
  listLoading.value = true
  try {
    const result = await listConversations(query)
    if (sequence === listSequence) conversations.value = result
  } catch (error) {
    if (sequence === listSequence) ElMessage.error(getErrorMessage(error))
  } finally {
    if (sequence === listSequence) listLoading.value = false
  }
}

async function loadCurrentConversation(id: string) {
  sourcePanelOpen.value = false
  viewedVersions.value = {}
  if (!id) {
    conversation.value = null
    detailLoading.value = false
    return
  }

  detailLoading.value = true
  try {
    const result = generationStore.mergeIntoDetail(await getConversation(id))
    if (currentConversationId.value !== id) return
    conversation.value = result
    result.turns.forEach((turn) => {
      if (turn.activeAssistantId) viewedVersions.value[turn.user.turnIndex] = turn.activeAssistantId
    })
    generationStore.markSeen(id)
  } catch (error) {
    if (currentConversationId.value === id) {
      ElMessage.error(getErrorMessage(error))
      await router.replace('/chat')
    }
  } finally {
    if (currentConversationId.value === id) {
      detailLoading.value = false
      if (conversation.value?.id === id) void scrollToBottom(false)
    }
  }
}

function openConversation(id: string) {
  mobileSidebarOpen.value = false
  if (id === currentConversationId.value) generationStore.markSeen(id)
  void router.push(`/chat/${id}`)
}

function startNewConversation() {
  mobileSidebarOpen.value = false
  draft.value = ''
  void router.push('/chat')
}

async function editConversation(item: ConversationSummary) {
  try {
    const result = await ElMessageBox.prompt('请输入新的会话名称', '重命名会话', {
      inputValue: item.title,
      inputPattern: /\S/,
      inputErrorMessage: '会话名称不能为空',
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    })
    const updated = await renameConversation(item.id, result.value.trim())
    Object.assign(item, updated)
    if (conversation.value?.id === item.id) conversation.value.title = updated.title
    ElMessage.success('会话名称已更新')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(getErrorMessage(error))
  }
}

async function removeConversation(item: ConversationSummary) {
  if (generationStore.isActive(item.id)) {
    ElMessage.warning('请先停止当前回答，再删除会话')
    return
  }
  try {
    await ElMessageBox.confirm(`“${item.title}”中的全部消息将被永久删除。`, '删除会话', {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await deleteConversation(item.id)
    generationStore.discard(item.id)
    conversations.value = conversations.value.filter((value) => value.id !== item.id)
    if (item.id === currentConversationId.value) await router.replace('/chat')
    ElMessage.success('会话已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(getErrorMessage(error))
  }
}

function currentAssistant(turn: ConversationTurn) {
  const selectedId = viewedVersions.value[turn.user.turnIndex] || turn.activeAssistantId
  return (
    turn.assistantVersions.find((item) => item.id === selectedId) ||
    turn.assistantVersions.at(-1) ||
    null
  )
}

function selectVersion(turn: ConversationTurn, direction: number) {
  const current = currentAssistant(turn)
  if (!current) return
  const index = turn.assistantVersions.findIndex((item) => item.id === current.id)
  const next = Math.min(Math.max(index + direction, 0), turn.assistantVersions.length - 1)
  viewedVersions.value[turn.user.turnIndex] = turn.assistantVersions[next]!.id
}

function taskStatusLabel(conversationId: string) {
  const phase = generationStore.taskFor(conversationId)?.phase
  if (phase === 'starting' || phase === 'streaming') return '生成中'
  if (phase === 'stopping') return '停止中'
  if (phase === 'completed') return '已完成'
  if (phase === 'failed') return '生成失败'
  if (phase === 'cancelled') return '已停止'
  return ''
}

function inlineParts(content: string) {
  const parts: Array<{
    text: string
    type: 'text' | 'citation' | 'strong' | 'code'
    citationId?: string
  }> = []
  const pattern = /(\[(S\d+)\]|`([^`]+)`|\*\*([^*]+)\*\*)/g
  let cursor = 0
  for (const match of content.matchAll(pattern)) {
    const index = match.index ?? 0
    if (index > cursor) parts.push({ text: content.slice(cursor, index), type: 'text' })
    if (match[2]) parts.push({ text: match[0], type: 'citation', citationId: match[2] })
    else if (match[3]) parts.push({ text: match[3], type: 'code' })
    else parts.push({ text: match[4] || match[0], type: 'strong' })
    cursor = index + match[0].length
  }
  if (cursor < content.length) parts.push({ text: content.slice(cursor), type: 'text' })
  return parts
}

/** 将模型常见的 Markdown 块转换成结构化数据，模板渲染可避免注入不可信 HTML。 */
function answerBlocks(content: string) {
  const blocks: AnswerBlock[] = []
  const lines = content.replace(/\r\n/g, '\n').split('\n')
  let index = 0

  while (index < lines.length) {
    const line = lines[index] || ''
    if (!line.trim()) {
      index += 1
      continue
    }

    const fence = line.match(/^```\s*([^\s]*)/)
    if (fence) {
      const code: string[] = []
      index += 1
      while (index < lines.length && !lines[index]!.startsWith('```')) code.push(lines[index++]!)
      if (index < lines.length) index += 1
      blocks.push({ type: 'code', content: code.join('\n'), language: fence[1] || '' })
      continue
    }

    const heading = line.match(/^(#{1,6})\s+(.+)/)
    if (heading) {
      blocks.push({ type: 'heading', level: heading[1]!.length, content: heading[2] })
      index += 1
      continue
    }

    const unordered = line.match(/^\s*[-*]\s+(.+)/)
    if (unordered) {
      const items: string[] = []
      while (index < lines.length) {
        const item = lines[index]!.match(/^\s*[-*]\s+(.+)/)
        if (!item) break
        items.push(item[1]!)
        index += 1
      }
      blocks.push({ type: 'unordered-list', items })
      continue
    }

    const ordered = line.match(/^\s*\d+[.)]\s+(.+)/)
    if (ordered) {
      const items: string[] = []
      while (index < lines.length) {
        const item = lines[index]!.match(/^\s*\d+[.)]\s+(.+)/)
        if (!item) break
        items.push(item[1]!)
        index += 1
      }
      blocks.push({ type: 'ordered-list', items })
      continue
    }

    if (line.startsWith('>')) {
      const quoted: string[] = []
      while (index < lines.length && lines[index]!.startsWith('>')) {
        quoted.push(lines[index]!.replace(/^>\s?/, ''))
        index += 1
      }
      blocks.push({ type: 'quote', content: quoted.join('\n') })
      continue
    }

    const paragraph = [line]
    index += 1
    while (
      index < lines.length &&
      lines[index]!.trim() &&
      !/^(#{1,6})\s+|^```|^\s*[-*]\s+|^\s*\d+[.)]\s+|^>/.test(lines[index]!)
    ) {
      paragraph.push(lines[index++]!)
    }
    blocks.push({ type: 'paragraph', content: paragraph.join('\n') })
  }

  return blocks
}

function openSources(message: AssistantMessage, citationId?: string) {
  sourceMessage.value = message
  highlightedCitation.value = citationId || null
  sourcePanelOpen.value = true
  void nextTick(() => {
    if (!citationId) return
    document
      .getElementById(`source-${message.id}-${citationId}`)
      ?.scrollIntoView({ block: 'center' })
  })
}

function sourceLineLabel(source: AnswerSource) {
  return source.lineStart === source.lineEnd
    ? `第 ${source.lineStart} 行`
    : `第 ${source.lineStart}–${source.lineEnd} 行`
}

async function copyAnswer(content: string) {
  try {
    await navigator.clipboard.writeText(content)
    ElMessage.success('回答已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择文本')
  }
}

async function scrollToBottom(smooth = true) {
  await nextTick()
  messageViewport.value?.scrollTo({
    top: messageViewport.value.scrollHeight,
    behavior: smooth ? 'smooth' : 'auto',
  })
}

async function submitQuestion() {
  const content = draft.value.trim()
  if (!content || generationStore.isActive(currentConversationId.value)) return
  if (content.length > 2000) {
    ElMessage.warning('问题不能超过 2000 个字符')
    return
  }

  draft.value = ''
  let id = currentConversationId.value
  if (!id) {
    try {
      const created = await createConversation(content.slice(0, 28))
      id = created.id
      conversation.value = { ...created, turns: [] }
      conversations.value = [created, ...conversations.value]
      // 当前页面已经持有新建结果，避免路由监听再发起一次详情请求覆盖乐观消息。
      skipRouteLoadId = id
      await router.replace(`/chat/${id}`)
    } catch (error) {
      draft.value = content
      ElMessage.error(getErrorMessage(error))
      return
    }
  }

  if (!conversation.value || conversation.value.id !== id) {
    try {
      conversation.value = await getConversation(id)
    } catch (error) {
      draft.value = content
      ElMessage.error(getErrorMessage(error))
      return
    }
  }

  const clientMessageId = newUuid()
  const turnIndex = (conversation.value.turns.at(-1)?.user.turnIndex ?? 0) + 1
  const now = new Date().toISOString()
  const assistant = makeAssistant(`${clientMessageId}-assistant`, clientMessageId, turnIndex, 1)
  const turn: ConversationTurn = {
    user: { id: clientMessageId, turnIndex, content, createdAt: now },
    assistantVersions: [assistant],
    activeAssistantId: assistant.id,
  }
  conversation.value.turns.push(turn)
  viewedVersions.value[turnIndex] = assistant.id
  await scrollToBottom()

  void generationStore.startAsk(id, turn.user, assistant, content)
}

async function rerunAnswer(turn: ConversationTurn, mode: 'retry' | 'regenerate') {
  const previous = currentAssistant(turn)
  const id = currentConversationId.value
  if (!previous || !id || generationStore.isActive(id)) return

  turn.assistantVersions.forEach((item) => (item.active = false))
  const requestId = newUuid()
  const variantIndex = Math.max(...turn.assistantVersions.map((item) => item.variantIndex)) + 1
  const assistant = makeAssistant(
    `${requestId}-assistant`,
    turn.user.id,
    turn.user.turnIndex,
    variantIndex,
  )
  turn.assistantVersions.push(assistant)
  turn.activeAssistantId = assistant.id
  viewedVersions.value[turn.user.turnIndex] = assistant.id
  await scrollToBottom()

  void generationStore.restartAnswer(id, turn.user, assistant, previous.id, requestId, mode)
}

async function stopAnswer() {
  const id = currentConversationId.value
  if (!id || !activeGenerationId.value || stopping.value) return
  try {
    await generationStore.stop(id)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

function useSuggestion(value: string) {
  draft.value = value
  void submitQuestion()
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  void submitQuestion()
}

watch(
  currentConversationId,
  (id) => {
    if (id && skipRouteLoadId === id) {
      skipRouteLoadId = null
      detailLoading.value = false
      return
    }
    void loadCurrentConversation(id)
  },
  { immediate: true },
)

// 只跟随当前会话的增量滚动，后台会话继续生成但不会干扰正在查看的内容。
watch(
  () => currentTask.value?.assistant.content.length,
  () => {
    if (sending.value) void scrollToBottom()
  },
)

watch(
  () => currentTask.value?.assistant.id,
  (assistantId) => {
    const task = currentTask.value
    if (assistantId && task) viewedVersions.value[task.user.turnIndex] = assistantId
  },
)

watch(
  () => generationStore.terminalRevision,
  () => {
    void loadConversationList()
    const id = currentConversationId.value
    if (id && !generationStore.isActive(id)) generationStore.markSeen(id)
  },
)

watch(searchQuery, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void loadConversationList(), 240)
})

void loadConversationList()

onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer)
})
</script>

<template>
  <div class="chat-page" :class="{ 'has-sources': sourcePanelOpen }">
    <div
      v-if="mobileSidebarOpen"
      class="sidebar-scrim"
      aria-hidden="true"
      @click="mobileSidebarOpen = false"
    ></div>

    <aside class="chat-sidebar" :class="{ 'is-open': mobileSidebarOpen }">
      <div class="sidebar-head">
        <RouterLink class="brand" to="/chat" aria-label="JAgent 问答首页">
          <span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
          <span><strong>JAgent</strong><small>知识问答</small></span>
        </RouterLink>
        <button
          class="mobile-close"
          type="button"
          aria-label="关闭会话列表"
          @click="mobileSidebarOpen = false"
        >
          <el-icon><Close /></el-icon>
        </button>
      </div>

      <button class="new-chat-button" type="button" @click="startNewConversation">
        <el-icon><Plus /></el-icon>
        新对话
      </button>

      <label class="search-box">
        <el-icon><Search /></el-icon>
        <input v-model="searchQuery" type="search" placeholder="搜索会话" aria-label="搜索会话" />
      </label>

      <div class="conversation-list" :class="{ 'is-loading': listLoading }">
        <template v-if="groupedConversations.length">
          <section
            v-for="group in groupedConversations"
            :key="group.label"
            class="conversation-group"
          >
            <h2>{{ group.label }}</h2>
            <div
              v-for="item in group.items"
              :key="item.id"
              class="conversation-item"
              :class="{
                'is-active': item.id === currentConversationId,
                'has-active-task': generationStore.isActive(item.id),
                'has-task-status': generationStore.taskFor(item.id),
              }"
            >
              <button type="button" class="conversation-link" @click="openConversation(item.id)">
                <el-icon><ChatDotRound /></el-icon>
                <span class="conversation-name">{{ item.title }}</span>
                <span
                  v-if="generationStore.taskFor(item.id)"
                  class="conversation-status"
                  :class="generationStore.taskFor(item.id)?.phase"
                  aria-live="polite"
                >
                  <i aria-hidden="true"></i>
                  {{ taskStatusLabel(item.id) }}
                </span>
              </button>
              <div v-if="!generationStore.isActive(item.id)" class="conversation-actions">
                <button
                  type="button"
                  title="重命名"
                  aria-label="重命名会话"
                  @click="editConversation(item)"
                >
                  <el-icon><EditPen /></el-icon>
                </button>
                <button
                  type="button"
                  title="删除"
                  aria-label="删除会话"
                  @click="removeConversation(item)"
                >
                  <el-icon><Delete /></el-icon>
                </button>
              </div>
            </div>
          </section>
        </template>
        <p v-else-if="!listLoading" class="conversation-empty">
          {{ searchQuery ? '没有匹配的会话' : '还没有历史会话' }}
        </p>
      </div>

      <RouterLink class="admin-entry" to="/admin">
        <el-icon><Setting /></el-icon>
        <span><strong>后台管理</strong><small>知识库与模型配置</small></span>
        <el-icon class="admin-arrow"><ArrowRight /></el-icon>
      </RouterLink>
    </aside>

    <main class="chat-workspace">
      <button
        class="sidebar-trigger"
        type="button"
        aria-label="打开会话列表"
        @click="mobileSidebarOpen = true"
      >
        <el-icon><MenuIcon /></el-icon>
      </button>
      <div ref="messageViewport" class="message-viewport">
        <div v-if="detailLoading" class="loading-state" aria-label="正在加载会话">
          <span></span><span></span><span></span>
        </div>

        <section v-else-if="isEmpty" class="welcome-state">
          <div class="welcome-mark" aria-hidden="true"><i></i><i></i><i></i><i></i></div>
          <p class="welcome-eyebrow">JAGENT · GROUNDED ANSWERS</p>
          <h1>从你的资料中，<br />找到有依据的答案</h1>
          <p class="welcome-copy">回答会标注实际检索来源。你可以继续追问，也可以随时核对原文。</p>
          <div class="suggestion-grid" aria-label="问题示例">
            <button
              v-for="item in suggestions"
              :key="item"
              type="button"
              @click="useSuggestion(item)"
            >
              <span>{{ item }}</span>
              <el-icon><ArrowRight /></el-icon>
            </button>
          </div>
        </section>

        <div v-else class="message-list">
          <article v-for="turn in conversation?.turns" :key="turn.user.id" class="turn">
            <div class="user-row">
              <div class="user-message">{{ turn.user.content }}</div>
            </div>

            <div v-if="currentAssistant(turn)" class="assistant-row">
              <div class="assistant-avatar" aria-hidden="true"><span>J</span></div>
              <div class="assistant-content">
                <div
                  v-if="currentAssistant(turn)?.content"
                  class="answer-text"
                  :class="{ 'is-streaming': currentAssistant(turn)?.status === 'STREAMING' }"
                >
                  <template
                    v-for="(block, blockIndex) in answerBlocks(
                      currentAssistant(turn)?.content || '',
                    )"
                    :key="blockIndex"
                  >
                    <component
                      :is="`h${Math.min(block.level || 2, 4)}`"
                      v-if="block.type === 'heading'"
                      class="answer-heading"
                    >
                      <template
                        v-for="(part, index) in inlineParts(block.content || '')"
                        :key="index"
                      >
                        <button
                          v-if="part.type === 'citation'"
                          type="button"
                          class="citation"
                          :title="`查看来源 ${part.citationId}`"
                          @click="openSources(currentAssistant(turn)!, part.citationId)"
                        >
                          {{ part.text }}
                        </button>
                        <strong v-else-if="part.type === 'strong'">{{ part.text }}</strong>
                        <code v-else-if="part.type === 'code'" class="inline-code">{{
                          part.text
                        }}</code>
                        <span v-else>{{ part.text }}</span>
                      </template>
                    </component>

                    <p v-else-if="block.type === 'paragraph'" class="answer-paragraph">
                      <template
                        v-for="(part, index) in inlineParts(block.content || '')"
                        :key="index"
                      >
                        <button
                          v-if="part.type === 'citation'"
                          type="button"
                          class="citation"
                          :title="`查看来源 ${part.citationId}`"
                          @click="openSources(currentAssistant(turn)!, part.citationId)"
                        >
                          {{ part.text }}
                        </button>
                        <strong v-else-if="part.type === 'strong'">{{ part.text }}</strong>
                        <code v-else-if="part.type === 'code'" class="inline-code">{{
                          part.text
                        }}</code>
                        <span v-else>{{ part.text }}</span>
                      </template>
                    </p>

                    <ul v-else-if="block.type === 'unordered-list'" class="answer-list">
                      <li v-for="(item, itemIndex) in block.items" :key="itemIndex">
                        <template v-for="(part, index) in inlineParts(item)" :key="index">
                          <button
                            v-if="part.type === 'citation'"
                            type="button"
                            class="citation"
                            :title="`查看来源 ${part.citationId}`"
                            @click="openSources(currentAssistant(turn)!, part.citationId)"
                          >
                            {{ part.text }}
                          </button>
                          <strong v-else-if="part.type === 'strong'">{{ part.text }}</strong>
                          <code v-else-if="part.type === 'code'" class="inline-code">{{
                            part.text
                          }}</code>
                          <span v-else>{{ part.text }}</span>
                        </template>
                      </li>
                    </ul>

                    <ol v-else-if="block.type === 'ordered-list'" class="answer-list">
                      <li v-for="(item, itemIndex) in block.items" :key="itemIndex">
                        <template v-for="(part, index) in inlineParts(item)" :key="index">
                          <button
                            v-if="part.type === 'citation'"
                            type="button"
                            class="citation"
                            :title="`查看来源 ${part.citationId}`"
                            @click="openSources(currentAssistant(turn)!, part.citationId)"
                          >
                            {{ part.text }}
                          </button>
                          <strong v-else-if="part.type === 'strong'">{{ part.text }}</strong>
                          <code v-else-if="part.type === 'code'" class="inline-code">{{
                            part.text
                          }}</code>
                          <span v-else>{{ part.text }}</span>
                        </template>
                      </li>
                    </ol>

                    <blockquote v-else-if="block.type === 'quote'" class="answer-quote">
                      {{ block.content }}
                    </blockquote>
                    <pre
                      v-else-if="block.type === 'code'"
                      class="answer-code"
                    ><code>{{ block.content }}</code></pre>
                  </template>
                </div>

                <div
                  v-else-if="
                    ['PENDING', 'STREAMING'].includes(currentAssistant(turn)?.status || '')
                  "
                  class="thinking-state"
                >
                  <span></span><span></span><span></span>
                  正在查找资料并组织回答
                </div>

                <div
                  v-if="['FAILED', 'CANCELLED'].includes(currentAssistant(turn)?.status || '')"
                  class="answer-error"
                >
                  <strong>{{
                    currentAssistant(turn)?.status === 'CANCELLED' ? '回答已停止' : '生成未完成'
                  }}</strong>
                  <span>{{
                    currentAssistant(turn)?.errorMessage || '你可以重新尝试生成回答。'
                  }}</span>
                </div>

                <div
                  v-if="!['PENDING', 'STREAMING'].includes(currentAssistant(turn)?.status || '')"
                  class="answer-toolbar"
                >
                  <button
                    v-if="currentAssistant(turn)?.content"
                    type="button"
                    title="复制回答"
                    @click="copyAnswer(currentAssistant(turn)!.content)"
                  >
                    <el-icon><CopyDocument /></el-icon>
                  </button>
                  <button
                    v-if="currentAssistant(turn)?.sources.length"
                    type="button"
                    class="source-button"
                    @click="openSources(currentAssistant(turn)!)"
                  >
                    <el-icon><Document /></el-icon>
                    {{ currentAssistant(turn)?.sources.length }} 条检索来源
                  </button>
                  <button
                    v-if="['FAILED', 'CANCELLED'].includes(currentAssistant(turn)?.status || '')"
                    type="button"
                    class="retry-button"
                    @click="rerunAnswer(turn, 'retry')"
                  >
                    <el-icon><RefreshRight /></el-icon>
                    重试
                  </button>
                  <button
                    v-if="
                      currentAssistant(turn)?.status === 'COMPLETED' &&
                      turn === conversation?.turns.at(-1) &&
                      currentAssistant(turn)?.active
                    "
                    type="button"
                    class="retry-button"
                    @click="rerunAnswer(turn, 'regenerate')"
                  >
                    <el-icon><RefreshRight /></el-icon>
                    重新生成
                  </button>
                  <span v-if="currentAssistant(turn)?.modelInfo" class="model-label">
                    {{ currentAssistant(turn)?.modelInfo?.model }}
                  </span>
                  <div v-if="turn.assistantVersions.length > 1" class="version-switcher">
                    <button
                      type="button"
                      aria-label="上一个回答版本"
                      @click="selectVersion(turn, -1)"
                    >
                      <el-icon><ArrowLeft /></el-icon>
                    </button>
                    <span>
                      {{
                        turn.assistantVersions.findIndex(
                          (item) => item.id === currentAssistant(turn)?.id,
                        ) + 1
                      }}
                      /
                      {{ turn.assistantVersions.length }}
                    </span>
                    <button
                      type="button"
                      aria-label="下一个回答版本"
                      @click="selectVersion(turn, 1)"
                    >
                      <el-icon><ArrowRight /></el-icon>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </article>
        </div>
      </div>

      <footer class="composer-area">
        <div class="composer-shell" :class="{ 'is-busy': sending }">
          <el-input
            v-model="draft"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 6 }"
            maxlength="2000"
            resize="none"
            placeholder="向知识库提问"
            aria-label="输入问题"
            @keydown="handleComposerKeydown"
          />
          <button
            v-if="sending"
            type="button"
            class="send-button stop-button"
            :disabled="stopping || !activeGenerationId"
            :aria-label="stopping ? '正在停止' : '停止生成'"
            @click="stopAnswer"
          >
            <el-icon><VideoPause /></el-icon>
          </button>
          <button
            v-else
            type="button"
            class="send-button"
            :disabled="!draft.trim()"
            aria-label="发送问题"
            @click="submitQuestion"
          >
            <el-icon><Position /></el-icon>
          </button>
        </div>
        <p>JAgent 仅根据已导入资料回答，重要信息请通过来源原文核对。</p>
      </footer>
    </main>

    <div
      v-if="sourcePanelOpen"
      class="source-scrim"
      aria-hidden="true"
      @click="sourcePanelOpen = false"
    ></div>
    <aside class="source-panel" :class="{ 'is-open': sourcePanelOpen }" aria-label="检索来源">
      <header>
        <div>
          <span>回答依据</span>
          <h2>检索来源</h2>
        </div>
        <button type="button" aria-label="关闭来源" @click="sourcePanelOpen = false">
          <el-icon><Close /></el-icon>
        </button>
      </header>
      <div class="source-intro">
        <span>{{ sourceMessage?.sources.length || 0 }}</span>
        个文档片段参与了本次回答。点击引用标记可定位到对应原文。
      </div>
      <div class="source-list">
        <article
          v-for="source in sourceMessage?.sources"
          :id="`source-${sourceMessage?.id}-${source.citationId}`"
          :key="source.chunkId"
          class="source-card"
          :class="{ 'is-highlighted': highlightedCitation === source.citationId }"
        >
          <div class="source-card-head">
            <span class="source-id">{{ source.citationId }}</span>
            <span class="source-lines">{{ sourceLineLabel(source) }}</span>
          </div>
          <h3>{{ source.documentName }}</h3>
          <p class="source-path">
            {{ source.knowledgeBaseName
            }}<template v-if="source.heading"> · {{ source.heading }}</template>
          </p>
          <blockquote>{{ source.content }}</blockquote>
        </article>
        <div v-if="!sourceMessage?.sources.length" class="no-sources">
          <el-icon><Document /></el-icon>
          <p>这条回答没有可展示的检索来源。</p>
        </div>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.chat-page {
  --chat-ink: #1d2433;
  --chat-muted: #6e7582;
  --chat-line: #e6e7ea;
  --chat-blue: #315ee7;
  --chat-blue-soft: #eef2ff;
  width: 100%;
  height: 100vh;
  height: 100dvh;
  display: flex;
  overflow: hidden;
  color: var(--chat-ink);
  background: #ffffff;
}

.chat-sidebar {
  position: relative;
  z-index: 30;
  width: 276px;
  display: flex;
  flex: 0 0 276px;
  flex-direction: column;
  padding: 16px 12px 14px;
  background: #f7f7f8;
  border-right: 1px solid #ececef;
}

.sidebar-head,
.brand,
.conversation-link,
.admin-entry,
.desktop-admin-entry,
.answer-toolbar,
.source-card-head,
.source-panel header {
  display: flex;
  align-items: center;
}

.sidebar-head {
  min-height: 40px;
  justify-content: space-between;
  padding: 0 7px;
}

.brand {
  gap: 10px;
}

.brand-mark,
.welcome-mark {
  display: grid;
  grid-template-columns: repeat(2, 5px);
  place-content: center;
  gap: 3px;
  background: var(--chat-blue);
  border-radius: 10px 10px 3px 10px;
}

.brand-mark {
  width: 34px;
  height: 34px;
}
.brand-mark i,
.welcome-mark i {
  width: 5px;
  height: 5px;
  background: #ffffff;
  border-radius: 50%;
}
.brand > span:last-child {
  display: flex;
  flex-direction: column;
}
.brand strong {
  font-size: 15px;
  letter-spacing: -0.2px;
}
.brand small {
  margin-top: 1px;
  color: #8a8f99;
  font-size: 10px;
}
.mobile-close {
  display: none;
}

.new-chat-button {
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin: 19px 2px 12px;
  color: #ffffff;
  font-size: 13px;
  font-weight: 650;
  background: var(--chat-blue);
  border-radius: 10px;
  box-shadow: 0 6px 16px rgb(49 94 231 / 16%);
  transition:
    background 150ms ease,
    transform 150ms ease;
}

.new-chat-button:hover {
  background: #244fd4;
  transform: translateY(-1px);
}

.search-box {
  height: 38px;
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 2px 10px;
  padding: 0 11px;
  color: #9297a1;
  background: #ffffff;
  border: 1px solid #e6e7ea;
  border-radius: 9px;
}

.search-box:focus-within {
  border-color: #aab9f3;
  box-shadow: 0 0 0 3px rgb(49 94 231 / 8%);
}
.search-box input {
  min-width: 0;
  flex: 1;
  color: var(--chat-ink);
  font-size: 12px;
  background: transparent;
  border: 0;
  outline: 0;
}

.conversation-list {
  min-height: 0;
  flex: 1;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 4px 2px 12px;
  transition: opacity 120ms ease;
  scrollbar-width: thin;
}

.conversation-list.is-loading {
  opacity: 0.55;
}
.conversation-group h2 {
  margin: 16px 10px 6px;
  color: #9297a1;
  font-size: 10px;
  font-weight: 600;
}
.conversation-item {
  position: relative;
  min-width: 0;
  border-radius: 8px;
}
.conversation-item:hover,
.conversation-item.is-active {
  background: #eaeaec;
}

.conversation-link {
  width: 100%;
  min-width: 0;
  height: 39px;
  gap: 9px;
  padding: 0 10px;
  color: #555b65;
  font-size: 12px;
  text-align: left;
}

.conversation-link .el-icon {
  flex: 0 0 auto;
  color: #868c97;
}
.conversation-name {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.conversation-item.is-active .conversation-link {
  color: #252b36;
  font-weight: 600;
}

.conversation-actions {
  position: absolute;
  top: 0;
  right: 5px;
  height: 39px;
  display: none;
  align-items: center;
  gap: 1px;
  padding-left: 12px;
  background: linear-gradient(90deg, transparent, #eaeaec 18%);
}

.conversation-item:hover .conversation-actions,
.conversation-item:focus-within .conversation-actions {
  display: flex;
}

.conversation-item:not(.has-active-task):hover .conversation-status,
.conversation-item:not(.has-active-task):focus-within .conversation-status {
  visibility: hidden;
}

.conversation-status {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 4px;
  color: #8a909a;
  font-size: 9px;
  font-weight: 500;
  white-space: nowrap;
}

.conversation-status i {
  width: 6px;
  height: 6px;
  background: currentColor;
  border-radius: 50%;
}

.conversation-status.starting,
.conversation-status.streaming,
.conversation-status.stopping {
  color: #315ee7;
}

.conversation-status.starting i,
.conversation-status.streaming i {
  box-shadow: 0 0 0 0 rgb(49 94 231 / 30%);
  animation: generation-pulse 1.4s infinite;
}

.conversation-status.completed {
  color: #21855f;
}

.conversation-status.failed {
  color: #c05248;
}

@keyframes generation-pulse {
  65% {
    box-shadow: 0 0 0 4px rgb(49 94 231 / 0%);
  }

  100% {
    box-shadow: 0 0 0 0 rgb(49 94 231 / 0%);
  }
}
.conversation-actions button {
  width: 25px;
  height: 25px;
  display: grid;
  place-items: center;
  color: #7e848e;
  border-radius: 6px;
}
.conversation-actions button:hover {
  color: #2f3540;
  background: #dcdddf;
}
.conversation-empty {
  margin: 28px 12px;
  color: #999ea7;
  font-size: 12px;
  text-align: center;
}

.admin-entry {
  gap: 10px;
  margin: 4px 2px 0;
  padding: 11px 10px;
  color: #4f5662;
  border-top: 1px solid #e4e4e7;
}
.admin-entry > .el-icon:first-child {
  color: #767e8b;
  font-size: 17px;
}
.admin-entry span {
  min-width: 0;
  display: flex;
  flex: 1;
  flex-direction: column;
}
.admin-entry strong {
  font-size: 12px;
  font-weight: 650;
}
.admin-entry small {
  margin-top: 2px;
  color: #999ea7;
  font-size: 10px;
}
.admin-arrow {
  color: #a1a6af;
  font-size: 12px;
}

.chat-workspace {
  min-width: 0;
  display: flex;
  flex: 1;
  flex-direction: column;
}

.conversation-title {
  display: flex;
  align-items: center;
  flex-direction: column;
}
.conversation-title strong {
  max-width: min(480px, 45vw);
  overflow: hidden;
  font-size: 13px;
  font-weight: 650;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.conversation-title span {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 3px;
  color: #9297a1;
  font-size: 9px;
  letter-spacing: 0.2px;
}
.conversation-title i {
  width: 5px;
  height: 5px;
  background: #2baa78;
  border-radius: 50%;
}
.desktop-admin-entry,
.sidebar-trigger {
  position: absolute;
}
.desktop-admin-entry {
  right: 22px;
  gap: 6px;
  padding: 7px 10px;
  color: #5c6471;
  font-size: 11px;
  border: 1px solid #e3e5e9;
  border-radius: 8px;
}
.desktop-admin-entry:hover {
  color: var(--chat-blue);
  border-color: #cbd4f6;
}
.sidebar-trigger {
  top: 12px;
  left: 14px;
  z-index: 12;
  display: none;
  background: rgb(255 255 255 / 92%);
  border: 1px solid #e3e5e9;
  box-shadow: 0 4px 14px rgb(31 39 55 / 8%);
  backdrop-filter: blur(10px);
}

.message-viewport {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  scroll-behavior: smooth;
}
.loading-state {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
}
.loading-state span,
.thinking-state > span {
  width: 5px;
  height: 5px;
  background: #8d94a1;
  border-radius: 50%;
  animation: thinking 1.2s infinite ease-in-out;
}
.loading-state span:nth-child(2),
.thinking-state > span:nth-child(2) {
  animation-delay: 120ms;
}
.loading-state span:nth-child(3),
.thinking-state > span:nth-child(3) {
  animation-delay: 240ms;
}

@keyframes thinking {
  0%,
  70%,
  100% {
    opacity: 0.3;
    transform: translateY(0);
  }
  35% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

.welcome-state {
  width: min(760px, calc(100% - 40px));
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  margin: 0 auto;
  padding: 56px 0 40px;
  text-align: center;
}

.welcome-mark {
  position: relative;
  width: 48px;
  height: 48px;
  grid-template-columns: repeat(2, 6px);
  gap: 4px;
  margin-bottom: 22px;
  border-radius: 14px 14px 4px 14px;
  box-shadow: 0 12px 28px rgb(49 94 231 / 20%);
}
.welcome-mark i {
  width: 6px;
  height: 6px;
}
.welcome-mark::after {
  position: absolute;
  right: -3px;
  bottom: -3px;
  width: 10px;
  height: 10px;
  content: '';
  background: #50cda0;
  border: 3px solid #ffffff;
  border-radius: 50%;
}
.welcome-eyebrow {
  margin: 0 0 10px;
  color: var(--chat-blue);
  font-family: var(--font-data);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 1.5px;
}
.welcome-state h1 {
  margin: 0;
  color: #19202d;
  font-size: clamp(29px, 4vw, 42px);
  font-weight: 650;
  line-height: 1.25;
  letter-spacing: -1.6px;
}
.welcome-copy {
  max-width: 500px;
  margin: 15px 0 27px;
  color: #777e8a;
  font-size: 13px;
  line-height: 1.75;
}

.suggestion-grid {
  width: min(620px, 100%);
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 9px;
}
.suggestion-grid button {
  min-height: 74px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 14px;
  color: #525966;
  font-size: 11px;
  line-height: 1.5;
  text-align: left;
  background: #fafafa;
  border: 1px solid #e8e9ec;
  border-radius: 11px;
  transition:
    border-color 140ms ease,
    background 140ms ease,
    transform 140ms ease;
}
.suggestion-grid button:hover {
  background: #ffffff;
  border-color: #bdc9f7;
  transform: translateY(-2px);
}
.suggestion-grid .el-icon {
  flex: 0 0 auto;
  margin-top: 2px;
  color: #a3a8b1;
}

.message-list {
  width: min(820px, calc(100% - 40px));
  margin: 0 auto;
  padding: 34px 0 28px;
}
.turn + .turn {
  margin-top: 34px;
}
.user-row {
  display: flex;
  justify-content: flex-end;
  padding-left: 80px;
}
.user-message {
  max-width: 82%;
  padding: 11px 15px;
  color: #242a35;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f1f1f2;
  border-radius: 16px 16px 4px 16px;
}
.assistant-row {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 14px;
  margin-top: 22px;
}
.assistant-avatar {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  color: #ffffff;
  background: var(--chat-blue);
  border-radius: 9px 9px 3px 9px;
}
.assistant-avatar span {
  font-family: var(--font-data);
  font-size: 11px;
  font-weight: 700;
}
.assistant-content {
  min-width: 0;
  padding-top: 3px;
}
.answer-text {
  color: #303642;
  font-size: 13.5px;
  line-height: 1.78;
  overflow-wrap: anywhere;
}
.answer-heading {
  margin: 20px 0 7px;
  color: #252b36;
  font-size: 15px;
  font-weight: 700;
  line-height: 1.5;
}
.answer-text > .answer-heading:first-child {
  margin-top: 0;
}
.answer-paragraph {
  margin: 0 0 11px;
  white-space: pre-wrap;
}
.answer-paragraph:last-child {
  margin-bottom: 0;
}
.answer-list {
  margin: 5px 0 13px;
  padding-left: 21px;
}
.answer-list li {
  margin: 4px 0;
  padding-left: 2px;
}
.inline-code {
  padding: 2px 5px;
  color: #33405a;
  font-family: var(--font-data);
  font-size: 0.86em;
  background: #f2f3f5;
  border: 1px solid #e8e9ec;
  border-radius: 4px;
}
.answer-code {
  margin: 8px 0 14px;
  overflow-x: auto;
  padding: 13px 14px;
  color: #dce3f2;
  font-family: var(--font-data);
  font-size: 11px;
  line-height: 1.65;
  white-space: pre;
  background: #202633;
  border-radius: 9px;
  scrollbar-width: thin;
}
.answer-quote {
  margin: 8px 0 13px;
  padding: 7px 13px;
  color: #646c78;
  white-space: pre-wrap;
  background: #f7f8fa;
  border-left: 3px solid #acb9e7;
}
.answer-text.is-streaming::after {
  width: 2px;
  height: 15px;
  display: inline-block;
  margin-left: 3px;
  content: '';
  vertical-align: -2px;
  background: var(--chat-blue);
  animation: cursor 900ms infinite;
}
@keyframes cursor {
  50% {
    opacity: 0;
  }
}

.citation {
  display: inline-flex;
  margin: 0 2px;
  padding: 1px 5px;
  color: #3159ca;
  font-family: var(--font-data);
  font-size: 10px;
  font-weight: 700;
  line-height: 18px;
  vertical-align: 1px;
  background: var(--chat-blue-soft);
  border-radius: 5px;
}
.citation:hover {
  background: #dfe6ff;
}
.thinking-state {
  min-height: 32px;
  display: flex;
  align-items: center;
  gap: 5px;
  color: #858b96;
  font-size: 11px;
}
.thinking-state > span:nth-child(3) {
  margin-right: 5px;
}
.answer-error {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-top: 10px;
  padding: 10px 12px;
  color: #8c4c42;
  font-size: 11px;
  line-height: 1.5;
  background: #fff7f5;
  border-left: 2px solid #d97868;
}
.answer-error strong {
  font-size: 12px;
}

.answer-toolbar {
  min-height: 30px;
  display: flex;
  gap: 3px;
  margin-top: 12px;
  color: #8b919b;
}
.answer-toolbar > button,
.version-switcher button {
  min-width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 0 7px;
  color: #7d8490;
  font-size: 10px;
  border-radius: 6px;
}
.answer-toolbar > button:hover,
.version-switcher button:hover {
  color: #303744;
  background: #f3f4f5;
}
.answer-toolbar .source-button {
  color: #536aa8;
  background: #f5f7fd;
}
.model-label {
  align-self: center;
  margin-left: 5px;
  color: #a0a5ae;
  font-family: var(--font-data);
  font-size: 9px;
}
.version-switcher {
  display: flex;
  align-items: center;
  margin-left: auto;
}
.version-switcher span {
  min-width: 34px;
  color: #8c929c;
  font-family: var(--font-data);
  font-size: 9px;
  text-align: center;
}

.composer-area {
  position: relative;
  z-index: 8;
  flex: 0 0 auto;
  padding: 14px 22px 10px;
  background: linear-gradient(180deg, rgb(255 255 255 / 0%), #ffffff 24%);
}
.composer-shell {
  width: min(820px, 100%);
  min-height: 54px;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  margin: 0 auto;
  padding: 9px 9px 9px 15px;
  background: #ffffff;
  border: 1px solid #d8dbe1;
  border-radius: 15px;
  box-shadow: 0 8px 28px rgb(31 39 55 / 9%);
  transition:
    border-color 150ms ease,
    box-shadow 150ms ease;
}
.composer-shell:focus-within {
  border-color: #9fafea;
  box-shadow:
    0 8px 30px rgb(31 39 55 / 10%),
    0 0 0 3px rgb(49 94 231 / 7%);
}
.composer-shell :deep(.el-textarea) {
  display: flex;
  align-items: center;
}
.composer-shell :deep(.el-textarea__inner) {
  min-height: 34px !important;
  padding: 7px 0 5px;
  color: #272d38;
  font-size: 13px;
  line-height: 1.55;
  box-shadow: none;
  scrollbar-width: thin;
}
.composer-shell :deep(.el-textarea__inner::placeholder) {
  color: #9a9fa8;
}
.send-button {
  width: 36px;
  height: 36px;
  display: grid;
  flex: 0 0 36px;
  place-items: center;
  color: #ffffff;
  font-size: 17px;
  background: var(--chat-blue);
  border-radius: 10px;
  transition:
    background 140ms ease,
    transform 140ms ease;
}
.send-button:hover:not(:disabled) {
  background: #244fd4;
  transform: translateY(-1px);
}
.send-button:disabled {
  color: #b0b4bc;
  background: #eceef1;
  cursor: not-allowed;
}
.stop-button {
  background: #252b36;
}
.composer-area > p {
  margin: 7px 0 0;
  color: #a0a5ae;
  font-size: 9px;
  text-align: center;
}

.source-panel {
  position: relative;
  z-index: 25;
  width: 0;
  flex: 0 0 0;
  overflow: hidden;
  background: #fbfbfc;
  border-left: 0 solid var(--chat-line);
  transition:
    width 200ms ease,
    flex-basis 200ms ease,
    border-width 200ms ease;
}
.source-panel.is-open {
  width: 390px;
  flex-basis: 390px;
  border-left-width: 1px;
}
.source-panel header,
.source-intro,
.source-list {
  width: 389px;
}
.source-panel header {
  height: 69px;
  justify-content: space-between;
  padding: 0 18px 0 20px;
  background: #ffffff;
  border-bottom: 1px solid var(--chat-line);
}
.source-panel header > div {
  display: flex;
  flex-direction: column;
}
.source-panel header span {
  color: var(--chat-blue);
  font-family: var(--font-data);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 1.2px;
}
.source-panel h2 {
  margin: 3px 0 0;
  font-size: 15px;
}
.source-panel header button {
  width: 31px;
  height: 31px;
  display: grid;
  place-items: center;
  color: #727986;
  border-radius: 7px;
}
.source-panel header button:hover {
  background: #f1f2f4;
}
.source-intro {
  padding: 15px 20px;
  color: #737a86;
  font-size: 10px;
  line-height: 1.6;
  background: #f4f6fc;
  border-bottom: 1px solid #e6e9f3;
}
.source-intro span {
  color: #355bc7;
  font-family: var(--font-data);
  font-size: 11px;
  font-weight: 700;
}
.source-list {
  height: calc(100vh - 119px);
  height: calc(100dvh - 119px);
  overflow-y: auto;
  padding: 14px;
  scrollbar-width: thin;
}
.source-card {
  padding: 15px;
  background: #ffffff;
  border: 1px solid #e2e4e9;
  border-left: 3px solid #aebcf0;
  border-radius: 9px;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease;
  scroll-margin-block: 20px;
}
.source-card + .source-card {
  margin-top: 10px;
}
.source-card.is-highlighted {
  border-color: #7891e9;
  border-left-color: var(--chat-blue);
  box-shadow: 0 8px 24px rgb(49 94 231 / 9%);
}
.source-card-head {
  justify-content: space-between;
}
.source-id {
  padding: 3px 6px;
  color: #3159ca;
  font-family: var(--font-data);
  font-size: 9px;
  font-weight: 700;
  background: var(--chat-blue-soft);
  border-radius: 5px;
}
.source-lines {
  color: #9399a3;
  font-size: 9px;
}
.source-card h3 {
  margin: 11px 0 0;
  overflow: hidden;
  color: #303642;
  font-size: 12px;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.source-path {
  margin: 3px 0 11px;
  overflow: hidden;
  color: #8b919b;
  font-size: 9px;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.source-card blockquote {
  max-height: 190px;
  margin: 0;
  overflow-y: auto;
  padding: 10px 11px;
  color: #5f6672;
  font-size: 10px;
  line-height: 1.75;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f8f8f9;
  border-radius: 6px;
  scrollbar-width: thin;
}
.no-sources {
  display: flex;
  align-items: center;
  flex-direction: column;
  padding: 70px 20px;
  color: #a0a5ae;
  text-align: center;
}
.no-sources .el-icon {
  font-size: 28px;
}
.no-sources p {
  font-size: 11px;
}
.sidebar-scrim,
.source-scrim {
  display: none;
}

@media (max-width: 1180px) {
  .source-panel {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    width: min(390px, 88vw);
    flex-basis: auto;
    border-left-width: 1px;
    box-shadow: -18px 0 42px rgb(27 34 48 / 12%);
    transform: translateX(105%);
    transition: transform 200ms ease;
  }
  .source-panel.is-open {
    width: min(390px, 88vw);
    flex-basis: auto;
    transform: translateX(0);
  }
  .source-scrim {
    position: fixed;
    z-index: 24;
    inset: 0;
    display: block;
    background: rgb(18 23 32 / 20%);
    backdrop-filter: blur(1px);
  }
}

@media (max-width: 760px) {
  .chat-sidebar {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    width: min(286px, 86vw);
    transform: translateX(-105%);
    transition: transform 190ms ease;
  }
  .chat-sidebar.is-open {
    transform: translateX(0);
    box-shadow: 18px 0 42px rgb(27 34 48 / 16%);
  }
  .sidebar-scrim {
    position: fixed;
    z-index: 29;
    inset: 0;
    display: block;
    background: rgb(18 23 32 / 24%);
    backdrop-filter: blur(1px);
  }
  .mobile-close,
  .sidebar-trigger {
    width: 34px;
    height: 34px;
    display: grid;
    place-items: center;
    color: #626a77;
    border-radius: 8px;
  }
  .desktop-admin-entry {
    width: 34px;
    height: 34px;
    right: 12px;
    justify-content: center;
    padding: 0;
    font-size: 0;
  }
  .conversation-title strong {
    max-width: 54vw;
  }
  .welcome-state {
    width: min(600px, calc(100% - 28px));
    justify-content: flex-start;
    padding-top: clamp(54px, 12vh, 110px);
  }
  .welcome-state h1 {
    font-size: 30px;
  }
  .suggestion-grid {
    grid-template-columns: 1fr;
  }
  .suggestion-grid button {
    min-height: 52px;
    align-items: center;
  }
  .message-list {
    width: calc(100% - 28px);
    padding-top: 25px;
  }
  .user-row {
    padding-left: 36px;
  }
  .user-message {
    max-width: 92%;
  }
  .assistant-row {
    grid-template-columns: 28px minmax(0, 1fr);
    gap: 10px;
  }
  .assistant-avatar {
    width: 27px;
    height: 27px;
  }
  .answer-text {
    font-size: 13px;
  }
  .model-label {
    display: none;
  }
  .composer-area {
    padding: 10px 10px 7px;
  }
  .composer-area > p {
    display: none;
  }
  .source-panel header,
  .source-intro,
  .source-list {
    width: min(390px, 88vw);
  }
}
</style>
