<script setup lang="ts">
import { ArrowDown, Expand, Fold, Menu } from '@element-plus/icons-vue'
import { storeToRefs } from 'pinia'
import { computed } from 'vue'
import { useRoute } from 'vue-router'

import { useLayoutStore } from '../store'
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
          <el-dropdown trigger="click">
            <button class="profile" type="button">
              <span class="profile-avatar">JA</span>
              <span class="profile-copy">
                <strong>本地用户</strong>
                <small>管理员</small>
              </span>
              <el-icon><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>单用户本地模式</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
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
.topbar-actions,
.profile {
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

.profile {
  gap: 9px;
  margin-left: 4px;
  padding: 4px;
  border-radius: 9px;
}

.profile:hover {
  background: #f5f7fb;
}

.profile-avatar {
  width: 33px;
  height: 33px;
  display: grid;
  place-items: center;
  color: #ffffff;
  font-family: var(--font-data);
  font-size: 11px;
  font-weight: 700;
  background: #4263eb;
  border-radius: 9px;
}

.profile-copy {
  display: flex;
  flex-direction: column;
  text-align: left;
}

.profile-copy strong {
  color: #35425a;
  font-size: 12px;
  font-weight: 650;
}

.profile-copy small {
  margin-top: 2px;
  color: #919bad;
  font-size: 10px;
}

.profile .el-icon {
  margin: 0 5px 0 2px;
  color: #9ca6b6;
  font-size: 11px;
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
  .profile-copy,
  .profile .el-icon {
    display: none;
  }

  .main-content {
    min-height: calc(100vh - 60px);
    min-height: calc(100dvh - 60px);
  }
}
</style>
