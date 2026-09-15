<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElConfigProvider, ElMessage } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import {
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  ChatDotRound,
  Close,
  Collection,
  CopyDocument,
  Cpu,
  Document,
  Fold,
  HomeFilled,
  Lock,
  Menu,
  MoreFilled,
  Plus,
  Position,
  Refresh,
  Search,
  Setting,
  Share,
  Upload,
  User,
  VideoPause,
  Warning,
} from '@element-plus/icons-vue'
import {
  documents,
  initialLibraries,
  initialUsers,
  runs,
  sources,
  stages,
  type Resource,
} from './data'

type Page = 'chat' | 'dashboard' | 'knowledge' | 'traces' | 'trace' | 'models' | 'users' | 'login'
type Scene = 'normal' | 'loading' | 'empty' | 'error'
const nav = [
  { id: 'chat' as Page, label: '知识问答', icon: ChatDotRound },
  { id: 'dashboard' as Page, label: '工作台', icon: HomeFilled },
  { id: 'knowledge' as Page, label: '知识库', icon: Collection },
  { id: 'traces' as Page, label: '链路追踪', icon: Share },
  { id: 'models' as Page, label: '模型', icon: Cpu },
  { id: 'users' as Page, label: '用户管理', icon: User },
]
const page = ref<Page>('chat')
const role = ref('admin')
const scene = ref<Scene>('normal')
const controlsOpen = ref(false)
const mobileNav = ref(false)
const mobileViewport = ref(window.innerWidth <= 600)
const collapsed = ref(false)
const historyOpen = ref(false)
const navElement = ref<HTMLElement>()
watch(mobileNav, async (open) => {
  await nextTick()
  if (open) navElement.value?.querySelector<HTMLButtonElement>('button')?.focus()
  else document.querySelector<HTMLButtonElement>('.mobile-menu')?.focus()
})
function handleNavKey(event: KeyboardEvent) {
  if (!mobileNav.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    mobileNav.value = false
    return
  }
  if (event.key !== 'Tab') return
  const buttons = Array.from(
    navElement.value?.querySelectorAll<HTMLElement>('button, input, [tabindex="0"]') ?? [],
  ).filter((element) => element.offsetParent !== null)
  const first = buttons[0]
  const last = buttons[buttons.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last?.focus()
  }
  if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  }
}
const pageTitle = computed(() =>
  page.value === 'trace'
    ? '运行详情'
    : (nav.find((item) => item.id === page.value)?.label ?? '登录'),
)
const visibleNav = computed(() =>
  nav.filter((item) => item.id !== 'users' || role.value === 'admin'),
)
function go(target: Page) {
  page.value = target
  scene.value = 'normal'
  mobileNav.value = false
  historyOpen.value = false
  sourceOpen.value = false
  query.value = ''
  currentPage.value = 1
}
watch(role, () => {
  if (role.value !== 'admin' && page.value === 'users') go('chat')
  userFilter.value = ''
})

const conversationQuery = ref('')
const conversations = ref([
  { id: 1, title: '如何让上传的文档参与问答？' },
  { id: 2, title: '整理知识库的使用流程' },
  { id: 3, title: '检索结果有哪些引用信息？' },
])
interface Turn {
  question: string
  answer: string
  versions: string[]
  version: number
  state: 'done' | 'generating' | 'cancelled' | 'error'
}
const answer = '文档处理完成后，就可以直接在这里提问。你可以按下面的步骤检查：'
const histories = ref<Record<number, Turn[]>>({
  1: [
    {
      question: '如何让上传的文档参与问答？',
      answer,
      versions: [answer],
      version: 0,
      state: 'done',
    },
  ],
  2: [],
  3: [],
})
const currentConversation = ref(1)
const turns = computed(() => histories.value[currentConversation.value] ?? [])
const conversationTitle = computed(
  () =>
    conversations.value.find((item) => item.id === currentConversation.value)?.title ?? '新对话',
)
const filteredConversations = computed(() =>
  conversations.value.filter((item) => item.title.includes(conversationQuery.value)),
)
const input = ref('')
const generating = ref(false)
const messageViewport = ref<HTMLElement>()
let generationTimer: ReturnType<typeof setInterval> | undefined
let activeTurn: Turn | undefined
function stopGeneration() {
  clearInterval(generationTimer)
  if (activeTurn?.state === 'generating') activeTurn.state = 'cancelled'
  generating.value = false
}
onBeforeUnmount(stopGeneration)
function newConversation() {
  stopGeneration()
  const id = Date.now()
  conversations.value.unshift({ id, title: '新对话' })
  histories.value[id] = []
  currentConversation.value = id
  go('chat')
  input.value = ''
}
function openConversation(id: number) {
  stopGeneration()
  currentConversation.value = id
  go('chat')
}
async function scrollBottom() {
  await nextTick()
  messageViewport.value?.scrollTo({ top: messageViewport.value.scrollHeight })
}
function generate(turn: Turn, retry = false) {
  stopGeneration()
  turn.state = 'generating'
  turn.answer = ''
  activeTurn = turn
  generating.value = true
  const text = retry
    ? '可以。先确认文档状态为“可检索”，然后发起问题。回答中的编号可以打开原文，帮助你核对结论。'
    : answer
  let cursor = 0
  generationTimer = setInterval(() => {
    cursor += 2
    turn.answer = text.slice(0, cursor)
    void scrollBottom()
    if (cursor >= text.length) {
      clearInterval(generationTimer)
      turn.state = 'done'
      turn.versions.push(text)
      turn.version = turn.versions.length - 1
      generating.value = false
    }
  }, 65)
}
function send() {
  if (!input.value.trim() || generating.value) return
  scene.value = 'normal'
  const list =
    histories.value[currentConversation.value] ?? (histories.value[currentConversation.value] = [])
  const question = input.value.trim()
  list.push({ question, answer: '', versions: [], version: 0, state: 'generating' })
  const conversation = conversations.value.find((item) => item.id === currentConversation.value)
  if (conversation?.title === '新对话') conversation.title = question
  input.value = ''
  generate(list[list.length - 1]!)
  void scrollBottom()
}
function switchVersion(turn: Turn, offset: number) {
  turn.version += offset
  turn.answer = turn.versions[turn.version] ?? ''
}
async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择文字复制')
  }
}
const sourceOpen = ref(false)
const desktopSource = ref(window.innerWidth >= 1100)
function updateViewport() {
  desktopSource.value = window.innerWidth >= 1100
  mobileViewport.value = window.innerWidth <= 600
}
window.addEventListener('resize', updateViewport)
onBeforeUnmount(() => window.removeEventListener('resize', updateViewport))
const activeSource = ref(0)
function showSource(index: number) {
  selectedChunk.value = null
  activeSource.value = index
  sourceOpen.value = true
  nextTick(() => document.getElementById(`source-${index}`)?.scrollIntoView({ block: 'nearest' }))
}

const query = ref('')
const currentPage = ref(1)
const pageSize = 5
const libraries = ref(initialLibraries.map((item) => ({ ...item })))
const docsByLibrary = ref<Record<number, Resource[]>>(
  Object.fromEntries(
    initialLibraries.map((item) => [
      item.id,
      Array.from({ length: item.count }, (_, index) => ({
        ...documents[index % documents.length]!,
        id: item.id * 100 + index,
        name:
          index < documents.length
            ? documents[index]!.name
            : `${item.name}补充资料-${index + 1}.md`,
      })),
    ]),
  ),
)
const library = ref<Resource | null>(null)
const selectedDocument = ref<Resource | null>(null)
const selectedChunk = ref<Resource | null>(null)
const chunks = computed<Resource[]>(() =>
  Array.from({ length: selectedDocument.value?.count ?? 0 }, (_, index) => ({
    id: index + 1,
    name: sources[index % sources.length]!.text,
    count: 180 + index * 12,
    status: '可检索',
    date: `第 ${index + 1} 块`,
  })),
)
const resourceRows = computed(() =>
  selectedDocument.value
    ? chunks.value
    : library.value
      ? (docsByLibrary.value[library.value.id] ?? [])
      : libraries.value,
)
const filteredResources = computed(() =>
  resourceRows.value.filter((item) => item.name.includes(query.value)),
)
const resourcePage = computed(() =>
  filteredResources.value.slice((currentPage.value - 1) * pageSize, currentPage.value * pageSize),
)
const resourceTitle = computed(
  () => selectedDocument.value?.name ?? library.value?.name ?? '知识库',
)
function openResource(item: Resource) {
  if (selectedDocument.value) {
    selectedChunk.value = item
    activeSource.value = (item.id - 1) % sources.length
    sourceOpen.value = true
    return
  }
  if (library.value) selectedDocument.value = item
  else library.value = item
  query.value = ''
  currentPage.value = 1
}
function resourceBack() {
  if (selectedDocument.value) selectedDocument.value = null
  else library.value = null
  query.value = ''
  currentPage.value = 1
}
const fileInput = ref<HTMLInputElement>()
function upload(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? [])
  if (!library.value || !files.length) return
  const accepted = files.filter((file) => file.name.toLowerCase().endsWith('.md'))
  if (accepted.length !== files.length) ElMessage.warning('请选择 Markdown（.md）文件')
  const list = docsByLibrary.value[library.value.id] ?? (docsByLibrary.value[library.value.id] = [])
  accepted.forEach((file, index) =>
    list.unshift({
      id: Date.now() + index,
      name: file.name,
      count: 0,
      status: '处理中',
      date: '刚刚',
    }),
  )
  library.value.count = list.length
  if (accepted.length) ElMessage.success(`已模拟上传 ${accepted.length} 份文档`)
  ;(event.target as HTMLInputElement).value = ''
}

const statusFilter = ref('')
const modelFilter = ref('')
const userFilter = ref('')
const modeFilter = ref('')
const timeFilter = ref('24h')
const filteredRuns = computed(() =>
  runs.filter(
    (run, index) =>
      (!statusFilter.value || run.status === statusFilter.value) &&
      (!modelFilter.value || run.model === modelFilter.value) &&
      (!modeFilter.value || run.mode === modeFilter.value) &&
      (!userFilter.value || run.user === userFilter.value) &&
      (role.value === 'admin' || run.user === '陈默') &&
      (timeFilter.value !== '1h' || index < 5),
  ),
)
const runPage = computed(() =>
  filteredRuns.value.slice((currentPage.value - 1) * pageSize, currentPage.value * pageSize),
)
const selectedRun = ref(runs[0]!)
const successful = computed(() => filteredRuns.value.filter((run) => run.status === '成功').length)
const successRate = computed(() =>
  filteredRuns.value.length
    ? `${Math.round((successful.value / filteredRuns.value.length) * 100)}%`
    : '—',
)
const latency = computed(() => {
  const values = filteredRuns.value
    .flatMap((run) => (run.first === null ? [] : [run.first]))
    .sort((a, b) => a - b)
  return values.length ? `${values[Math.floor(values.length / 2)]!.toFixed(2)} s` : '—'
})
function openRun(run: (typeof runs)[number]) {
  selectedRun.value = run
  go('trace')
}
function resetFilters() {
  statusFilter.value = ''
  modelFilter.value = ''
  userFilter.value = ''
  modeFilter.value = ''
  timeFilter.value = '24h'
}
watch(
  [query, statusFilter, modelFilter, userFilter, modeFilter, timeFilter],
  () => (currentPage.value = 1),
)
const users = ref(initialUsers.map((item) => ({ ...item })))
const filteredUsers = computed(() =>
  users.value.filter((item) => `${item.name} ${item.username}`.includes(query.value)),
)
const userPage = computed(() =>
  filteredUsers.value.slice((currentPage.value - 1) * pageSize, currentPage.value * pageSize),
)

type DialogKind =
  | 'createLibrary'
  | 'renameResource'
  | 'deleteResource'
  | 'renameChat'
  | 'deleteChat'
  | 'createUser'
  | 'resetPassword'
  | 'password'
  | 'toggleUser'
const dialog = ref(false)
const dialogKind = ref<DialogKind>('createLibrary')
const form = ref({
  name: '',
  username: '',
  password: '',
  oldPassword: '',
  confirm: '',
  role: '普通用户',
})
const formError = ref('')
let resourceTarget: Resource | undefined
let chatTarget: (typeof conversations.value)[number] | undefined
let userTarget: (typeof users.value)[number] | undefined
const titles: Record<DialogKind, string> = {
  createLibrary: '新建知识库',
  renameResource: '重命名',
  deleteResource: '删除确认',
  renameChat: '重命名会话',
  deleteChat: '删除会话',
  createUser: '创建用户',
  resetPassword: '重置密码',
  password: '修改密码',
  toggleUser: '更改用户状态',
}
const destructive = computed(() => ['deleteResource', 'deleteChat'].includes(dialogKind.value))
function openDialog(
  kind: DialogKind,
  target?: Resource | (typeof conversations.value)[number] | (typeof users.value)[number],
) {
  dialogKind.value = kind
  resourceTarget = kind.endsWith('Resource') ? (target as Resource) : undefined
  chatTarget = kind.endsWith('Chat') ? (target as typeof chatTarget) : undefined
  userTarget = ['resetPassword', 'toggleUser'].includes(kind)
    ? (target as typeof userTarget)
    : undefined
  form.value = {
    name: resourceTarget?.name ?? chatTarget?.title ?? '',
    username: '',
    password: '',
    oldPassword: '',
    confirm: '',
    role: '普通用户',
  }
  formError.value = ''
  dialog.value = true
}
function submitDialog() {
  const kind = dialogKind.value
  const value = form.value
  if (
    ['createLibrary', 'renameResource', 'renameChat', 'createUser'].includes(kind) &&
    !value.name.trim()
  ) {
    formError.value = '请输入名称'
    return
  }
  if (['createUser', 'resetPassword', 'password'].includes(kind) && value.password.length < 8) {
    formError.value = '密码至少需要 8 位'
    return
  }
  if (kind === 'createUser' && !value.username.trim()) {
    formError.value = '请输入用户名'
    return
  }
  if (
    kind === 'createUser' &&
    users.value.some((user) => user.username === value.username.trim())
  ) {
    formError.value = '该用户名已存在'
    return
  }
  if (kind === 'password' && (!value.oldPassword || value.confirm !== value.password)) {
    formError.value = !value.oldPassword ? '请输入当前密码' : '两次新密码不一致'
    return
  }
  if (kind === 'createLibrary')
    libraries.value.unshift({
      id: Date.now(),
      name: value.name.trim(),
      count: 0,
      status: '可检索',
      date: '刚刚',
    })
  if (kind === 'renameResource' && resourceTarget) resourceTarget.name = value.name.trim()
  if (kind === 'deleteResource' && resourceTarget) {
    if (library.value) {
      docsByLibrary.value[library.value.id] = resourceRows.value.filter(
        (item) => item.id !== resourceTarget!.id,
      )
      library.value.count = docsByLibrary.value[library.value.id]!.length
    } else libraries.value = libraries.value.filter((item) => item.id !== resourceTarget!.id)
  }
  if (kind === 'renameChat' && chatTarget) chatTarget.title = value.name.trim()
  if (kind === 'deleteChat' && chatTarget) {
    conversations.value = conversations.value.filter((item) => item.id !== chatTarget!.id)
    delete histories.value[chatTarget.id]
    if (currentConversation.value === chatTarget.id) {
      stopGeneration()
      if (conversations.value.length) currentConversation.value = conversations.value[0]!.id
      else newConversation()
    }
  }
  if (kind === 'createUser')
    users.value.unshift({
      id: Date.now(),
      name: value.name.trim(),
      username: value.username.trim(),
      role: value.role,
      enabled: true,
      date: '尚未登录',
    })
  if (kind === 'toggleUser' && userTarget) userTarget.enabled = !userTarget.enabled
  currentPage.value = 1
  dialog.value = false
  ElMessage.success(
    kind === 'resetPassword' || kind === 'password'
      ? '密码已模拟更新'
      : destructive.value
        ? '已删除'
        : '已保存',
  )
}
const loginName = ref('')
const loginPassword = ref('')
const loginError = ref('')
function login() {
  if (!loginName.value.trim() || !loginPassword.value) {
    loginError.value = '请输入用户名和密码'
    return
  }
  if (scene.value === 'error') {
    loginError.value = '用户名或密码错误'
    return
  }
  loginError.value = ''
  go('chat')
  ElMessage.success('已进入示例工作区')
}
function refresh() {
  scene.value = 'normal'
  ElMessage.success('示例数据已刷新')
}
</script>

<template>
  <ElConfigProvider :locale="zhCn">
    <div class="prototype-root" :class="{ 'source-visible': sourceOpen && page === 'chat' }">
      <div v-if="page === 'login'" class="login-screen">
        <form class="login-card" @submit.prevent="login">
          <div class="login-brand">
            <span class="brand-symbol"><Collection /></span>BaseRAG
          </div>
          <h1>登录</h1>
          <label
            >用户名<el-input v-model="loginName" autocomplete="username" placeholder="输入用户名"
          /></label>
          <label
            >密码<el-input
              v-model="loginPassword"
              type="password"
              autocomplete="current-password"
              show-password
              placeholder="输入密码"
          /></label>
          <p v-if="loginError" role="alert" class="error-text">{{ loginError }}</p>
          <el-button type="primary" native-type="submit" class="login-submit">登录</el-button>
        </form>
      </div>

      <div v-else class="app-shell" :class="{ 'nav-collapsed': collapsed }">
        <button
          v-if="mobileNav"
          class="nav-backdrop"
          aria-label="关闭导航"
          @click="mobileNav = false"
        ></button>
        <aside
          ref="navElement"
          :inert="mobileViewport && !mobileNav"
          class="app-nav"
          :class="{ 'mobile-open': mobileNav }"
          aria-label="主导航"
          @keydown="handleNavKey"
        >
          <button class="brand" aria-label="BaseRAG 首页" @click="go('chat')">
            <span class="brand-symbol"><Collection /></span><strong>BaseRAG</strong>
          </button>
          <button
            class="mobile-nav-close icon-button"
            aria-label="关闭导航"
            @click="mobileNav = false"
          >
            <Close />
          </button>
          <nav>
            <button
              v-for="item in visibleNav"
              :key="item.id"
              class="nav-item"
              :class="{ active: page === item.id || (page === 'trace' && item.id === 'traces') }"
              :aria-current="page === item.id ? 'page' : undefined"
              :title="item.label"
              @click="go(item.id)"
            >
              <component :is="item.icon" /><span>{{ item.label }}</span>
            </button>
          </nav>
          <section v-if="page === 'chat'" class="nav-conversations">
            <div class="history-heading">
              <span>最近会话</span
              ><button
                class="icon-button"
                title="新对话"
                aria-label="新对话"
                @click="newConversation"
              >
                <Plus />
              </button>
            </div>
            <el-input
              v-model="conversationQuery"
              :prefix-icon="Search"
              placeholder="搜索会话"
              aria-label="搜索会话"
              clearable
            />
            <div class="history-list">
              <div
                v-for="conversation in filteredConversations"
                :key="conversation.id"
                class="history-row"
                :class="{ selected: conversation.id === currentConversation }"
              >
                <button
                  class="history-title"
                  :title="conversation.title"
                  @click="openConversation(conversation.id)"
                >
                  {{ conversation.title }}
                </button>
                <el-dropdown
                  trigger="click"
                  @command="(command: DialogKind) => openDialog(command, conversation)"
                  ><button class="icon-button" :aria-label="`管理会话：${conversation.title}`">
                    <MoreFilled /></button
                  ><template #dropdown
                    ><el-dropdown-menu
                      ><el-dropdown-item command="renameChat">重命名</el-dropdown-item
                      ><el-dropdown-item command="deleteChat"
                        >删除</el-dropdown-item
                      ></el-dropdown-menu
                    ></template
                  ></el-dropdown
                >
              </div>
              <p v-if="!filteredConversations.length" class="muted small">没有匹配的会话</p>
            </div>
          </section>
          <div class="account-area">
            <el-dropdown
              trigger="click"
              @command="
                (command: string) => (command === 'logout' ? go('login') : openDialog('password'))
              "
            >
              <button class="account-button" aria-label="账号菜单">
                <span class="avatar">{{ role === 'admin' ? '林' : '陈' }}</span
                ><span class="account-name"
                  >{{ role === 'admin' ? '林晓' : '陈默'
                  }}<small>{{ role === 'admin' ? '管理员' : '普通用户' }}</small></span
                ><ArrowDown />
              </button>
              <template #dropdown
                ><el-dropdown-menu
                  ><el-dropdown-item command="password">修改密码</el-dropdown-item
                  ><el-dropdown-item command="logout" divided
                    >退出登录</el-dropdown-item
                  ></el-dropdown-menu
                ></template
              >
            </el-dropdown>
          </div>
        </aside>

        <div class="app-workspace">
          <header class="app-topbar">
            <div class="topbar-title">
              <button
                class="icon-button desktop-fold"
                aria-label="折叠导航"
                @click="collapsed = !collapsed"
              >
                <Fold /></button
              ><button
                class="icon-button mobile-menu"
                aria-label="打开导航"
                @click="mobileNav = true"
              >
                <Menu /></button
              ><span>{{ pageTitle }}</span>
            </div>
            <el-button
              v-if="page === 'chat'"
              class="tablet-history"
              :icon="ChatDotRound"
              @click="historyOpen = true"
              >会话</el-button
            >
            <span v-if="page === 'chat'" class="topbar-context">全部知识库</span>
            <el-button v-if="page === 'chat'" :icon="Plus" @click="newConversation"
              >新对话</el-button
            >
            <span v-else class="topbar-context">{{
              role === 'admin' ? '个人工作区' : '陈默的工作区'
            }}</span>
          </header>

          <main v-if="page === 'chat'" class="chat-main">
            <div ref="messageViewport" class="message-scroll">
              <el-skeleton
                v-if="scene === 'loading'"
                class="chat-skeleton"
                :rows="8"
                animated
                aria-label="正在加载会话"
              />
              <div v-else-if="scene === 'error'" class="state-panel">
                <Warning />
                <h2>会话加载失败</h2>
                <p>请重试加载。</p>
                <el-button @click="refresh">重新加载</el-button>
              </div>
              <div v-else-if="scene === 'empty' || !turns.length" class="chat-welcome">
                <span class="welcome-icon"><ChatDotRound /></span>
                <h1>有什么想了解的？</h1>
                <p>从你的知识库中寻找答案</p>
                <div class="suggestions">
                  <button @click="input = '如何让上传的文档参与问答？'">
                    了解文档处理流程<ArrowRight /></button
                  ><button @click="input = '如何核对回答中的引用来源？'">
                    核对回答来源<ArrowRight />
                  </button>
                </div>
              </div>
              <div v-else class="chat-thread">
                <div class="conversation-caption">{{ conversationTitle }}</div>
                <article v-for="(turn, index) in turns" :key="index" class="message-turn">
                  <div class="question-bubble">{{ turn.question }}</div>
                  <div class="answer-heading">
                    <span class="assistant-avatar"><Collection /></span><strong>BaseRAG</strong
                    ><span class="muted small">{{
                      turn.state === 'generating' ? '正在回答' : '基于知识库'
                    }}</span>
                  </div>
                  <div class="answer-body" aria-live="polite">
                    <p>
                      {{ turn.answer
                      }}<span v-if="turn.state === 'generating'" class="typing-caret">▍</span>
                    </p>
                    <template v-if="turn.state === 'done'">
                      <ol class="answer-steps">
                        <li>
                          <strong>上传文档</strong>
                          <p>
                            进入知识库，上传需要使用的 Markdown 文件。<button
                              class="citation"
                              aria-label="查看来源 1"
                              @click="showSource(0)"
                            >
                              1
                            </button>
                          </p>
                        </li>
                        <li>
                          <strong>确认处理完成</strong>
                          <p>
                            等待解析、分块和向量化完成，文档状态会更新为“可检索”。<button
                              class="citation"
                              aria-label="查看来源 2"
                              @click="showSource(1)"
                            >
                              2
                            </button>
                          </p>
                        </li>
                        <li>
                          <strong>提问并查看来源</strong>
                          <p>
                            回到知识问答输入问题。点击回答中的引用编号，可以核对原文。<button
                              class="citation"
                              aria-label="查看来源 3"
                              @click="showSource(2)"
                            >
                              3
                            </button>
                          </p>
                        </li>
                      </ol>
                      <div class="answer-note">如果文档处理失败，可以在文档列表中重新处理。</div>
                    </template>
                    <p v-if="turn.state === 'cancelled'" class="muted small">已停止生成</p>
                    <p v-if="turn.state === 'error'" class="error-text">回答生成失败，请重试。</p>
                  </div>
                  <div v-if="turn.state !== 'generating'" class="answer-actions">
                    <button
                      class="icon-button"
                      aria-label="复制回答"
                      title="复制回答"
                      @click="copy(turn.answer)"
                    >
                      <CopyDocument /></button
                    ><button
                      class="icon-button"
                      aria-label="重新生成"
                      title="重新生成"
                      :disabled="generating"
                      @click="generate(turn, true)"
                    >
                      <Refresh /></button
                    ><button class="text-action" @click="showSource(0)"><Document />3 个来源</button
                    ><span v-if="turn.versions.length > 1" class="version-controls"
                      ><button
                        class="icon-button"
                        aria-label="上一版本"
                        :disabled="turn.version === 0"
                        @click="switchVersion(turn, -1)"
                      >
                        <ArrowLeft /></button
                      >{{ turn.version + 1 }} / {{ turn.versions.length
                      }}<button
                        class="icon-button"
                        aria-label="下一版本"
                        :disabled="turn.version === turn.versions.length - 1"
                        @click="switchVersion(turn, 1)"
                      >
                        <ArrowRight /></button></span
                    ><button
                      v-if="turn.state === 'cancelled' || turn.state === 'error'"
                      class="text-action"
                      @click="generate(turn, true)"
                    >
                      重试
                    </button>
                  </div>
                </article>
              </div>
            </div>
            <div class="composer-area">
              <form class="composer" @submit.prevent="send">
                <textarea
                  v-model="input"
                  rows="2"
                  aria-label="输入问题"
                  placeholder="向知识库提问…"
                  @keydown.enter.exact="
                    (event) => {
                      if (!event.isComposing) {
                        event.preventDefault()
                        send()
                      }
                    }
                  "
                ></textarea>
                <div class="composer-toolbar">
                  <span><Collection />全部知识库</span
                  ><button
                    v-if="generating"
                    class="send-button"
                    aria-label="停止生成"
                    @click.prevent="stopGeneration"
                  >
                    <VideoPause /></button
                  ><button
                    v-else
                    class="send-button"
                    type="submit"
                    aria-label="发送问题"
                    :disabled="!input.trim()"
                  >
                    <Position />
                  </button>
                </div>
              </form>
              <p class="composer-hint">Enter 发送 · Shift + Enter 换行</p>
            </div>
          </main>

          <main v-else class="content-page">
            <template v-if="page === 'dashboard'">
              <div class="page-heading">
                <div>
                  <h1>工作台</h1>
                  <p class="muted">{{ role === 'admin' ? '林晓' : '陈默' }}，欢迎回来</p>
                </div>
                <el-button type="primary" :icon="Plus" @click="newConversation">新对话</el-button>
              </div>
              <div class="quick-links">
                <button @click="go('chat')">
                  <ChatDotRound /><strong>开始提问</strong><ArrowRight /></button
                ><button @click="go('knowledge')">
                  <Collection /><strong>管理知识库</strong><ArrowRight /></button
                ><button @click="go('traces')">
                  <Share /><strong>查看链路</strong><ArrowRight />
                </button>
              </div>
            </template>

            <template v-if="page === 'knowledge'">
              <button v-if="library" class="back-link" @click="resourceBack">
                <ArrowLeft />{{ selectedDocument ? library.name : '全部知识库' }}
              </button>
              <div class="page-heading">
                <div class="heading-copy">
                  <h1>{{ resourceTitle }}</h1>
                  <span class="muted small"
                    >{{ selectedDocument ? '分块' : library ? '文档' : '知识库' }} ·
                    {{ resourceRows.length }}</span
                  >
                </div>
                <el-button
                  v-if="!library"
                  type="primary"
                  :icon="Plus"
                  @click="openDialog('createLibrary')"
                  >新建知识库</el-button
                ><el-button
                  v-else-if="!selectedDocument"
                  type="primary"
                  :icon="Upload"
                  @click="fileInput?.click()"
                  >上传文档</el-button
                >
              </div>
              <input ref="fileInput" type="file" accept=".md" multiple hidden @change="upload" />
            </template>

            <template v-if="page === 'traces'">
              <div class="page-heading">
                <h1>链路追踪</h1>
                <el-button :icon="Refresh" @click="refresh">刷新</el-button>
              </div>
              <div class="metrics">
                <div>
                  <span>问答次数</span
                  ><strong>{{ scene === 'empty' ? '0' : filteredRuns.length }}</strong>
                </div>
                <div>
                  <span>成功率</span><strong>{{ scene === 'empty' ? '—' : successRate }}</strong>
                </div>
                <div>
                  <span>首字耗时 · P50</span
                  ><strong>{{ scene === 'empty' ? '—' : latency }}</strong>
                </div>
                <div>
                  <span>降级次数</span
                  ><strong>{{
                    scene === 'empty' ? '0' : filteredRuns.filter((run) => run.degraded).length
                  }}</strong>
                </div>
              </div>
              <div class="filters">
                <el-select v-model="timeFilter" aria-label="时间范围"
                  ><el-option label="最近 1 小时" value="1h" /><el-option
                    label="最近 24 小时"
                    value="24h" /><el-option label="最近 7 天" value="7d" /></el-select
                ><el-select
                  v-model="statusFilter"
                  placeholder="全部状态"
                  aria-label="状态筛选"
                  clearable
                  ><el-option
                    v-for="status in ['成功', '失败', '已取消', '生成中']"
                    :key="status"
                    :label="status"
                    :value="status" /></el-select
                ><el-select
                  v-model="modelFilter"
                  placeholder="全部模型"
                  aria-label="模型筛选"
                  clearable
                  ><el-option
                    v-for="model in ['Qwen3', 'DeepSeek V3']"
                    :key="model"
                    :label="model"
                    :value="model" /></el-select
                ><el-select
                  v-model="modeFilter"
                  placeholder="全部流程"
                  aria-label="流程筛选"
                  clearable
                  ><el-option label="快速回答" value="快速回答" /><el-option
                    label="完整流程"
                    value="完整流程" /></el-select
                ><el-select
                  v-if="role === 'admin'"
                  v-model="userFilter"
                  placeholder="全部用户"
                  aria-label="用户筛选"
                  clearable
                  ><el-option label="林晓" value="林晓" /><el-option
                    label="陈默"
                    value="陈默" /></el-select
                ><el-button text @click="resetFilters">重置</el-button>
              </div>
            </template>

            <template v-if="page === 'trace'">
              <button class="back-link" @click="go('traces')"><ArrowLeft />返回链路列表</button>
              <div class="page-heading">
                <div>
                  <h1>
                    运行详情
                    <span class="status" :class="{ bad: selectedRun.status === '失败' }">{{
                      selectedRun.status
                    }}</span>
                  </h1>
                  <p class="muted small">今天 {{ selectedRun.time }} · {{ selectedRun.model }}</p>
                </div>
                <button
                  class="icon-button"
                  aria-label="复制运行 ID"
                  title="复制运行 ID"
                  @click="copy(selectedRun.id)"
                >
                  <CopyDocument />
                </button>
              </div>
            </template>

            <div v-if="page === 'models'" class="page-heading">
              <h1>模型</h1>
              <span class="readonly-badge"><Lock />只读</span>
            </div>
            <div v-if="page === 'users'" class="page-heading">
              <h1>用户管理</h1>
              <el-button type="primary" :icon="Plus" @click="openDialog('createUser')"
                >创建用户</el-button
              >
            </div>

            <el-skeleton
              v-if="scene === 'loading'"
              class="panel skeleton-panel"
              :rows="8"
              animated
              aria-label="正在加载"
            />
            <section v-else-if="scene === 'error'" class="panel state-panel">
              <Warning />
              <h2>加载失败</h2>
              <p>请检查连接后重试。</p>
              <el-button @click="refresh">重新加载</el-button>
            </section>
            <section v-else-if="scene === 'empty'" class="panel state-panel">
              <Collection />
              <h2>{{ page === 'knowledge' ? '还没有内容' : '暂无记录' }}</h2>
              <el-button
                v-if="page === 'knowledge' && !library"
                type="primary"
                @click="
                  () => {
                    openDialog('createLibrary')
                    scene = 'normal'
                  }
                "
                >新建知识库</el-button
              ><el-button v-else @click="scene = 'normal'">返回示例</el-button>
            </section>

            <template v-else>
              <section v-if="page === 'dashboard'" class="panel">
                <div class="panel-heading">
                  <h2>最近活动</h2>
                  <button class="text-action" @click="go('traces')">查看全部<ArrowRight /></button>
                </div>
                <button
                  v-for="(activity, index) in [
                    '完成了一次知识问答',
                    '更新了产品与使用指南',
                    '上传了文档上传与处理规范.md',
                  ]"
                  :key="activity"
                  class="activity"
                  @click="index === 0 ? openRun(runs[0]!) : go('knowledge')"
                >
                  <span class="activity-icon"
                    ><component :is="index === 0 ? ChatDotRound : Document" /></span
                  ><span
                    >{{ activity
                    }}<small>{{ ['今天 10:42', '今天 10:24', '今天 09:16'][index] }}</small></span
                  ><ArrowRight />
                </button>
              </section>

              <section v-if="page === 'knowledge'" class="panel resource-panel">
                <div class="table-toolbar">
                  <el-input
                    v-model="query"
                    :prefix-icon="Search"
                    clearable
                    :placeholder="
                      selectedDocument ? '搜索分块内容' : library ? '搜索文档' : '搜索知识库'
                    "
                    aria-label="搜索内容"
                  /><el-button :icon="Refresh" aria-label="刷新内容" @click="refresh" />
                </div>
                <div class="resource-table">
                  <div class="resource-table-header">
                    <span>{{ selectedDocument ? '内容' : '名称' }}</span
                    ><span>{{ selectedDocument ? '字符数' : library ? '分块数' : '文档数' }}</span
                    ><span>状态</span><span>更新时间</span><span>操作</span>
                  </div>
                  <div v-for="item in resourcePage" :key="item.id" class="resource-row">
                    <button class="resource-name" @click="openResource(item)">
                      <span class="file-icon"
                        ><component :is="library ? Document : Collection" /></span
                      ><span
                        ><strong>{{ selectedDocument ? `分块 ${item.id}` : item.name }}</strong
                        ><small v-if="selectedDocument">{{ item.name }}</small></span
                      ></button
                    ><span class="resource-count"
                      >{{ item.count
                      }}<small class="mobile-only">
                        {{ selectedDocument ? '字符' : library ? '分块' : '文档' }}</small
                      ></span
                    ><span
                      class="status"
                      :class="{
                        bad: item.status === '处理失败',
                        pending: item.status === '处理中',
                      }"
                      >{{ item.status }}</span
                    ><span class="resource-date muted small">{{ item.date }}</span>
                    <div class="row-actions">
                      <button
                        v-if="item.status === '处理失败'"
                        class="text-action"
                        @click="
                          () => {
                            item.status = '处理中'
                            ElMessage.success('已模拟重新处理')
                          }
                        "
                      >
                        重试</button
                      ><button
                        v-if="selectedDocument"
                        class="text-action"
                        @click="openResource(item)"
                      >
                        查看</button
                      ><el-dropdown
                        v-else
                        trigger="click"
                        @command="(command: DialogKind) => openDialog(command, item)"
                        ><button class="icon-button" :aria-label="`管理 ${item.name}`">
                          <MoreFilled /></button
                        ><template #dropdown
                          ><el-dropdown-menu
                            ><el-dropdown-item command="renameResource">重命名</el-dropdown-item
                            ><el-dropdown-item command="deleteResource"
                              >删除</el-dropdown-item
                            ></el-dropdown-menu
                          ></template
                        ></el-dropdown
                      >
                    </div>
                  </div>
                </div>
                <el-empty
                  v-if="!filteredResources.length"
                  :description="query ? '没有匹配的内容' : '暂无文档，上传文件开始使用'"
                  :image-size="64"
                />
                <div class="pagination">
                  <span>共 {{ filteredResources.length }} 条</span
                  ><el-pagination
                    v-model:current-page="currentPage"
                    :page-size="pageSize"
                    :total="filteredResources.length"
                    layout="prev, pager, next"
                  />
                </div>
              </section>

              <section v-if="page === 'traces'" class="panel trace-table">
                <div class="trace-row trace-table-header">
                  <span>开始时间</span><span>状态</span><span>模型 / 流程</span><span>首字耗时</span
                  ><span>总耗时</span><span></span>
                </div>
                <button
                  v-for="run in runPage"
                  :key="run.id"
                  class="trace-row trace-data-row"
                  @click="openRun(run)"
                >
                  <span class="run-time"
                    >今天 {{ run.time }}<small v-if="role === 'admin'">{{ run.user }}</small></span
                  ><span
                    class="status"
                    :class="{
                      bad: run.status === '失败',
                      neutral: run.status === '已取消',
                      pending: run.status === '生成中',
                    }"
                    >{{ run.status }}</span
                  ><span class="run-model"
                    >{{ run.model
                    }}<small
                      >{{ run.mode
                      }}<span v-if="run.degraded" class="warning-inline"> · 已降级</span></small
                    ></span
                  ><span class="run-first"
                    ><small class="mobile-only">首字 </small
                    >{{ run.first === null ? '—' : `${run.first.toFixed(2)} s` }}</span
                  ><span class="run-total"
                    ><small class="mobile-only">总耗时 </small
                    >{{ run.status === '生成中' ? '—' : `${run.total.toFixed(2)} s` }}</span
                  ><span class="trace-open"><ArrowRight /></span></button
                ><el-empty
                  v-if="!filteredRuns.length"
                  description="没有匹配的运行记录"
                  :image-size="64"
                />
                <div class="pagination">
                  <span>共 {{ filteredRuns.length }} 条</span
                  ><el-pagination
                    v-model:current-page="currentPage"
                    :total="filteredRuns.length"
                    :page-size="pageSize"
                    layout="prev, pager, next"
                  />
                </div>
              </section>

              <template v-if="page === 'trace'">
                <div
                  v-if="selectedRun.status === '失败' || selectedRun.degraded"
                  class="alert-strip"
                  role="status"
                >
                  <Warning />{{
                    selectedRun.status === '失败'
                      ? '模型请求超时，本次未生成回答。'
                      : '重排服务未响应，已使用原始检索顺序完成回答。'
                  }}
                </div>
                <div class="metrics">
                  <div>
                    <span>总耗时</span
                    ><strong>{{
                      selectedRun.status === '生成中' ? '—' : `${selectedRun.total.toFixed(2)} s`
                    }}</strong>
                  </div>
                  <div>
                    <span>首字耗时</span
                    ><strong>{{
                      selectedRun.first === null ? '—' : `${selectedRun.first.toFixed(2)} s`
                    }}</strong>
                  </div>
                  <div>
                    <span>模型首字耗时</span
                    ><strong>{{ selectedRun.first === null ? '—' : '0.31 s' }}</strong>
                  </div>
                  <div>
                    <span>候选 / 证据</span><strong>24 <em>/ 6</em></strong>
                  </div>
                </div>
                <section class="panel waterfall">
                  <div class="panel-heading">
                    <h2>执行阶段</h2>
                    <span class="muted small">{{ selectedRun.mode }}</span>
                  </div>
                  <div v-if="selectedRun.status === '生成中'" class="state-panel">
                    <p>正在生成回答，完成后显示阶段详情。</p>
                  </div>
                  <template v-else
                    ><div class="waterfall-axis">
                      <span></span>
                      <div>
                        <span>0 s</span><span>{{ (selectedRun.total / 2).toFixed(2) }} s</span
                        ><span>{{ selectedRun.total.toFixed(2) }} s</span>
                      </div>
                      <span></span>
                    </div>
                    <div v-for="(stage, index) in stages" :key="stage.name" class="waterfall-row">
                      <span>{{ stage.name }}</span>
                      <div class="waterfall-track">
                        <span
                          class="waterfall-bar"
                          :class="{
                            'generation-bar': index === 5,
                            'failed-bar': selectedRun.status === '失败' && index === 5,
                          }"
                          :style="{
                            marginLeft: `${(stage.start / 3.24) * 100}%`,
                            width: `${(stage.duration / 3.24) * 100}%`,
                          }"
                        ></span>
                      </div>
                      <span class="stage-duration"
                        >{{ ((stage.duration / 3.24) * selectedRun.total).toFixed(2) }} s</span
                      >
                    </div></template
                  >
                </section>
                <div class="detail-grid">
                  <section class="panel">
                    <div class="panel-heading"><h2>模型尝试</h2></div>
                    <div class="model-attempt">
                      <span class="file-icon"><Cpu /></span>
                      <div>
                        <strong>{{ selectedRun.model }}</strong
                        ><small>回答模型</small>
                      </div>
                      <span class="status" :class="{ bad: selectedRun.status === '失败' }">{{
                        selectedRun.status
                      }}</span>
                    </div>
                  </section>
                  <section class="panel">
                    <div class="panel-heading"><h2>候选流转</h2></div>
                    <div class="candidate-flow">
                      <span><strong>32</strong>检索</span><ArrowRight /><span
                        ><strong>24</strong>去重</span
                      ><ArrowRight /><span><strong>6</strong>引用证据</span>
                    </div>
                  </section>
                </div>
                <details class="panel technical-details">
                  <summary>技术信息</summary>
                  <dl>
                    <dt>运行 ID</dt>
                    <dd>{{ selectedRun.id }}</dd>
                    <dt>执行模式</dt>
                    <dd>{{ selectedRun.mode }}</dd>
                    <dt>候选模型</dt>
                    <dd>{{ selectedRun.model }}</dd>
                  </dl>
                </details>
              </template>

              <section v-if="page === 'models'" class="panel model-empty">
                <span class="model-large-icon"><Cpu /></span>
                <h2>模型配置由后端管理</h2>
                <p class="muted">模型状态查询尚未接入</p>
                <span class="status neutral">未接入</span>
              </section>

              <section v-if="page === 'users'" class="panel">
                <div class="table-toolbar">
                  <el-input
                    v-model="query"
                    :prefix-icon="Search"
                    clearable
                    placeholder="搜索姓名或用户名"
                    aria-label="搜索用户"
                  />
                </div>
                <div class="user-row user-table-header">
                  <span>用户</span><span>角色</span><span>状态</span><span>最近登录</span
                  ><span>操作</span>
                </div>
                <div v-for="user in userPage" :key="user.id" class="user-row">
                  <div class="user-identity">
                    <span class="avatar">{{ user.name.slice(0, 1) }}</span
                    ><span
                      ><strong>{{ user.name }}</strong
                      ><small>{{ user.username }}</small></span
                    >
                  </div>
                  <span class="user-role">{{ user.role }}</span
                  ><span class="status" :class="{ neutral: !user.enabled }">{{
                    user.enabled ? '已启用' : '已禁用'
                  }}</span
                  ><span class="user-date muted small">{{ user.date }}</span>
                  <div class="user-actions">
                    <el-dropdown
                      v-if="user.id !== 1"
                      trigger="click"
                      @command="(command: DialogKind) => openDialog(command, user)"
                      ><button class="icon-button" :aria-label="`管理用户 ${user.name}`">
                        <MoreFilled /></button
                      ><template #dropdown
                        ><el-dropdown-menu
                          ><el-dropdown-item command="resetPassword">重置密码</el-dropdown-item
                          ><el-dropdown-item command="toggleUser">{{
                            user.enabled ? '禁用用户' : '启用用户'
                          }}</el-dropdown-item></el-dropdown-menu
                        ></template
                      ></el-dropdown
                    ><span v-else class="muted small">当前账号</span>
                  </div>
                </div>
                <el-empty
                  v-if="!filteredUsers.length"
                  description="没有匹配的用户"
                  :image-size="64"
                />
                <div class="pagination">
                  <span>共 {{ filteredUsers.length }} 条</span
                  ><el-pagination
                    v-model:current-page="currentPage"
                    :page-size="pageSize"
                    :total="filteredUsers.length"
                    layout="prev, pager, next"
                  />
                </div>
              </section>
            </template>
          </main>
        </div>
      </div>

      <el-drawer v-model="historyOpen" title="会话" size="min(320px, 100vw)">
        <el-input
          v-model="conversationQuery"
          :prefix-icon="Search"
          placeholder="搜索会话"
          aria-label="搜索历史会话"
          clearable
        />
        <div
          v-for="conversation in filteredConversations"
          :key="conversation.id"
          class="history-row"
        >
          <button class="history-title" @click="openConversation(conversation.id)">
            {{ conversation.title }}
          </button>
          <el-dropdown
            trigger="click"
            @command="(command: DialogKind) => openDialog(command, conversation)"
          >
            <button class="icon-button" :aria-label="`管理历史会话：${conversation.title}`">
              <MoreFilled />
            </button>
            <template #dropdown
              ><el-dropdown-menu
                ><el-dropdown-item command="renameChat">重命名</el-dropdown-item
                ><el-dropdown-item command="deleteChat">删除</el-dropdown-item></el-dropdown-menu
              ></template
            >
          </el-dropdown>
        </div>
        <el-empty
          v-if="!filteredConversations.length"
          description="没有匹配的会话"
          :image-size="64"
        />
      </el-drawer>
      <el-drawer
        v-model="sourceOpen"
        :title="selectedChunk && page === 'knowledge' ? '分块详情' : '引用来源'"
        class="source-drawer"
        size="min(380px, 100vw)"
        :modal="!desktopSource || page !== 'chat'"
        :lock-scroll="!desktopSource"
        :modal-penetrable="desktopSource && page === 'chat'"
      >
        <article v-if="selectedChunk && page === 'knowledge'" class="source-card source-active">
          <h2>分块 {{ selectedChunk.id }}</h2>
          <p class="muted small">{{ selectedDocument?.name }} · {{ selectedChunk.count }} 字符</p>
          <p>{{ selectedChunk.name }}</p>
        </article>
        <template v-else>
          <div class="source-count">3 个来源</div>
          <article
            v-for="(source, index) in sources"
            :id="`source-${index}`"
            :key="source.title"
            class="source-card"
            :class="{ 'source-active': index === activeSource }"
          >
            <button class="source-title" @click="activeSource = index">
              <span class="citation">{{ index + 1 }}</span
              ><strong>{{ source.title }}</strong>
            </button>
            <p class="muted small">{{ source.section }}</p>
            <p>{{ source.text }}</p>
          </article>
        </template>
      </el-drawer>

      <el-dialog
        v-model="dialog"
        :title="titles[dialogKind]"
        width="440px"
        class="prototype-dialog"
        :close-on-click-modal="false"
      >
        <form id="prototype-form" class="dialog-form" @submit.prevent="submitDialog">
          <p v-if="destructive">
            删除后无法恢复。确定删除“{{ resourceTarget?.name ?? chatTarget?.title }}”？
          </p>
          <p v-if="dialogKind === 'toggleUser'">
            确定{{ userTarget?.enabled ? '禁用' : '启用' }}用户“{{ userTarget?.name }}”？{{
              userTarget?.enabled ? '禁用后该用户将无法登录。' : ''
            }}
          </p>
          <p v-if="dialogKind === 'resetPassword'" class="muted">
            为 {{ userTarget?.name }} 设置新密码
          </p>
          <label
            v-if="
              ['createLibrary', 'renameResource', 'renameChat', 'createUser'].includes(dialogKind)
            "
            >{{ dialogKind === 'createUser' ? '显示名称' : '名称'
            }}<el-input v-model="form.name" maxlength="120" autofocus
          /></label>
          <template v-if="dialogKind === 'createUser'"
            ><label>用户名<el-input v-model="form.username" autocomplete="off" /></label
            ><label
              >角色<el-select v-model="form.role"
                ><el-option label="普通用户" value="普通用户" /><el-option
                  label="管理员"
                  value="管理员" /></el-select></label
          ></template>
          <label v-if="dialogKind === 'password'"
            >当前密码<el-input
              v-model="form.oldPassword"
              type="password"
              show-password
              autocomplete="current-password"
          /></label>
          <label v-if="['createUser', 'resetPassword', 'password'].includes(dialogKind)"
            >{{ dialogKind === 'createUser' ? '初始密码' : '新密码'
            }}<el-input
              v-model="form.password"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="至少 8 位"
          /></label>
          <label v-if="dialogKind === 'password'"
            >确认新密码<el-input
              v-model="form.confirm"
              type="password"
              show-password
              autocomplete="new-password"
          /></label>
          <p v-if="formError" class="error-text" role="alert">{{ formError }}</p>
        </form>
        <template #footer
          ><el-button @click="dialog = false">取消</el-button
          ><el-button
            :type="destructive ? 'danger' : 'primary'"
            native-type="submit"
            form="prototype-form"
            >{{ destructive ? '删除' : '保存' }}</el-button
          ></template
        >
      </el-dialog>

      <div class="prototype-tools">
        <button
          class="prototype-toggle"
          :aria-expanded="controlsOpen"
          aria-controls="prototype-controls"
          @click="controlsOpen = !controlsOpen"
        >
          <Setting />原型<ArrowDown />
        </button>
        <section v-if="controlsOpen" id="prototype-controls" class="prototype-controls">
          <div class="tools-heading">
            <strong>原型设置</strong
            ><button class="icon-button" aria-label="关闭原型设置" @click="controlsOpen = false">
              <Close />
            </button>
          </div>
          <p>仅使用模拟数据，刷新后重置</p>
          <label
            >身份<el-select v-model="role" aria-label="原型身份"
              ><el-option label="管理员" value="admin" /><el-option
                label="普通用户"
                value="user" /></el-select></label
          ><label
            >页面状态<el-select v-model="scene" aria-label="原型页面状态"
              ><el-option label="正常" value="normal" /><el-option
                label="加载中"
                value="loading" /><el-option label="空状态" value="empty" /><el-option
                label="加载失败"
                value="error" /></el-select></label
          ><el-button
            @click="
              () => {
                go('login')
                controlsOpen = false
              }
            "
            >查看登录页</el-button
          ><el-button
            v-if="page === 'chat' && turns.length"
            @click="
              () => {
                stopGeneration()
                turns[turns.length - 1]!.state = 'error'
                controlsOpen = false
              }
            "
            >模拟生成失败</el-button
          >
        </section>
      </div>
    </div>
  </ElConfigProvider>
</template>
