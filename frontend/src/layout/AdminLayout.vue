<script setup lang="ts">
import { Expand, Fold, Menu } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { computed } from 'vue'
import { useRoute } from 'vue-router'

import { useLayoutStore } from '../store'
import AccountMenu from '../components/auth/AccountMenu.vue'
import AppSidebar from './AppSidebar.vue'

const route = useRoute()
const layoutStore = useLayoutStore()
const { mobileMenuOpen, sidebarCollapsed } = storeToRefs(layoutStore)

const pageTitle = computed(() => String(route.meta.title ?? '工作台'))
const pageSection = computed(() => String(route.meta.section ?? '概览'))

function handleMenuButton() {
  if (window.matchMedia('(max-width: 760px)').matches) {
    layoutStore.openMobileMenu()
    return
  }

  layoutStore.toggleSidebar()
}
</script>

<template>
  <div class="admin-layout">
    <div class="desktop-sidebar" :class="{ 'is-collapsed': sidebarCollapsed }">
      <AppSidebar :collapsed="sidebarCollapsed" />
    </div>

    <el-drawer
      v-model="mobileMenuOpen"
      direction="ltr"
      size="280px"
      :show-close="false"
      :with-header="false"
      class="mobile-drawer"
    >
      <AppSidebar @navigate="layoutStore.closeMobileMenu" />
    </el-drawer>

    <div class="workspace">
      <header class="topbar">
        <div class="topbar-leading">
          <el-button
            class="menu-button"
            text
            :icon="sidebarCollapsed ? Expand : Fold"
            aria-label="切换侧边栏"
            @click="handleMenuButton"
          />
          <el-button
            class="mobile-menu-button"
            text
            :icon="Menu"
            aria-label="打开菜单"
            @click="layoutStore.openMobileMenu"
          />
          <div class="breadcrumb">
            <span v-if="pageSection">{{ pageSection }}</span>
            <i v-if="pageSection">/</i>
            <strong>{{ pageTitle }}</strong>
          </div>
        </div>

        <div class="topbar-actions">
          <AccountMenu />
        </div>
      </header>

      <main class="main-content">
        <RouterView v-slot="{ Component }">
          <Transition name="page" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>
</template>

<style scoped>
.admin-layout {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  background: var(--color-canvas);
}

.desktop-sidebar {
  position: sticky;
  top: 0;
  z-index: 20;
  width: 248px;
  height: 100vh;
  height: 100dvh;
  flex: 0 0 248px;
  transition:
    width 180ms ease,
    flex-basis 180ms ease;
}

.desktop-sidebar.is-collapsed {
  width: 80px;
  flex-basis: 80px;
}

.workspace {
  min-width: 0;
  flex: 1;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 15;
  height: 68px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 0 30px;
  background: rgb(255 255 255 / 90%);
  border-bottom: 1px solid var(--color-line);
  backdrop-filter: blur(14px);
}

.topbar-leading,
.topbar-actions {
  display: flex;
  align-items: center;
}

.topbar-leading {
  min-width: 0;
  gap: 18px;
}

.menu-button,
.mobile-menu-button {
  width: 34px;
  height: 34px;
  color: #66738a;
  border: 1px solid #e3e8f0;
  border-radius: 8px;
}

.mobile-menu-button {
  display: none;
}

.breadcrumb {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 9px;
  color: #96a0b2;
  font-size: 13px;
  white-space: nowrap;
}

.breadcrumb i {
  color: #cbd1db;
  font-style: normal;
}

.breadcrumb strong {
  overflow: hidden;
  color: #31405a;
  font-weight: 600;
  text-overflow: ellipsis;
}

.topbar-actions {
  gap: 10px;
}

.main-content {
  min-height: calc(100vh - 68px);
  min-height: calc(100dvh - 68px);
}

.page-enter-active,
.page-leave-active {
  transition:
    opacity 120ms ease,
    transform 120ms ease;
}

.page-enter-from,
.page-leave-to {
  opacity: 0;
  transform: translateY(4px);
}

:global(.mobile-drawer .el-drawer__body) {
  padding: 0;
}

@media (max-width: 760px) {
  .desktop-sidebar,
  .menu-button {
    display: none;
  }

  .mobile-menu-button {
    display: inline-flex;
  }

  .topbar {
    height: 60px;
    padding: 0 16px;
  }

  .topbar-leading {
    gap: 12px;
  }

  .breadcrumb > span,
  .breadcrumb > i,
  .main-content {
    min-height: calc(100vh - 60px);
    min-height: calc(100dvh - 60px);
  }
}
</style>
