<script setup lang="ts">
import { ChatDotRound, Expand, Fold, Menu } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useLayoutStore } from '../store'
import AppSidebar from './AppSidebar.vue'
import AccountMenu from '../components/auth/AccountMenu.vue'
const route = useRoute()
const layout = useLayoutStore()
const { mobileMenuOpen, sidebarCollapsed } = storeToRefs(layout)
const tablet = ref(window.innerWidth <= 1000)
function resize() {
  tablet.value = window.innerWidth <= 1000
}
window.addEventListener('resize', resize)
onBeforeUnmount(() => window.removeEventListener('resize', resize))
const collapsed = computed(() => tablet.value || sidebarCollapsed.value)
</script>
<template>
  <div class="admin-layout">
    <div class="desktop-sidebar" :class="{ 'is-collapsed': collapsed }">
      <AppSidebar :collapsed="collapsed" />
    </div>
    <el-drawer
      v-model="mobileMenuOpen"
      direction="ltr"
      size="264px"
      :with-header="false"
      class="navigation-drawer"
      aria-label="导航"
    >
      <AppSidebar @navigate="layout.closeMobileMenu" />
    </el-drawer>
    <div class="workspace">
      <header class="topbar">
        <el-button
          class="desktop-toggle"
          text
          :icon="sidebarCollapsed ? Expand : Fold"
          aria-label="切换侧边栏"
          @click="layout.toggleSidebar"
        />
        <el-button
          class="mobile-toggle"
          text
          :icon="Menu"
          aria-label="打开导航"
          @click="layout.openMobileMenu"
        />
        <nav class="breadcrumb" aria-label="当前位置">
          <RouterLink to="/admin">BaseRAG</RouterLink>
          <span aria-hidden="true">/</span>
          <span class="current-page">{{ String(route.meta.title ?? 'Dashboard') }}</span>
        </nav>
        <div class="topbar-actions">
          <RouterLink class="chat-link" to="/chat">
            <el-icon aria-hidden="true"><ChatDotRound /></el-icon>
            <span>知识问答</span>
          </RouterLink>
          <span class="account-divider" aria-hidden="true" />
          <AccountMenu class="topbar-account" />
        </div>
      </header>
      <main class="main-content"><RouterView /></main>
    </div>
  </div>
</template>
<style scoped>
.admin-layout {
  --main-content-padding: 29px 30px 45px;
  min-height: 100dvh;
  display: flex;
  background: var(--color-canvas);
}
.desktop-sidebar {
  position: sticky;
  top: 0;
  width: 224px;
  height: 100dvh;
  flex: 0 0 224px;
}
.desktop-sidebar.is-collapsed {
  width: 72px;
  flex-basis: 72px;
}
.workspace {
  min-width: 0;
  flex: 1;
}
.topbar {
  height: 70px;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 30px;
  background: white;
  border-bottom: 1px solid var(--color-line);
  font-size: 12px;
  color: #637086;
}
.topbar .el-button {
  margin: 0;
  padding: 8px;
  color: #738096;
}
.main-content {
  padding: var(--main-content-padding);
  min-height: calc(100dvh - 70px);
}
.breadcrumb,
.topbar-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}
.breadcrumb {
  min-width: 0;
  color: var(--color-muted);
}
.current-page {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text);
}
.topbar-actions {
  margin-left: auto;
  flex-shrink: 0;
}
.chat-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid #e4ebff;
  border-radius: 8px;
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}
.chat-link .el-icon {
  font-size: 16px;
}
.chat-link:hover {
  border-color: #cbd9ff;
  background: #e3ebff;
}
.account-divider {
  width: 1px;
  height: 18px;
  background: var(--color-line);
}
.topbar-account :deep(.profile) {
  min-height: 40px;
  gap: 9px;
  padding: 4px 8px 4px 4px;
  border-radius: 8px;
}
.topbar-account :deep(.profile:hover),
.topbar-account :deep(.profile[aria-expanded='true']) {
  background: #f3f5f8;
}
.topbar-account :deep(.avatar) {
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  background: #edf1f8;
  color: #536da0;
}
.topbar-account :deep(.copy strong) {
  max-width: 120px;
  font-size: 13px;
  font-weight: 500;
}
.topbar-account :deep(.copy small) {
  display: none;
}
.topbar-account :deep(.arrow) {
  margin: 0 0 0 3px;
  color: #8a95a7;
}
@media (prefers-reduced-motion: no-preference) {
  .desktop-sidebar {
    transition:
      width var(--motion-duration-layout) var(--motion-ease),
      flex-basis var(--motion-duration-layout) var(--motion-ease);
  }
  .chat-link,
  .topbar-account :deep(.profile) {
    transition:
      background-color 0.15s,
      border-color 0.15s;
  }
}
.mobile-toggle {
  display: none;
}
@media (max-width: 1000px) {
  .desktop-toggle {
    display: none;
  }
}
@media (max-width: 760px) {
  .admin-layout {
    --main-content-padding: 24px 16px 36px;
  }
}
@media (max-width: 600px) {
  .desktop-sidebar {
    display: none;
  }
  .mobile-toggle {
    display: inline-flex;
  }
  .topbar {
    height: 56px;
    padding-inline: 16px;
    gap: 8px;
  }
  .main-content {
    min-height: calc(100dvh - 56px);
  }
  .breadcrumb > a,
  .breadcrumb > span[aria-hidden] {
    display: none;
  }
  .topbar-actions {
    gap: 8px;
  }
  .account-divider,
  .topbar-account :deep(.copy),
  .topbar-account :deep(.arrow) {
    display: none;
  }
  .topbar-account :deep(.profile) {
    padding: 4px;
  }
  .chat-link {
    min-height: 40px;
    padding-inline: 10px;
  }
}
</style>
