<script setup lang="ts">
import { View } from '@element-plus/icons-vue'

import type { DocumentChunk } from '../../api'

defineProps<{
  rows: DocumentChunk[]
  loading: boolean
}>()

const emit = defineEmits<{
  open: [row: DocumentChunk]
}>()
</script>

<template>
  <el-table
    :data="rows"
    row-key="id"
    class="data-table"
    :row-class-name="() => 'clickable-row'"
    @row-click="emit('open', $event)"
  >
    <el-table-column label="序号" width="92">
      <template #default="{ row }">
        <span class="chunk-index">{{ String(row.chunkIndex + 1).padStart(2, '0') }}</span>
      </template>
    </el-table-column>
    <el-table-column label="标题" min-width="210">
      <template #default="{ row }">
        <strong class="heading">{{ row.heading || '无标题段落' }}</strong>
      </template>
    </el-table-column>
    <el-table-column label="内容预览" min-width="380">
      <template #default="{ row }">
        <span class="preview">{{ row.preview }}</span>
      </template>
    </el-table-column>
    <el-table-column label="原文行" width="130">
      <template #default="{ row }">L{{ row.lineStart }}–{{ row.lineEnd }}</template>
    </el-table-column>
    <el-table-column label="字符数" width="110">
      <template #default="{ row }">{{ row.characterCount }}</template>
    </el-table-column>
    <el-table-column label="操作" width="110" fixed="right" align="right">
      <template #default="{ row }">
        <el-button text :icon="View" class="view-button" @click.stop="emit('open', row)">
          查看
        </el-button>
      </template>
    </el-table-column>
    <template #empty>
      <el-empty :description="loading ? '正在加载分块' : '该文档暂无分块'" :image-size="72" />
    </template>
  </el-table>
</template>

<style scoped>
.chunk-index {
  color: var(--color-primary);
  font-family: var(--font-data);
  font-size: 12px;
  font-weight: 700;
}

.heading {
  color: var(--color-ink);
  font-size: 13px;
  font-weight: 650;
}

.preview {
  overflow: hidden;
  display: -webkit-box;
  color: #657187;
  font-size: 12px;
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.view-button {
  color: var(--color-primary);
  font-weight: 600;
}
</style>
