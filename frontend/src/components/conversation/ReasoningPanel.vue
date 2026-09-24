<script setup lang="ts">
import { ArrowRight } from '@element-plus/icons-vue'
import { ref, useId } from 'vue'

defineProps<{ content: string }>()
const expanded = ref(false)
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
      深度思考
    </button>
    <div class="reasoning-collapse" :class="{ expanded }" :inert="!expanded">
      <div :id="contentId" class="reasoning-clip" :aria-hidden="!expanded">
        <div class="reasoning-body">{{ content }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.reasoning-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  font-weight: 600;
  text-align: left;
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
  max-height: 320px;
  overflow: auto;
  margin-top: 8px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
@media (prefers-reduced-motion: no-preference) {
  .reasoning-toggle .el-icon {
    transition: transform var(--motion-duration-layout) var(--motion-ease);
  }
  .reasoning-collapse {
    transition:
      grid-template-rows var(--motion-duration-layout) var(--motion-ease),
      visibility var(--motion-duration-layout);
  }
}
</style>
