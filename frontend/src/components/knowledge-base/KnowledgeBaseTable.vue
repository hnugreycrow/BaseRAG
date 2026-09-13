<script setup lang="ts">
import { Delete, EditPen, FolderOpened } from '@element-plus/icons-vue'

import type { KnowledgeBase } from '../../api'

defineProps<{
  rows: KnowledgeBase[]
  loading: boolean
}>()

const emit = defineEmits<{
  open: [row: KnowledgeBase]
  rename: [row: KnowledgeBase]
  remove: [row: KnowledgeBase]
}>()

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

function formatDate(value: string) {
  return dateFormatter.format(new Date(value))
}
</script>

<template>
  <el-table
    :data="rows"
    row-key="id"
    class="data-table"
    :row-class-name="() => 'clickable-row'"
    @row-click="emit('open', $event)"
  >
    <el-table-column label="知识库" min-width="280">
      <template #default="{ row }">
        <div class="name-cell">
          <span class="resource-icon"
            ><el-icon><FolderOpened /></el-icon
          ></span>
          <div>
            <strong>{{ row.name }}</strong>
            <small>{{ row.id.slice(0, 8) }}</small>
          </div>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="向量模型" min-width="230">
      <template #default="{ row }">
        <span class="model-name">{{ row.embeddingModel }}</span>
      </template>
    </el-table-column>
    <el-table-column prop="embeddingDimensions" label="维度" width="110" />
    <el-table-column label="文档" width="110">
      <template #default="{ row }">{{ row.documentCount }} 份</template>
    </el-table-column>
    <el-table-column label="创建时间" width="150">
      <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
    </el-table-column>
    <el-table-column label="操作" width="244" fixed="right" align="center">
      <template #default="{ row }">
        <div class="row-actions" @click.stop>
          <el-button class="open-button" text @click="emit('open', row)">打开</el-button>
          <el-button text :icon="EditPen" @click="emit('rename', row)">重命名</el-button>
          <el-button text type="danger" :icon="Delete" @click="emit('remove', row)">
            删除
          </el-button>
        </div>
      </template>
    </el-table-column>
    <template #empty>
      <el-empty :description="loading ? '正在加载知识库' : '还没有知识库'" :image-size="72" />
    </template>
  </el-table>
</template>

<style scoped>
.name-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.resource-icon {
  width: 35px;
  height: 35px;
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  color: var(--color-primary);
  background: var(--color-primary-soft);
  border-radius: 9px;
}

.name-cell div {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.name-cell strong {
  overflow: hidden;
  color: var(--color-ink);
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.name-cell small,
.model-name {
  color: var(--color-muted);
  font-family: var(--font-data);
  font-size: 11px;
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.row-actions :deep(.el-button) {
  margin-left: 0;
}

.open-button {
  color: var(--color-primary);
  font-weight: 600;
}
</style>
