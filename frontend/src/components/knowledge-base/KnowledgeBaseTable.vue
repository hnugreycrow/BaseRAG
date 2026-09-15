<script setup lang="ts">
import { FolderOpened, MoreFilled } from '@element-plus/icons-vue'

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
  <div class="desktop-resource-table">
    <el-table
      :data="rows"
      row-key="id"
      class="data-table"
      :row-class-name="() => 'clickable-row'"
      @row-click="emit('open', $event)"
    >
      <el-table-column label="知识库" min-width="180">
        <template #default="{ row }">
          <div class="name-cell">
            <span class="resource-icon"
              ><el-icon><FolderOpened /></el-icon
            ></span>
            <div>
              <strong>{{ row.name }}</strong>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="向量模型" min-width="180">
        <template #default="{ row }">
          <span v-if="row.embeddingProvider" class="model-name"
            >{{ row.embeddingProvider }} · {{ row.embeddingModel }} ·
            {{ row.embeddingDimensions }} 维</span
          >
          <span v-else>未绑定</span>
        </template>
      </el-table-column>
      <el-table-column label="文档" width="110">
        <template #default="{ row }">{{ row.documentCount }} 份</template>
      </el-table-column>
      <el-table-column label="创建时间" width="150">
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right" align="center">
        <template #default="{ row }">
          <div class="row-actions" @click.stop>
            <el-button class="open-button" text @click="emit('open', row)">打开</el-button>
            <el-dropdown
              trigger="click"
              @command="
                (command: string) =>
                  command === 'rename' ? emit('rename', row) : emit('remove', row)
              "
            >
              <el-button text :icon="MoreFilled" :aria-label="'管理 ' + row.name" />
              <template #dropdown
                ><el-dropdown-menu
                  ><el-dropdown-item command="rename">重命名</el-dropdown-item
                  ><el-dropdown-item command="remove">删除</el-dropdown-item></el-dropdown-menu
                ></template
              >
            </el-dropdown>
          </div>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="loading ? '正在加载知识库' : '还没有知识库'" :image-size="72" />
      </template>
    </el-table>
  </div>
  <div class="mobile-resource-list">
    <article v-for="row in rows" :key="row.id" class="resource-mobile-card">
      <h3>
        <button @click="emit('open', row)">{{ row.name }}</button>
      </h3>
      <p v-if="row.embeddingProvider">
        {{ row.embeddingProvider }} · {{ row.embeddingModel }} · {{ row.embeddingDimensions }} 维
      </p>
      <p v-else>未绑定向量模型</p>
      <p>{{ row.documentCount }} 份文档 · {{ formatDate(row.createdAt) }}</p>
      <div class="card-actions">
        <el-button text @click="emit('open', row)">打开</el-button
        ><el-button text @click="emit('rename', row)">重命名</el-button
        ><el-button text type="danger" @click="emit('remove', row)">删除</el-button>
      </div>
    </article>
    <el-empty
      v-if="!rows.length"
      :description="loading ? '正在加载知识库' : '还没有知识库'"
      :image-size="64"
    />
  </div>
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
