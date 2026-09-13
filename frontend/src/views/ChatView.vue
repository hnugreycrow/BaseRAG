<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { ElMessageBox } from "element-plus";
import AppIcon from "../components/AppIcon.vue";
import SourcesDrawer from "../components/SourcesDrawer.vue";
import {
  connectWorkspace,
  workspace,
  readyDocuments,
} from "../stores/workspace";
import {
  activeConversation,
  chatState,
  isAsking,
  loadConversations,
  newConversation,
  regenerateExchange,
  removeConversation,
  renameConversation,
  retryExchange,
  selectConversation,
  showVersion,
  stopGeneration,
  sendQuestion,
  type Exchange,
} from "../stores/conversations";

const selectedSource = ref<{ exchange: Exchange; citationId?: string }>();
const route = useRoute();
const router = useRouter();
const messagesEnd = ref<HTMLDivElement>();
const textarea = ref<HTMLTextAreaElement>();
const sidebar = ref<HTMLElement>();
const menuButton = ref<HTMLButtonElement>();
const sidebarOpen = ref(false);
const historyQuery = ref("");
const exchanges = computed(() => activeConversation.value?.exchanges || []);
const pendingExchange = computed(() =>
  exchanges.value.find((exchange) => exchange.state === "pending"),
);
const histories = computed(() => chatState.conversations);
const suggestions = [
  {
    icon: "book",
    title: "了解申请条件",
    question: "文档中规定的申请条件是什么？",
  },
  {
    icon: "file",
    title: "查找具体流程",
    question: "申请需要经过哪些审批步骤？",
  },
  {
    icon: "clock",
    title: "确认时间要求",
    question: "文档中规定的办理时限是什么？",
  },
];
const copiedId = ref("");
const copyError = ref("");
let mobileQuery: MediaQueryList | undefined;
let routeReady = false;
function closeOnDesktop(event: MediaQueryListEvent) {
  if (!event.matches) sidebarOpen.value = false;
}
onMounted(async () => {
  mobileQuery = window.matchMedia("(max-width: 760px)");
  mobileQuery.addEventListener("change", closeOnDesktop);
  await loadConversations();
  routeReady = true;
  await loadRouteConversation();
});
async function loadRouteConversation() {
  const id =
    typeof route.params.conversationId === "string"
      ? route.params.conversationId
      : "";
  if (id && id !== chatState.activeId)
    await selectConversation(id).catch(() => void router.replace("/chat"));
  else if (!id) newConversation();
}
watch(
  () => route.params.conversationId,
  () => {
    if (routeReady) void loadRouteConversation();
  },
);
onUnmounted(() => mobileQuery?.removeEventListener("change", closeOnDesktop));
watch(sidebarOpen, async (open) => {
  await nextTick();
  if (open) sidebar.value?.querySelector<HTMLButtonElement>("button")?.focus();
  else menuButton.value?.focus();
});
function beginConversation() {
  newConversation();
  void router.push("/chat");
  sidebarOpen.value = false;
  void nextTick(() => textarea.value?.focus());
}
async function openConversation(id: string) {
  await router.push(`/chat/${id}`);
  sidebarOpen.value = false;
}

let searchTimer: number | undefined;
watch(historyQuery, (value) => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(
    () => void loadConversations(value.trim()),
    250,
  );
});

async function editConversation(id: string, title: string) {
  const value = await ElMessageBox.prompt("输入新的会话标题", "重命名会话", {
    inputValue: title,
    inputValidator: (text) =>
      (!!text.trim() && text.trim().length <= 200) || "请输入 1 到 200 个字符",
  }).catch(() => undefined);
  if (value?.value) await renameConversation(id, value.value);
}

async function confirmDeleteConversation(id: string, title: string) {
  const confirmed = await ElMessageBox.confirm(
    `确定删除“${title}”及其全部消息吗？`,
    "删除会话",
    {
      type: "warning",
      confirmButtonText: "删除",
      cancelButtonText: "取消",
    },
  ).then(
    () => true,
    () => false,
  );
  if (!confirmed) return;
  await removeConversation(id);
  if (!chatState.activeId) await router.push("/chat");
}
function trapSidebar(event: KeyboardEvent) {
  if (!sidebarOpen.value) return;
  if (event.key === "Escape") {
    sidebarOpen.value = false;
    return;
  }
  if (event.key !== "Tab") return;
  const items = Array.from(
    sidebar.value?.querySelectorAll<HTMLElement>(
      "a[href], button:not([disabled]), input",
    ) || [],
  ).filter((element) => element.getClientRects().length > 0);
  const first = items[0];
  const last = items[items.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last?.focus();
  }
  if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first?.focus();
  }
}
function resizeComposer() {
  if (!textarea.value) return;
  textarea.value.style.height = "auto";
  textarea.value.style.height =
    Math.min(textarea.value.scrollHeight, 180) + "px";
}
watch(
  () => chatState.draft,
  () => {
    void nextTick(resizeComposer);
  },
);
watch(
  () => [
    chatState.activeId,
    exchanges.value.length,
    exchanges.value.map((item) => item.state).join(","),
  ],
  async () => {
    await nextTick();
    messagesEnd.value?.scrollIntoView({ block: "end" });
  },
);
function useSuggestion(question: string) {
  chatState.draft = question;
  void nextTick(() => textarea.value?.focus());
}
function keydown(event: KeyboardEvent) {
  if (
    event.key === "Enter" &&
    !event.shiftKey &&
    !event.isComposing &&
    event.keyCode !== 229
  ) {
    event.preventDefault();
    void submit();
  }
}
async function submit() {
  await sendQuestion();
  if (chatState.activeId && route.params.conversationId !== chatState.activeId)
    await router.replace(`/chat/${chatState.activeId}`);
}
async function submitOrStop() {
  if (pendingExchange.value) {
    await stopGeneration(pendingExchange.value);
    return;
  }
  await submit();
}
async function copyAnswer(exchange: Exchange) {
  if (!exchange.answer) return;
  try {
    await navigator.clipboard.writeText(exchange.answer.answer);
    copiedId.value = exchange.id;
    copyError.value = "";
  } catch {
    copyError.value = "无法访问剪贴板，请选中回答文字后手动复制。";
  }
}
</script>
<template>
  <div class="chat-shell">
    <button
      v-if="sidebarOpen"
      class="chat-scrim"
      aria-label="关闭会话列表"
      tabindex="-1"
      @click="sidebarOpen = false"
    ></button>
    <aside
      ref="sidebar"
      class="chat-sidebar"
      :class="{ 'is-open': sidebarOpen }"
      aria-label="会话导航"
      @keydown="trapSidebar"
    >
      <div class="chat-brand-row">
        <RouterLink to="/chat" class="chat-brand" @click="beginConversation">
          <span class="chat-brand-mark">J</span><strong>JAgent</strong>
        </RouterLink>
        <button
          class="icon-button sidebar-close"
          aria-label="关闭会话列表"
          @click="sidebarOpen = false"
        >
          <AppIcon name="close" :size="18" />
        </button>
      </div>
      <button class="new-chat-button" @click="beginConversation">
        <AppIcon name="edit" :size="17" /><span>新建对话</span>
      </button>
      <div class="history-label">最近对话</div>
      <label class="history-search">
        <span class="sr-only">搜索对话</span>
        <AppIcon name="search" :size="15" />
        <input v-model="historyQuery" placeholder="搜索对话" />
      </label>
      <nav class="conversation-history" aria-label="最近对话">
        <div
          v-for="conversation in histories"
          :key="conversation.id"
          class="history-row"
        >
          <button
            class="history-item"
            :class="{ active: chatState.activeId === conversation.id }"
            :title="conversation.title"
            @click="openConversation(conversation.id)"
          >
            <AppIcon name="chat" :size="15" /><span>{{
              conversation.title
            }}</span>
          </button>
          <button
            class="history-action"
            aria-label="重命名会话"
            title="重命名"
            @click="editConversation(conversation.id, conversation.title)"
          >
            <AppIcon name="edit" :size="14" />
          </button>
          <button
            class="history-action"
            aria-label="删除会话"
            title="删除"
            @click="
              confirmDeleteConversation(conversation.id, conversation.title)
            "
          >
            <AppIcon name="close" :size="14" />
          </button>
        </div>
        <p v-if="!histories.length" class="history-empty">
          {{ historyQuery ? "没有匹配的对话" : "你的对话会显示在这里" }}
        </p>
      </nav>
      <div class="chat-sidebar-footer">
        <RouterLink to="/admin" class="admin-entry">
          <span class="admin-entry-icon"
            ><AppIcon name="grid" :size="17"
          /></span>
          <span><strong>管理后台</strong><small>知识库与文档</small></span>
          <AppIcon name="chevron" :size="15" />
        </RouterLink>
      </div>
    </aside>

    <main class="chat-main" :inert="sidebarOpen">
      <header class="chat-topbar">
        <button
          ref="menuButton"
          class="icon-button menu-button"
          aria-label="打开会话列表"
          :aria-expanded="sidebarOpen"
          @click="sidebarOpen = true"
        >
          <AppIcon name="panel" :size="20" />
        </button>
        <button class="assistant-picker" aria-label="当前助手">
          JAgent <span>知识问答</span><AppIcon name="down" :size="14" />
        </button>
      </header>
      <el-alert
        v-if="workspace.error"
        class="connection-alert"
        type="error"
        :title="workspace.error"
        :closable="false"
        show-icon
      >
        <template #default
          ><el-button link type="danger" @click="connectWorkspace"
            >重新连接</el-button
          ></template
        >
      </el-alert>
      <section
        class="chat-page"
        :class="{ 'has-messages': exchanges.length > 0 }"
        aria-label="知识库问答"
      >
        <div v-if="!exchanges.length" class="chat-welcome">
          <div class="welcome-kicker"><span></span>知识检索助手</div>
          <div class="welcome-symbol"><AppIcon name="chat" :size="28" /></div>
          <h1>从资料里，找到确定答案</h1>
          <p>输入问题，我会检索全部知识库，并标注引用来源。</p>
        </div>

        <div v-else class="message-scroll">
          <div class="message-thread">
            <article
              v-for="(exchange, exchangeIndex) in exchanges"
              :key="exchange.id"
              class="exchange"
              :aria-busy="exchange.state === 'pending'"
            >
              <div class="user-message">
                <span class="sr-only">你：</span>
                <p>{{ exchange.question }}</p>
              </div>
              <div class="assistant-message">
                <div class="assistant-avatar" aria-label="JAgent">J</div>
                <div class="assistant-content">
                  <div
                    v-if="exchange.state === 'pending'"
                    class="streaming-answer"
                    role="status"
                  >
                    <p v-if="exchange.answer?.answer" class="answer-text">
                      {{ exchange.answer.answer }}
                    </p>
                    <div class="thinking">
                      <span class="thinking-dot"></span
                      >{{
                        exchange.answer?.answer ? "正在生成…" : "正在查阅文档…"
                      }}
                    </div>
                  </div>
                  <div
                    v-else-if="
                      exchange.state === 'failed' ||
                      exchange.state === 'cancelled'
                    "
                    class="message-error"
                    role="alert"
                  >
                    <p
                      v-if="exchange.answer?.answer"
                      class="answer-text partial-answer"
                    >
                      {{ exchange.answer.answer }}
                    </p>
                    <p>{{ exchange.error }}</p>
                    <el-button
                      plain
                      size="small"
                      :disabled="isAsking"
                      @click="retryExchange(exchange)"
                    >
                      <AppIcon name="refresh" :size="15" />重试
                    </el-button>
                  </div>
                  <template v-else-if="exchange.answer">
                    <p class="answer-text">{{ exchange.answer.answer }}</p>
                    <div
                      v-if="exchange.answer.citations.length"
                      class="inline-citations"
                    >
                      <span>引用</span
                      ><button
                        v-for="id in exchange.answer.citations"
                        :key="id"
                        @click="selectedSource = { exchange, citationId: id }"
                      >
                        [{{ id }}]
                      </button>
                    </div>
                    <div class="answer-actions">
                      <el-button
                        class="sources-button"
                        plain
                        size="small"
                        @click="selectedSource = { exchange }"
                      >
                        <AppIcon name="book" :size="15" />{{
                          exchange.answer.sources.length
                            ? exchange.answer.sources.length + " 个检索来源"
                            : "查看检索来源"
                        }}<AppIcon name="chevron" :size="13" />
                      </el-button>
                      <el-button
                        circle
                        text
                        :aria-label="
                          copiedId === exchange.id ? '已复制回答' : '复制回答'
                        "
                        :title="
                          copiedId === exchange.id ? '已复制' : '复制回答'
                        "
                        @click="copyAnswer(exchange)"
                      >
                        <AppIcon
                          :name="copiedId === exchange.id ? 'check' : 'copy'"
                          :size="16"
                        />
                      </el-button>
                      <el-button
                        v-if="
                          exchangeIndex === exchanges.length - 1 &&
                          exchange.answer.active
                        "
                        text
                        size="small"
                        :disabled="isAsking"
                        @click="regenerateExchange(exchange)"
                        ><AppIcon
                          name="refresh"
                          :size="15"
                        />重新生成</el-button
                      >
                      <span
                        v-if="exchange.versions.length > 1"
                        class="version-switcher"
                      >
                        <button
                          :disabled="exchange.activeVersion === 0"
                          aria-label="上一版本"
                          @click="
                            showVersion(exchange, exchange.activeVersion - 1)
                          "
                        >
                          ‹
                        </button>
                        {{ exchange.activeVersion + 1 }}/{{
                          exchange.versions.length
                        }}
                        <button
                          :disabled="
                            exchange.activeVersion ===
                            exchange.versions.length - 1
                          "
                          aria-label="下一版本"
                          @click="
                            showVersion(exchange, exchange.activeVersion + 1)
                          "
                        >
                          ›
                        </button>
                      </span>
                      <span
                        v-if="exchange.answer.modelInfo"
                        class="answer-model"
                      >
                        {{ exchange.answer.modelInfo.model }} ·
                        {{ exchange.answer.modelInfo.provider }}
                      </span>
                    </div>
                  </template>
                </div>
              </div>
            </article>
            <p v-if="copyError" class="small muted" role="status">
              {{ copyError }}
            </p>
            <div ref="messagesEnd"></div>
          </div>
        </div>

        <div class="composer-area">
          <form class="chat-composer" @submit.prevent="submit">
            <label class="sr-only" for="question">输入问题</label>
            <textarea
              id="question"
              ref="textarea"
              v-model="chatState.draft"
              rows="1"
              maxlength="2000"
              placeholder="向 JAgent 提问"
              :disabled="isAsking"
              @keydown="keydown"
            ></textarea>
            <div class="composer-tools">
              <span v-if="chatState.draft.length > 1500" class="character-count"
                >{{ chatState.draft.length }}/2000</span
              >
              <el-button
                class="send-button"
                type="primary"
                circle
                native-type="button"
                :disabled="!isAsking && !chatState.draft.trim()"
                :aria-label="isAsking ? '停止生成' : '发送问题'"
                :title="isAsking ? '停止生成' : '发送问题'"
                @click="submitOrStop"
              >
                <span v-if="isAsking" class="stop-square"></span
                ><AppIcon v-else name="arrow" :size="20" />
              </el-button>
            </div>
          </form>
          <p
            v-if="
              !exchanges.length &&
              !workspace.loading &&
              !readyDocuments.length &&
              !workspace.error
            "
            class="empty-library-hint"
          >
            还没有可用文档。<RouterLink to="/admin/documents"
              >前往后台导入</RouterLink
            >
          </p>
          <div v-if="!exchanges.length" class="suggestion-grid">
            <el-button
              v-for="item in suggestions"
              :key="item.title"
              plain
              @click="useSuggestion(item.question)"
            >
              <AppIcon :name="item.icon" :size="18" /><span>{{
                item.title
              }}</span>
            </el-button>
          </div>
          <p class="composer-note">
            回答与来源会保存到当前会话；历史仅用于理解追问，请核对本轮检索来源。
          </p>
        </div>
        <SourcesDrawer
          v-if="selectedSource"
          :exchange="selectedSource.exchange"
          :citation-id="selectedSource.citationId"
          @close="selectedSource = undefined"
        />
      </section>
    </main>
  </div>
</template>

<style scoped>
.chat-shell {
  display: flex;
  width: 100%;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  color: #2f2f2f;
  background: #fff;
}
.chat-sidebar {
  width: 260px;
  flex: 0 0 260px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 10px;
  background: #f9f9f9;
  border-right: 1px solid #ececec;
}
.chat-brand-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 44px;
  padding: 0 5px 0 8px;
}
.chat-brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: #1f1f1f;
}
.chat-brand strong {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.2px;
}
.chat-brand-mark,
.assistant-avatar {
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 700;
  background: #10a37f;
}
.chat-brand-mark {
  width: 27px;
  height: 27px;
  font-size: 13px;
  border-radius: 8px;
}
.sidebar-close {
  display: none;
}
.new-chat-button {
  display: flex;
  align-items: center;
  gap: 11px;
  width: 100%;
  min-height: 42px;
  margin-top: 7px;
  padding: 9px 11px;
  color: #303030;
  font-size: 14px;
  font-weight: 550;
  text-align: left;
  border: 1px solid #dedede;
  border-radius: 10px;
  transition:
    background 0.16s,
    border-color 0.16s;
}
.new-chat-button:hover {
  background: #efefef;
  border-color: #d5d5d5;
}
.history-label {
  padding: 24px 10px 8px;
  color: #8a8a8a;
  font-size: 13px;
  font-weight: 600;
}
.history-search {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 4px 7px;
  padding: 7px 9px;
  color: #888;
  background: #fff;
  border: 1px solid #e7e7e7;
  border-radius: 8px;
}
.history-search:focus-within {
  border-color: #a8a8a8;
  box-shadow: 0 0 0 2px #0000000a;
}
.history-search input {
  min-width: 0;
  width: 100%;
  padding: 0;
  color: #333;
  font-size: 13px;
  background: transparent;
  border: 0;
  outline: 0;
}
.history-search input::placeholder {
  color: #aaa;
}
.conversation-history {
  flex: 1;
  min-height: 60px;
  overflow-y: auto;
}
.history-row {
  display: flex;
  align-items: center;
  border-radius: 8px;
}
.history-row:hover {
  background: #ececec;
}
.history-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
  min-height: 38px;
  padding: 8px 10px;
  color: #555;
  font-size: 14px;
  text-align: left;
  border-radius: 8px;
}
.history-item span {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.history-item:hover,
.history-item.active {
  color: #222;
  background: #ececec;
}
.history-action {
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  display: grid;
  place-items: center;
  color: #888;
  border-radius: 6px;
  opacity: 0;
}
.history-row:hover .history-action,
.history-row:focus-within .history-action {
  opacity: 1;
}
.history-action:hover {
  color: #222;
  background: #ddd;
}
.history-empty {
  padding: 12px 10px;
  color: #999;
  font-size: 13px;
}
.chat-sidebar-footer {
  padding-top: 9px;
  border-top: 1px solid #e6e6e6;
}
.admin-entry {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 8px;
  border-radius: 9px;
}
.admin-entry:hover {
  background: #ececec;
}
.admin-entry-icon {
  width: 31px;
  height: 31px;
  display: grid;
  place-items: center;
  color: #555;
  background: #fff;
  border: 1px solid #dedede;
  border-radius: 8px;
}
.admin-entry > span:nth-child(2) {
  min-width: 0;
  flex: 1;
  line-height: 1.25;
}
.admin-entry strong {
  display: block;
  color: #333;
  font-size: 14px;
  font-weight: 600;
}
.admin-entry small {
  display: block;
  margin-top: 3px;
  color: #999;
  font-size: 12px;
}
.admin-entry > svg {
  color: #999;
}
.chat-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  background: #fff;
}
.chat-topbar {
  z-index: 5;
  height: 56px;
  min-height: 56px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 18px;
  background: #ffffffed;
  border-bottom: 1px solid transparent;
  backdrop-filter: blur(12px);
}
.menu-button {
  display: none;
}
.assistant-picker {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 9px;
  color: #2f2f2f;
  font-size: 16px;
  font-weight: 650;
  border-radius: 9px;
}
.assistant-picker:hover {
  background: #f4f4f4;
}
.assistant-picker span {
  color: #8c8c8c;
  font-size: 12px;
  font-weight: 450;
}
.assistant-picker svg {
  color: #888;
}
.topbar-status {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-left: auto;
  color: #808080;
  font-size: 12px;
}
.topbar-status i {
  width: 6px;
  height: 6px;
  background: #10a37f;
  border-radius: 50%;
  box-shadow: 0 0 0 3px #10a37f17;
}
.topbar-status.error i {
  background: #d25252;
  box-shadow: 0 0 0 3px #d2525217;
}
.connection-alert {
  flex-shrink: 0;
  margin: 0 18px 10px;
}
.chat-page {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  background: #fff;
}
.chat-page:not(.has-messages) {
  justify-content: center;
  overflow-y: auto;
  padding: 5vh 0 clamp(18px, 8vh, 72px);
}
.chat-welcome {
  flex-shrink: 0;
  margin: 0 24px 30px;
  text-align: center;
}
.welcome-kicker {
  display: none;
}
.welcome-symbol {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  margin: 0 auto 19px;
  color: #fff;
  background: #10a37f;
  border-radius: 14px;
  box-shadow: 0 10px 24px #10a37f20;
}
.chat-welcome h1 {
  color: #242424;
  font-size: clamp(25px, 3vw, 31px);
  font-weight: 650;
  letter-spacing: -1px;
}
.chat-welcome > p {
  margin-top: 9px;
  color: #858585;
  font-size: 15px;
}
.composer-area {
  z-index: 1;
  width: min(100% - 48px, 768px);
  margin: 0 auto;
  flex-shrink: 0;
}
.chat-composer {
  padding: 13px 13px 9px 17px;
  background: #fff;
  border: 1px solid #d9d9d9;
  border-radius: 24px;
  box-shadow: 0 6px 22px #0000000d;
  transition:
    border-color 0.18s,
    box-shadow 0.18s;
}
.chat-composer:focus-within {
  border-color: #b9b9b9;
  box-shadow:
    0 8px 26px #00000012,
    0 0 0 2px #00000008;
}
.chat-composer textarea {
  display: block;
  width: 100%;
  height: 36px;
  min-height: 36px;
  max-height: 180px;
  padding: 2px 2px;
  color: #292929;
  font-size: 16px;
  line-height: 1.7;
  background: none;
  border: 0;
  outline: none;
  resize: none;
}
.chat-composer textarea::placeholder {
  color: #999;
}
.composer-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 7px;
}
.character-count {
  margin-left: auto;
  color: #aaa;
  font-size: 11px;
}
.send-button {
  width: 34px !important;
  height: 34px !important;
  margin-left: auto;
  color: #fff !important;
  background: #171717 !important;
  border-color: #171717 !important;
}
.send-button:disabled {
  background: #d7d7d7 !important;
  border-color: #d7d7d7 !important;
}
.stop-square {
  width: 10px;
  height: 10px;
  background: currentColor;
  border-radius: 2px;
}
.character-count + .send-button {
  margin-left: 0;
}
.suggestion-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 9px;
  margin-top: 18px;
}
.suggestion-grid :deep(.el-button) {
  justify-content: flex-start;
  min-height: 44px;
  margin: 0;
  color: #666;
  font-size: 13px;
  background: #fff;
  border-color: #e5e5e5;
  border-radius: 12px;
}
.suggestion-grid :deep(.el-button:hover) {
  color: #222;
  border-color: #bbb;
  background: #fafafa;
}
.empty-library-hint {
  margin-top: 13px;
  color: #888;
  font-size: 13px;
  text-align: center;
}
.empty-library-hint a {
  margin-left: 4px;
  color: #0a765c;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.composer-note {
  margin: 12px 0 8px;
  color: #a0a0a0;
  font-size: 11px;
  text-align: center;
}
.message-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.message-thread {
  max-width: 768px;
  margin: auto;
  padding: 32px 24px 38px;
}
.exchange {
  margin-bottom: 38px;
}
.user-message {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 28px;
}
.user-message p {
  max-width: 80%;
  padding: 10px 16px;
  color: #303030;
  font-size: 15px;
  line-height: 1.75;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f4f4f4;
  border-radius: 20px 20px 4px 20px;
}
.assistant-message {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}
.assistant-avatar {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  margin-top: 1px;
  font-size: 11px;
  border-radius: 9px;
}
.assistant-content {
  flex: 1;
  min-width: 0;
}
.answer-text {
  padding-top: 1px;
  color: #303030;
  font-size: 15px;
  line-height: 1.9;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.thinking {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 4px 0;
  color: #777;
  font-size: 14px;
}
.streaming-answer .thinking {
  margin: 8px 0;
}
.partial-answer {
  margin-bottom: 10px;
  color: #555;
}
.thinking-dot {
  width: 7px;
  height: 7px;
  background: #10a37f;
  border-radius: 50%;
  box-shadow: 0 0 0 4px #10a37f14;
  animation: blink 1.2s alternate infinite;
}
.inline-citations {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 13px;
  color: #999;
  font-size: 11px;
}
.inline-citations button {
  padding: 2px 6px;
  color: #0a765c;
  font-size: 12px;
  background: #e8f5f1;
  border-radius: 5px;
}
.answer-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 13px;
}
.version-switcher {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: #888;
  font-size: 12px;
}
.version-switcher button {
  width: 22px;
  height: 22px;
  border-radius: 5px;
}
.version-switcher button:hover:not(:disabled) {
  background: #eee;
}
.sources-button {
  color: #626262 !important;
  font-size: 12px !important;
  border-color: #dedede !important;
  border-radius: 8px !important;
}
.answer-model {
  margin-left: auto;
  color: #aaa;
  font-size: 11px;
}
.message-error {
  padding: 12px 14px;
  color: #a84747;
  font-size: 14px;
  background: #fff1f1;
  border: 1px solid #f5dcdc;
  border-radius: 8px;
}
.chat-scrim {
  display: none;
}

@media (max-width: 760px) {
  .chat-sidebar {
    position: fixed;
    z-index: 30;
    inset: 0 auto 0 0;
    width: min(290px, 86vw);
    visibility: hidden;
    transform: translateX(-100%);
    transition: transform 0.2s;
    box-shadow: 12px 0 32px #00000016;
  }
  .chat-sidebar.is-open {
    visibility: visible;
    transform: translateX(0);
  }
  .chat-scrim {
    position: fixed;
    z-index: 25;
    inset: 0;
    display: block;
    width: 100%;
    height: 100%;
    background: #00000066;
  }
  .sidebar-close,
  .menu-button {
    display: inline-flex;
  }
  .chat-topbar {
    padding: 0 10px;
    border-bottom-color: #ededed;
  }
  .topbar-status {
    display: none;
  }
  .assistant-picker {
    font-size: 14px;
  }
  .assistant-picker span {
    display: none;
  }
  .chat-page:not(.has-messages) {
    justify-content: flex-start;
    padding-top: 9vh;
  }
  .chat-welcome {
    margin-bottom: 24px;
  }
  .chat-welcome h1 {
    font-size: 25px;
  }
  .composer-area {
    width: calc(100% - 24px);
  }
  .chat-composer {
    padding: 13px 10px 9px 15px;
  }
  .chat-composer textarea {
    font-size: 16px;
  }
  .suggestion-grid {
    grid-template-columns: 1fr;
    gap: 7px;
  }
  .suggestion-grid :deep(.el-button) {
    min-height: 40px;
  }
  .message-thread {
    padding: 24px 15px;
  }
  .answer-text,
  .user-message p {
    font-size: 15px;
  }
}
@media (max-height: 650px) {
  .chat-page:not(.has-messages) {
    justify-content: flex-start;
    padding-top: 24px;
    padding-bottom: 15px;
  }
}
</style>
