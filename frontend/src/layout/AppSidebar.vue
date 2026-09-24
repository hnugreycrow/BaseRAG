<script setup lang="ts">
import { Collection, Cpu, HomeFilled, Share, User, Connection } from '@element-plus/icons-vue'
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
  ...(auth.user?.role === 'ADMIN'
    ? [
        { label: '工作台', path: '/admin', icon: HomeFilled },
        { label: '知识库管理', path: '/admin/knowledge-bases', icon: Collection },
        { label: '意图树', path: '/admin/intent-tree', icon: Connection },
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
      <span v-if="!collapsed" class="nav-label">工作空间</span>
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
    <div v-if="mode === 'chat'" class="sidebar-account">
      <RouterLink
        v-if="auth.user?.role === 'ADMIN'"
        class="management-link"
        to="/admin"
        title="管理后台"
        aria-label="管理后台"
        @click="emit('navigate')"
      >
        <el-icon aria-hidden="true"><HomeFilled /></el-icon>
        <span v-if="!collapsed">管理后台</span>
      </RouterLink>
      <AccountMenu :compact="collapsed" />
    </div>
  </aside>
</template>
<style scoped>
.sidebar {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 26px 16px 16px;
  background: white;
  border-right: 1px solid var(--color-line);
}
.brand {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 8px;
  margin-bottom: 28px;
}
.brand strong {
  white-space: nowrap;
  font-size: 20px;
  font-weight: 750;
  letter-spacing: -0.4px;
}
.brand-mark {
  width: 29px;
  height: 29px;
  display: grid;
  place-items: center;
  color: white;
  background: var(--color-primary);
  border-radius: 9px;
  font-size: 20px;
  flex-shrink: 0;
  transform: rotate(-8deg);
}
.nav-label {
  padding: 12px 12px 10px;
  color: #9299a7;
  font-size: 11px;
  letter-spacing: 1px;
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
  font-size: 13px;
  white-space: nowrap;
}
.workspace-nav a:hover {
  background: #f0f3f8;
}
.workspace-nav a.active {
  position: relative;
  background: #eaf0ff;
  color: var(--color-primary);
  font-weight: 600;
}
.workspace-nav a.active::before {
  content: '';
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: -9px;
  width: 3px;
  border-radius: 4px;
  background: var(--color-primary);
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
  flex-shrink: 0;
  margin-top: auto;
  padding-top: 8px;
}
.management-link {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 36px;
  margin-bottom: 8px;
  padding: 0 8px;
  border-radius: 8px;
  color: #637086;
  font-size: 13px;
}
.management-link:hover,
.management-link:focus-visible {
  color: var(--color-primary);
  background: #f0f3f8;
}
.is-collapsed .management-link {
  justify-content: center;
  padding: 0;
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
@media (prefers-reduced-motion: no-preference) {
  .sidebar {
    transition: padding var(--motion-duration-layout) var(--motion-ease);
  }
  .workspace-nav a {
    transition:
      color var(--motion-duration-fast) ease,
      background-color var(--motion-duration-fast) ease;
  }
}
</style>
