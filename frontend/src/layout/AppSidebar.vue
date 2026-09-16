<script setup lang="ts">
import { ChatDotRound, Collection, Cpu, HomeFilled, Share, User } from '@element-plus/icons-vue'
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../store'
import AccountMenu from '../components/auth/AccountMenu.vue'
withDefaults(defineProps<{ collapsed?: boolean; mode?: 'chat' | 'admin' }>(), {
  mode: 'admin',
})
const emit = defineEmits<{ navigate: [] }>()
const route = useRoute()
const auth = useAuthStore()
const activeMenu = computed(() =>
  String(route.meta.activeMenu ?? (route.path.startsWith('/chat') ? '/chat' : route.path)),
)
const menuItems = computed(() => [
  { label: '知识问答', path: '/chat', icon: ChatDotRound },
  ...(auth.user?.role === 'ADMIN'
    ? [
        { label: '工作台', path: '/admin', icon: HomeFilled },
        { label: '知识库', path: '/admin/knowledge-bases', icon: Collection },
        { label: '链路追踪', path: '/admin/observability', icon: Share },
        { label: '模型', path: '/admin/models', icon: Cpu },
        { label: '用户管理', path: '/admin/users', icon: User },
      ]
    : []),
])
</script>
<template>
  <aside
    class="sidebar"
    :class="{ 'is-collapsed': collapsed, 'is-chat': mode === 'chat' }"
    aria-label="主导航"
  >
    <RouterLink class="brand" to="/chat" aria-label="BaseRAG 首页" @click="emit('navigate')">
      <span class="brand-mark"
        ><el-icon><Collection /></el-icon></span
      ><strong v-if="!collapsed">BaseRAG</strong>
    </RouterLink>
    <nav v-if="mode === 'admin'" class="workspace-nav">
      <RouterLink
        v-for="item in menuItems"
        :key="item.path"
        :to="item.path"
        :title="item.label"
        :aria-label="item.label"
        :aria-current="activeMenu === item.path ? 'page' : undefined"
        :class="{ active: activeMenu === item.path }"
        @click="emit('navigate')"
      >
        <el-icon><component :is="item.icon" /></el-icon
        ><span v-if="!collapsed">{{ item.label }}</span>
      </RouterLink>
    </nav>
    <div v-if="$slots.default && !collapsed" class="sidebar-content"><slot /></div>
    <div class="sidebar-account"><AccountMenu :compact="collapsed" /></div>
  </aside>
</template>
<style scoped>
.sidebar {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 26px 16px 16px;
  background: #fafbfd;
  border-right: 1px solid var(--color-line);
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 8px;
  margin-bottom: 28px;
}
.brand strong {
  font-size: 19px;
  font-weight: 650;
  letter-spacing: -0.4px;
}
.brand-mark {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  color: white;
  background: var(--color-primary);
  border-radius: 9px;
  font-size: 20px;
  flex-shrink: 0;
}
.workspace-nav {
  display: grid;
  gap: 5px;
}
.workspace-nav a {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 42px;
  padding: 0 12px;
  border-radius: 8px;
  color: #637086;
  font-size: 14px;
  white-space: nowrap;
}
.workspace-nav a:hover {
  background: #f0f3f8;
}
.workspace-nav a.active {
  background: #eaf0ff;
  color: var(--color-primary);
  font-weight: 600;
}
.workspace-nav .el-icon {
  font-size: 18px;
  flex-shrink: 0;
}
.sidebar-content {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
  margin-top: 20px;
  padding-top: 12px;
  border-top: 1px solid var(--color-line);
}
.is-chat .sidebar-content {
  margin-top: 0;
  padding-top: 0;
  border-top: 0;
}
.sidebar-account {
  margin-top: auto;
  padding-top: 18px;
}
.sidebar-account :deep(.profile) {
  padding-top: 16px;
  border-top: 1px solid var(--color-line);
  border-radius: 0;
}
.is-collapsed {
  padding-inline: 12px;
}
.is-collapsed .brand,
.is-collapsed .workspace-nav a {
  justify-content: center;
  padding: 0;
}
</style>
