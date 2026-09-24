<script setup lang="ts">
import { ChatDotRound, Delete, EditPen, HomeFilled, Plus, Search } from '@element-plus/icons-vue'
import type { ConversationSummary } from '../../api'
import { useConversationGenerationStore } from '../../store'
import { useAuthStore } from '../../store/auth'
defineProps<{
  groups: { label: string; items: ConversationSummary[] }[]
  currentId: string
  loading: boolean
}>()
const query = defineModel<string>('query', { required: true })
const emit = defineEmits<{
  new: []
  manage: []
  open: [id: string]
  rename: [item: ConversationSummary]
  remove: [item: ConversationSummary]
}>()
const generationStore = useConversationGenerationStore()
const auth = useAuthStore()
function taskStatusLabel(conversationId: string) {
  const phase = generationStore.taskFor(conversationId)?.phase
  if (phase === 'starting' || phase === 'streaming') return '生成中'
  if (phase === 'stopping') return '停止中'
  if (phase === 'completed') return '已完成'
  if (phase === 'failed') return '生成失败'
  if (phase === 'cancelled') return '已停止'
  return ''
}
</script>
<template>
  <button class="new-chat-button" type="button" @click="emit('new')">
    <el-icon><Plus /></el-icon>
    新对话
  </button>

  <RouterLink
    v-if="auth.user?.role === 'ADMIN'"
    class="management-link"
    to="/admin"
    @click="emit('manage')"
  >
    <el-icon><HomeFilled /></el-icon>
    管理后台
  </RouterLink>

  <label class="search-box">
    <el-icon><Search /></el-icon>
    <input v-model="query" type="search" placeholder="搜索会话" aria-label="搜索会话" />
  </label>

  <div class="conversation-list" :class="{ 'is-loading': loading }">
    <template v-if="groups.length">
      <section v-for="group in groups" :key="group.label" class="conversation-group">
        <h2>{{ group.label }}</h2>
        <div
          v-for="item in group.items"
          :key="item.id"
          class="conversation-item"
          :class="{
            'is-active': item.id === currentId,
            'has-active-task': generationStore.isActive(item.id),
            'has-task-status': generationStore.taskFor(item.id),
          }"
        >
          <button type="button" class="conversation-link" @click="emit('open', item.id)">
            <el-icon><ChatDotRound /></el-icon>
            <span class="conversation-name" :title="item.title">{{ item.title }}</span>
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
              @click="emit('rename', item)"
            >
              <el-icon><EditPen /></el-icon>
            </button>
            <button type="button" title="删除" aria-label="删除会话" @click="emit('remove', item)">
              <el-icon><Delete /></el-icon>
            </button>
          </div>
        </div>
      </section>
    </template>
    <p v-else-if="!loading" class="conversation-empty">
      {{ query ? '没有匹配的会话' : '还没有历史会话' }}
    </p>
  </div>
</template>
<style scoped>
.new-chat-button {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 38px;
  color: var(--color-primary);
  background: var(--color-primary-soft);
  border-radius: 8px;
  font-size: 13px;
}
.management-link {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  min-height: 38px;
  margin-top: 8px;
  border-radius: 8px;
  color: #637086;
  font-size: 13px;
}
.management-link:hover,
.management-link:focus-visible {
  color: var(--color-primary);
  background: #f0f3f8;
}
.search-box {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0 4px;
  padding: 8px;
  color: var(--color-muted);
}
.search-box input {
  width: 100%;
  min-width: 0;
  border: 0;
  background: transparent;
  outline: none;
  font-size: 13px;
  color: var(--color-text);
}
.search-box:focus-within {
  outline: 2px solid var(--color-primary);
  border-radius: 6px;
}
.conversation-list {
  min-height: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
  flex: 1;
}
.conversation-group h2 {
  font-size: 12px;
  color: var(--color-muted);
  margin: 16px 8px 8px;
  font-weight: 500;
}
.conversation-item {
  display: flex;
  align-items: center;
  border-radius: 8px;
  position: relative;
}
.conversation-item:hover,
.conversation-item.is-active {
  background: #eef1f6;
}
.conversation-link {
  width: 100%;
  min-width: 0;
  flex: 1;
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 10px 8px;
  text-align: left;
  font-size: 13px;
  color: #637086;
}
.conversation-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}
.conversation-status {
  font-size: 11px;
  color: var(--color-primary);
}
.conversation-actions {
  position: absolute;
  top: 50%;
  right: 4px;
  z-index: 1;
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
  gap: 0;
  opacity: 0;
  visibility: hidden;
  pointer-events: none;
  transform: translateY(-50%);
  transition: opacity 120ms ease;
}
.conversation-item:hover .conversation-actions,
.conversation-item:focus-within .conversation-actions {
  opacity: 1;
  visibility: visible;
  pointer-events: auto;
}
.conversation-item:not(.has-active-task):hover .conversation-link,
.conversation-item:not(.has-active-task):focus-within .conversation-link {
  padding-right: 68px;
}
@media (hover: none) {
  .conversation-actions {
    opacity: 1;
    visibility: visible;
    pointer-events: auto;
  }
  .conversation-item:not(.has-active-task) .conversation-link {
    padding-right: 68px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .conversation-actions {
    transition: none;
  }
}
.conversation-actions button {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  color: var(--color-muted);
}
.conversation-actions button:hover {
  color: var(--color-primary);
  background: white;
}
.conversation-empty {
  font-size: 13px;
  color: var(--color-muted);
  margin: 20px 8px;
}
</style>
