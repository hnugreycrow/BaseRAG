<script setup lang="ts">
import { ChatDotRound, Collection, Cpu, HomeFilled } from '@element-plus/icons-vue'
import type { Component } from 'vue'
import { useRoute } from 'vue-router'

defineProps<{
  collapsed?: boolean
}>()

const emit = defineEmits<{
  navigate: []
}>()

interface MenuItem {
  label: string
  path: string
  icon: Component
}

const route = useRoute()
const menuItems: MenuItem[] = [
  { label: '工作台', path: '/', icon: HomeFilled },
  { label: '知识库', path: '/knowledge-bases', icon: Collection },
  { label: '问答会话', path: '/conversations', icon: ChatDotRound },
  { label: '模型配置', path: '/models', icon: Cpu },
]
</script>

<template>
  <aside class="sidebar" :class="{ 'is-collapsed': collapsed }">
    <RouterLink class="brand" to="/" aria-label="返回 JAgent 工作台" @click="emit('navigate')">
      <span class="brand-mark" aria-hidden="true">
        <i></i>
        <i></i>
        <i></i>
        <i></i>
      </span>
      <span v-show="!collapsed" class="brand-copy">
        <strong>JAgent</strong>
        <small>KNOWLEDGE STUDIO</small>
      </span>
    </RouterLink>

    <div v-show="!collapsed" class="menu-caption">工作空间</div>
    <el-menu
      :default-active="route.path"
      :collapse="collapsed"
      :collapse-transition="false"
      router
      class="sidebar-menu"
      @select="emit('navigate')"
    >
      <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
        <el-icon><component :is="item.icon" /></el-icon>
        <template #title>{{ item.label }}</template>
      </el-menu-item>
    </el-menu>

    <div class="runtime" :title="collapsed ? '本地服务' : undefined">
      <span class="runtime-signal" aria-hidden="true"></span>
      <span v-show="!collapsed" class="runtime-copy">
        <strong>本地工作区</strong>
        <small>数据保留在当前环境</small>
      </span>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 248px;
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 20px 14px 16px;
  color: #c7d1e3;
  background: #17233c;
  transition: width 180ms ease;
}

.sidebar.is-collapsed {
  width: 80px;
}

.brand {
  min-height: 42px;
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0 8px 32px;
  border-radius: 8px;
}

.brand:focus-visible {
  outline-color: #8fa5ff;
}

.brand-mark {
  position: relative;
  width: 38px;
  height: 38px;
  display: grid;
  flex: 0 0 38px;
  grid-template-columns: repeat(2, 6px);
  place-content: center;
  gap: 4px;
  background: #4263eb;
  border-radius: 12px 12px 4px 12px;
  box-shadow: 0 10px 24px rgb(2 10 30 / 28%);
}

.brand-mark::after {
  position: absolute;
  right: 5px;
  bottom: 5px;
  width: 5px;
  height: 5px;
  content: '';
  background: #68dfc5;
  border: 2px solid #4263eb;
  border-radius: 50%;
}

.brand-mark i {
  width: 6px;
  height: 6px;
  background: #ffffff;
  border-radius: 50%;
  box-shadow: 0 0 0 1px rgb(255 255 255 / 10%);
}

.brand-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.brand-copy strong {
  color: #ffffff;
  font-size: 17px;
  line-height: 1.1;
  letter-spacing: 0.1px;
}

.brand-copy small {
  margin-top: 6px;
  color: #70809e;
  font-family: var(--font-data);
  font-size: 9px;
  letter-spacing: 1.25px;
  white-space: nowrap;
}

.menu-caption {
  margin: 0 14px 9px;
  color: #6f7e99;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 1.2px;
}

.sidebar-menu {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #9eacc2;
  --el-menu-hover-bg-color: rgb(255 255 255 / 5%);
  --el-menu-active-color: #ffffff;
  border-right: 0;
}

.sidebar-menu :deep(.el-menu-item) {
  position: relative;
  height: 46px;
  margin-bottom: 5px;
  border-radius: 9px;
}

.sidebar-menu :deep(.el-menu-item.is-active) {
  background: rgb(255 255 255 / 7%);
}

.sidebar-menu :deep(.el-menu-item.is-active::before) {
  position: absolute;
  left: 0;
  width: 3px;
  height: 20px;
  content: '';
  background: #68dfc5;
  border-radius: 0 3px 3px 0;
}

.sidebar-menu :deep(.el-icon) {
  font-size: 18px;
}

.runtime {
  min-height: 55px;
  display: flex;
  align-items: center;
  gap: 11px;
  margin-top: auto;
  padding: 13px 12px 0;
  border-top: 1px solid rgb(255 255 255 / 8%);
}

.runtime-signal {
  width: 9px;
  height: 9px;
  flex: 0 0 9px;
  background: #58d5b9;
  border: 2px solid #17233c;
  border-radius: 50%;
  box-shadow: 0 0 0 3px rgb(88 213 185 / 15%);
}

.runtime-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.runtime-copy strong {
  color: #cbd5e5;
  font-size: 12px;
  font-weight: 600;
}

.runtime-copy small {
  margin-top: 3px;
  overflow: hidden;
  color: #72819d;
  font-size: 10px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.is-collapsed .brand,
.is-collapsed .runtime {
  justify-content: center;
  margin-inline: 0;
}

.is-collapsed .sidebar-menu :deep(.el-menu-item) {
  justify-content: center;
  padding: 0 !important;
}

@media (max-width: 760px) {
  .sidebar,
  .sidebar.is-collapsed {
    width: 100%;
  }
}
</style>
