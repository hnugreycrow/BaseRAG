<script setup lang="ts">
import { computed } from 'vue'

import type { DocumentChunk, DocumentChunkDetail } from '../../api'

const props = defineProps<{
  modelValue: boolean
  chunk: DocumentChunk | null
  detail: DocumentChunkDetail | null
  loading: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})
</script>

<template>
  <el-drawer v-model="visible" size="min(680px, 92vw)" class="chunk-drawer">
    <template #header>
      <div class="drawer-heading">
        <span>分块 {{ chunk ? String(chunk.chunkIndex + 1).padStart(2, '0') : '' }}</span>
        <h2>{{ chunk?.heading || '无标题段落' }}</h2>
      </div>
    </template>

    <div v-if="chunk" class="chunk-meta">
      <span>原文 L{{ chunk.lineStart }}–{{ chunk.lineEnd }}</span>
      <i></i>
      <span>{{ chunk.characterCount }} 字符</span>
    </div>

    <div v-if="loading" class="detail-loading">
      <i v-for="index in 7" :key="index" :style="{ width: `${96 - (index % 3) * 11}%` }"></i>
    </div>
    <pre v-else-if="detail" class="chunk-content">{{ detail.content }}</pre>
    <el-empty v-else description="无法读取分块内容" />
  </el-drawer>
</template>

<style scoped>
.drawer-heading span {
  color: var(--color-primary);
  font-family: var(--font-data);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.8px;
}

.drawer-heading h2 {
  margin: 5px 0 0;
  color: var(--color-ink);
  font-size: 18px;
}

.chunk-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
  color: var(--color-muted);
  font-family: var(--font-data);
  font-size: 10px;
}

.chunk-meta i {
  width: 3px;
  height: 3px;
  background: #b8c0ce;
  border-radius: 50%;
}

.chunk-content {
  min-height: 300px;
  margin: 0;
  padding: 22px;
  overflow: auto;
  color: #2c3951;
  font-family: var(--font-body);
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
  background: #f7f9fc;
  border: 1px solid var(--color-line);
  border-radius: 10px;
}

.detail-loading {
  display: flex;
  flex-direction: column;
  gap: 13px;
  padding: 22px;
}

.detail-loading i {
  height: 12px;
  background: linear-gradient(90deg, #edf0f5, #f7f8fa, #edf0f5);
  background-size: 200% 100%;
  border-radius: 5px;
  animation: shimmer 1.3s linear infinite;
}

@keyframes shimmer {
  to {
    background-position: -200% 0;
  }
}
</style>
