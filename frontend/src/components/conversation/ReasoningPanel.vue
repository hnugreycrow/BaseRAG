<script setup lang="ts">
import { ArrowRight } from '@element-plus/icons-vue'
import { ref, useId } from 'vue'

const props = defineProps<{ content: string; live?: boolean }>()
const expanded = ref(Boolean(props.live))
const contentId = useId()
</script>

<template>
  <div class="reasoning-panel">
    <button
      type="button"
      class="reasoning-toggle"
      :aria-expanded="expanded"
      :aria-controls="contentId"
      @click="expanded = !expanded"
    >
      <el-icon aria-hidden="true" :class="{ expanded }"><ArrowRight /></el-icon>
      <span>{{ live ? '正在思考…' : '思考过程' }}</span>
      <span v-if="live" class="thinking-dot" aria-hidden="true" />
    </button>
    <div class="reasoning-collapse" :class="{ expanded }" :inert="!expanded">
      <div :id="contentId" class="reasoning-clip" :aria-hidden="!expanded">
        <div class="reasoning-body" tabindex="0" role="region" aria-label="思考过程内容">
          {{ content }}
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.reasoning-panel {
  margin-bottom: 18px;
  color: #656b76;
  font-size: 13px;
  line-height: 1.65;
}
.reasoning-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-height: 36px;
  padding: 4px 0;
  color: #656b76;
  font-weight: 400;
  text-align: left;
}
.reasoning-toggle:hover {
  color: var(--color-ink);
}
.reasoning-toggle .el-icon {
  font-size: 12px;
}
.thinking-dot {
  width: 5px;
  height: 5px;
  margin-left: 2px;
  border-radius: 50%;
  background: currentColor;
}
.reasoning-toggle .expanded {
  transform: rotate(90deg);
}
.reasoning-collapse {
  display: grid;
  grid-template-rows: 0fr;
  visibility: hidden;
}
.reasoning-collapse.expanded {
  grid-template-rows: 1fr;
  visibility: visible;
}
.reasoning-clip {
  min-height: 0;
  overflow: hidden;
}
.reasoning-body {
  max-height: 240px;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  margin: 4px 0 2px 5px;
  padding: 2px 12px 2px 14px;
  border-left: 2px solid #e5e7eb;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
@media (prefers-reduced-motion: no-preference) {
  .thinking-dot {
    animation: thinking-pulse 1.4s ease-in-out infinite;
  }
  .reasoning-toggle .el-icon {
    transition: transform var(--motion-duration-layout) var(--motion-ease);
  }
  .reasoning-collapse {
    transition:
      grid-template-rows var(--motion-duration-layout) var(--motion-ease),
      visibility var(--motion-duration-layout);
  }
}
@keyframes thinking-pulse {
  50% {
    opacity: 0.35;
  }
}
</style>
