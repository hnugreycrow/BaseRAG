<script setup lang="ts">
import {
  ArrowLeft,
  ArrowRight,
  CopyDocument,
  Document,
  Menu as MenuIcon,
  Position,
  RefreshRight,
  VideoPause,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Tokens } from 'marked'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  createConversation,
  deleteConversation,
  getConversation,
  getErrorMessage,
  listConversations,
  renameConversation,
  setConversationThinking,
  type AssistantMessage,
  type ConversationDetail,
  type ConversationSummary,
  type ConversationTurn,
} from '../api'
import AppSidebar from '../layout/AppSidebar.vue'
import ConversationHistory from '../components/conversation/ConversationHistory.vue'
import SourcePanel from '../components/conversation/SourcePanel.vue'
import { answerTable } from '../components/conversation/answerTable'
import { useConversationGenerationStore } from '../store'

const route = useRoute()
const router = useRouter()
const generationStore = useConversationGenerationStore()

const conversations = ref<ConversationSummary[]>([])
const conversation = ref<ConversationDetail | null>(null)
const searchQuery = ref('')
const draft = ref('')
const thinkingEnabled = ref(false)
const thinkingSaving = ref(false)
const listLoading = ref(false)
const detailLoading = ref(false)
const mobileSidebarOpen = ref(false)
const compactSidebar = ref(window.innerWidth <= 1000)
function updateSidebarWidth() {
  compactSidebar.value = window.innerWidth <= 1000
}
window.addEventListener('resize', updateSidebarWidth)
onBeforeUnmount(() => window.removeEventListener('resize', updateSidebarWidth))
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

const conversationTitle = computed(() => {
  const id = currentConversationId.value
  if (!id) return '新对话'
  if (conversation.value?.id === id) return conversation.value.title
  return conversations.value.find((item) => item.id === id)?.title ?? '加载中…'
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

interface AnswerBlock {
  type: 'paragraph' | 'heading' | 'unordered-list' | 'ordered-list' | 'quote' | 'code' | 'table'
  table?: Tokens.Table
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
    thinkingEnabled: thinkingEnabled.value,
    reasoningContent: '',
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
    thinkingEnabled.value = false
    detailLoading.value = false
    return
  }

  detailLoading.value = true
  try {
    const result = generationStore.mergeIntoDetail(await getConversation(id))
    if (currentConversationId.value !== id) return
    conversation.value = result
    thinkingEnabled.value = result.thinkingEnabled
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
  thinkingEnabled.value = false
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

    const table = answerTable(lines, index)
    if (table) {
      blocks.push({ type: 'table', table })
      index += table.raw.replace(/\n$/, '').split('\n').length
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
      !answerTable(lines, index) &&
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
  if (!content || thinkingSaving.value || generationStore.isActive(currentConversationId.value))
    return
  if (content.length > 2000) {
    ElMessage.warning('问题不能超过 2000 个字符')
    return
  }

  draft.value = ''
  let id = currentConversationId.value
  if (!id) {
    try {
      const created = await createConversation(content.slice(0, 28), thinkingEnabled.value)
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

async function toggleThinking() {
  if (sending.value || thinkingSaving.value || detailLoading.value) return
  const previous = thinkingEnabled.value
  thinkingEnabled.value = !previous
  const id = currentConversationId.value
  if (!id) return
  thinkingSaving.value = true
  try {
    const updated = await setConversationThinking(id, thinkingEnabled.value)
    if (currentConversationId.value === id) {
      thinkingEnabled.value = updated.thinkingEnabled
      if (conversation.value?.id === id)
        conversation.value.thinkingEnabled = updated.thinkingEnabled
    }
    const summary = conversations.value.find((item) => item.id === id)
    if (summary) summary.thinkingEnabled = updated.thinkingEnabled
  } catch (error) {
    if (currentConversationId.value === id) thinkingEnabled.value = previous
    ElMessage.error(getErrorMessage(error))
  } finally {
    thinkingSaving.value = false
  }
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
  () => currentTask.value?.assistant.reasoningContent.length,
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
    <el-drawer
      v-model="mobileSidebarOpen"
      direction="ltr"
      size="280px"
      :with-header="false"
      class="navigation-drawer"
      aria-label="导航"
    >
      <AppSidebar mode="chat" @navigate="mobileSidebarOpen = false">
        <ConversationHistory
          v-model:query="searchQuery"
          :groups="groupedConversations"
          :current-id="currentConversationId"
          :loading="listLoading"
          @new="startNewConversation"
          @manage="mobileSidebarOpen = false"
          @open="openConversation"
          @rename="editConversation"
          @remove="removeConversation"
        />
      </AppSidebar>
    </el-drawer>
    <AppSidebar class="chat-sidebar" mode="chat" :collapsed="compactSidebar">
      <ConversationHistory
        v-model:query="searchQuery"
        :groups="groupedConversations"
        :current-id="currentConversationId"
        :loading="listLoading"
        @new="startNewConversation"
        @open="openConversation"
        @rename="editConversation"
        @remove="removeConversation"
      />
    </AppSidebar>

    <main class="chat-workspace" :class="{ 'is-empty': isEmpty }">
      <header class="chat-topbar">
        <button
          class="history-toggle"
          type="button"
          aria-label="打开导航和会话"
          @click="mobileSidebarOpen = true"
        >
          <el-icon><MenuIcon /></el-icon>
        </button>
        <span class="chat-title" :title="conversationTitle">{{ conversationTitle }}</span>
      </header>
      <div ref="messageViewport" class="message-viewport">
        <div v-if="detailLoading" class="loading-state" aria-label="正在加载会话">
          <span></span><span></span><span></span>
        </div>

        <section v-else-if="isEmpty" class="welcome-state">
          <div class="welcome-mark" aria-hidden="true"><i></i><i></i><i></i><i></i></div>
          <h1>向知识库提问</h1>
          <p class="welcome-copy">我会根据已入库资料回答，并标注可核对的来源。</p>
        </section>

        <div v-else class="message-list">
          <article v-for="turn in conversation?.turns" :key="turn.user.id" class="turn">
            <div class="user-row">
              <div class="user-message">{{ turn.user.content }}</div>
            </div>

            <div v-if="currentAssistant(turn)" class="assistant-row">
              <div class="assistant-avatar" aria-hidden="true"><span>B</span></div>
              <div class="assistant-content">
                <div
                  v-if="
                    currentAssistant(turn)?.reasoningContent &&
                    ['PENDING', 'STREAMING'].includes(currentAssistant(turn)?.status || '')
                  "
                  class="reasoning-panel is-live"
                  aria-label="正在生成思考内容"
                >
                  <strong>深度思考中</strong>
                  <div class="reasoning-body">{{ currentAssistant(turn)?.reasoningContent }}</div>
                </div>
                <details
                  v-else-if="currentAssistant(turn)?.reasoningContent"
                  class="reasoning-panel"
                >
                  <summary>深度思考</summary>
                  <div class="reasoning-body">{{ currentAssistant(turn)?.reasoningContent }}</div>
                </details>
                <div
                  v-else-if="
                    currentAssistant(turn)?.thinkingEnabled &&
                    currentAssistant(turn)?.status === 'COMPLETED' &&
                    currentAssistant(turn)?.modelInfo
                  "
                  class="reasoning-unavailable"
                >
                  本次模型未返回思考内容
                </div>
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

                    <div
                      v-else-if="block.type === 'table' && block.table"
                      class="answer-table-scroll"
                      role="region"
                      aria-label="回答表格"
                      tabindex="0"
                    >
                      <table class="answer-table">
                        <component
                          :is="sectionIndex === 0 ? 'thead' : 'tbody'"
                          v-for="(rows, sectionIndex) in [[block.table.header], block.table.rows]"
                          :key="sectionIndex"
                        >
                          <tr v-for="(row, rowIndex) in rows" :key="rowIndex">
                            <component
                              :is="sectionIndex === 0 ? 'th' : 'td'"
                              v-for="(cell, cellIndex) in row"
                              :key="cellIndex"
                              :scope="sectionIndex === 0 ? 'col' : undefined"
                              :style="{ textAlign: block.table.align[cellIndex] || 'left' }"
                            >
                              <template
                                v-for="(part, index) in inlineParts(cell.text)"
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
                          </tr>
                        </component>
                      </table>
                    </div>

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
                  <small v-if="currentAssistant(turn)?.errorCode">
                    错误代码：{{ currentAssistant(turn)?.errorCode }}
                  </small>
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
                    v-if="currentAssistant(turn)?.citations.length"
                    type="button"
                    class="source-button"
                    @click="openSources(currentAssistant(turn)!)"
                  >
                    <el-icon><Document /></el-icon>
                    {{ currentAssistant(turn)?.citations.length }} 个来源
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
          <div class="composer-actions">
            <button
              type="button"
              class="thinking-toggle"
              :class="{ 'is-enabled': thinkingEnabled }"
              :disabled="sending || thinkingSaving || detailLoading"
              :aria-pressed="thinkingEnabled"
              aria-label="深度思考"
              @click="toggleThinking"
            >
              <span class="thinking-toggle-mark" aria-hidden="true">✦</span>
              深度思考
            </button>
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
              :disabled="!draft.trim() || thinkingSaving"
              aria-label="发送问题"
              @click="submitQuestion"
            >
              <el-icon><Position /></el-icon>
            </button>
          </div>
        </div>
        <p>Enter 发送 · Shift + Enter 换行</p>
      </footer>
    </main>

    <SourcePanel
      v-model="sourcePanelOpen"
      :message="sourceMessage"
      :conversation-id="currentConversationId"
      :highlighted="highlightedCitation"
    />
  </div>
</template>

<style scoped src="../components/conversation/conversation-view.css"></style>
