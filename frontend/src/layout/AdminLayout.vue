<script setup lang="ts">
import { Expand, Fold, Menu } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useLayoutStore } from '../store'
import AppSidebar from './AppSidebar.vue'
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
        <span>{{ String(route.meta.title ?? '工作台') }}</span>
      </header>
      <main class="main-content"><RouterView /></main>
    </div>
  </div>
</template>
<style scoped>
.admin-layout {
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
  height: 64px;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 32px;
  background: white;
  border-bottom: 1px solid var(--color-line);
  font-size: 14px;
  color: #637086;
}
.topbar .el-button {
  margin: 0;
  padding: 8px;
  color: #738096;
}
.main-content {
  min-height: calc(100dvh - 64px);
}
.mobile-toggle {
  display: none;
}
@media (max-width: 1000px) {
  .desktop-toggle {
    display: none;
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
}
</style>
