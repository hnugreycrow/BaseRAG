<script setup lang="ts">
import { ArrowRight, ChatDotRound, Collection, Share } from '@element-plus/icons-vue'
import { onMounted, ref } from 'vue'
import { listConversations, getErrorMessage, type ConversationSummary } from '../api'
import { useAuthStore } from '../store'
const auth = useAuthStore()
const recent = ref<ConversationSummary[]>([])
const loading = ref(true)
const error = ref('')
async function load() {
  loading.value = true
  error.value = ''
  try {
    recent.value = (await listConversations()).slice(0, 5)
  } catch (cause) {
    error.value = getErrorMessage(cause)
  } finally {
    loading.value = false
  }
}
onMounted(load)
const entries = [
  { label: '开始提问', path: '/chat', icon: ChatDotRound },
  { label: '管理知识库', path: '/admin/knowledge-bases', icon: Collection },
  { label: '查看链路', path: '/admin/observability', icon: Share },
]
</script>
<template>
  <div class="dashboard-page">
    <header>
      <div>
        <h1>工作台</h1>
        <p>{{ auth.user?.displayName || auth.user?.username }}，欢迎回来</p>
      </div>
      <el-button type="primary" @click="$router.push('/chat')">进入问答</el-button>
    </header>
    <nav class="quick-links">
      <RouterLink v-for="item in entries" :key="item.path" :to="item.path"
        ><el-icon><component :is="item.icon" /></el-icon><strong>{{ item.label }}</strong
        ><el-icon><ArrowRight /></el-icon
      ></RouterLink>
    </nav>
    <section class="recent-panel">
      <h2>最近会话</h2>
      <div v-if="error" class="recent-state" role="alert">
        <p>{{ error }}</p>
        <el-button @click="load">重新加载</el-button>
      </div>
      <el-empty
        v-else-if="!recent.length"
        :description="loading ? '正在加载会话' : '还没有会话'"
        :image-size="64"
      />
      <RouterLink
        v-for="item in recent"
        v-else
        :key="item.id"
        :to="'/chat/' + item.id"
        class="recent-row"
        ><el-icon><ChatDotRound /></el-icon><span>{{ item.title }}</span
        ><time>{{ new Date(item.updatedAt).toLocaleDateString('zh-CN') }}</time
        ><el-icon><ArrowRight /></el-icon
      ></RouterLink>
    </section>
  </div>
</template>
<style scoped>
.dashboard-page {
  max-width: 1480px;
  margin: 0 auto;
  padding: 32px;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 26px;
}
h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 650;
}
header p {
  font-size: 14px;
  color: var(--color-muted);
  margin: 8px 0 0;
}
.quick-links {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
  margin-bottom: 28px;
}
.quick-links a {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 24px;
  border: 1px solid var(--color-line);
  border-radius: 12px;
  background: white;
}
.quick-links strong {
  flex: 1;
  font-size: 14px;
  font-weight: 550;
}
.quick-links .el-icon {
  color: var(--color-primary);
  font-size: 20px;
}
.recent-panel {
  background: white;
  border: 1px solid var(--color-line);
  border-radius: 12px;
  overflow: hidden;
}
h2 {
  font-size: 16px;
  margin: 0;
  padding: 24px;
}
.recent-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 22px 24px;
  border-top: 1px solid var(--color-line);
  font-size: 14px;
}
.recent-row span {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.recent-row time {
  font-size: 12px;
  color: var(--color-muted);
}
.recent-state {
  padding: 24px;
}
@media (max-width: 760px) {
  .quick-links {
    grid-template-columns: 1fr;
    gap: 12px;
  }
}
@media (max-width: 600px) {
  .dashboard-page {
    padding: 24px 16px;
  }
  .recent-row {
    padding: 18px;
  }
}
</style>
