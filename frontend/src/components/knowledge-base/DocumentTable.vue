<script setup lang="ts">
import { MoreFilled, RefreshRight, Scissor } from '@element-plus/icons-vue'

import { ref, watch } from 'vue'
import type { TableInstance } from 'element-plus'
import type { KnowledgeDocument } from '../../api'

const props = defineProps<{
  rows: KnowledgeDocument[]
  loading: boolean
  processingIds: Set<string>
  selectedIds: Set<string>
}>()

const emit = defineEmits<{
  open: [row: KnowledgeDocument]
  preview: [row: KnowledgeDocument]
  rename: [row: KnowledgeDocument]
  remove: [row: KnowledgeDocument]
  chunk: [row: KnowledgeDocument]
  toggleSelection: [id: string]
  selectionChange: [rows: KnowledgeDocument[]]
}>()

const statusMeta = {
  UPLOADED: { label: '待分块', type: 'warning' },
  PROCESSING: { label: '处理中', type: 'primary' },
  READY: { label: '已完成', type: 'success' },
  FAILED: { label: '处理失败', type: 'danger' },
} as const

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
})

function formatDate(value: string) {
  return dateFormatter.format(new Date(value))
}

function canOpen(row: KnowledgeDocument) {
  return row.chunkCount > 0
}

const tableRef = ref<TableInstance>()
let syncingSelection = false

watch(
  [() => props.selectedIds, () => props.rows],
  () => {
    const table = tableRef.value
    if (!table) return
    const currentIds = new Set(
      (table.getSelectionRows() as KnowledgeDocument[]).map((row) => row.id),
    )
    if (
      currentIds.size === props.selectedIds.size &&
      [...currentIds].every((id) => props.selectedIds.has(id))
    )
      return
    syncingSelection = true
    try {
      table.clearSelection()
      props.rows
        .filter((row) => props.selectedIds.has(row.id))
        .forEach((row) => table.toggleRowSelection(row, true))
    } finally {
      syncingSelection = false
    }
  },
  { flush: 'post' },
)

function handleSelectionChange(rows: KnowledgeDocument[]) {
  if (!syncingSelection) emit('selectionChange', rows)
}

function rowClassName({ row }: { row: KnowledgeDocument }) {
  return canOpen(row) ? 'clickable-row' : ''
}

function handleRowClick(row: KnowledgeDocument) {
  if (canOpen(row)) emit('open', row)
}

function statusType(row: KnowledgeDocument) {
  return statusMeta[row.status].type
}

function statusLabel(row: KnowledgeDocument) {
  return statusMeta[row.status].label
}

function errorMessage(row: KnowledgeDocument) {
  return row.errorMessage || '处理失败，请根据请求 ID 查询日志'
}
</script>

<template>
  <div class="desktop-resource-table">
    <el-table
      ref="tableRef"
      :data="rows"
      row-key="id"
      class="data-table"
      :row-class-name="rowClassName"
      @row-click="handleRowClick"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="48" align="center" />
      <el-table-column label="文档" min-width="200">
        <template #default="{ row }">
          <div class="document-cell">
            <div>
              <strong :title="row.name">{{ row.name }}</strong>
              <template v-if="row.errorCode">
                <small class="error-message">{{ errorMessage(row) }}</small>
                <small class="error-code">错误代码：{{ row.errorCode }}</small>
              </template>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row)" effect="light" round>
            <i
              v-if="processingIds.has(row.id) || row.status === 'PROCESSING'"
              class="pulse-dot"
            ></i>
            {{ processingIds.has(row.id) ? '处理中' : statusLabel(row) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="分块" width="90">
        <template #default="{ row }">{{ row.chunkCount }} 块</template>
      </el-table-column>
      <el-table-column label="上传时间" width="150">
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="286" fixed="right" align="center">
        <template #default="{ row }">
          <div class="row-actions" @click.stop>
            <el-button
              class="chunk-button"
              :icon="row.status === 'READY' ? RefreshRight : Scissor"
              :loading="processingIds.has(row.id)"
              :disabled="row.status === 'PROCESSING' || processingIds.has(row.id)"
              @click="emit('chunk', row)"
            >
              {{ row.status === 'READY' ? '重新分块' : '开始分块' }}
            </el-button>
            <el-button v-if="canOpen(row)" class="open-button" text @click="emit('open', row)">
              管理分块
            </el-button>
            <el-button
              v-if="row.previewAvailable !== false"
              class="preview-button"
              text
              @click="emit('preview', row)"
            >
              预览
            </el-button>
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
        <el-empty :description="loading ? '正在加载文档' : '还没有文档'" :image-size="72" />
      </template>
    </el-table>
  </div>
  <div class="mobile-resource-list">
    <article v-for="row in rows" :key="row.id" class="resource-mobile-card">
      <h3>
        <el-checkbox
          :model-value="selectedIds.has(row.id)"
          :aria-label="'选择 ' + row.name"
          @change="emit('toggleSelection', row.id)"
        />
        <button :disabled="!canOpen(row)" @click="handleRowClick(row)">{{ row.name }}</button>
      </h3>
      <el-tag :type="statusType(row)">{{
        processingIds.has(row.id) ? '处理中' : statusLabel(row)
      }}</el-tag>
      <p>{{ row.chunkCount }} 块 · {{ formatDate(row.createdAt) }}</p>
      <div v-if="row.errorCode" class="mobile-error" role="status">
        <strong>{{ errorMessage(row) }}</strong>
        <small>错误代码：{{ row.errorCode }}</small>
      </div>
      <div class="card-actions">
        <el-button
          :loading="processingIds.has(row.id)"
          :disabled="row.status === 'PROCESSING' || processingIds.has(row.id)"
          @click="emit('chunk', row)"
          >{{
            row.status === 'READY' ? '重新分块' : row.status === 'FAILED' ? '重试分块' : '开始分块'
          }}</el-button
        >
        <el-button v-if="canOpen(row)" text @click="emit('open', row)">管理分块</el-button>
        <el-button v-if="row.previewAvailable !== false" text @click="emit('preview', row)"
          >预览</el-button
        >
        <el-button text @click="emit('rename', row)">重命名</el-button>
        <el-button text type="danger" @click="emit('remove', row)">删除</el-button>
      </div>
    </article>
    <el-empty
      v-if="!rows.length"
      :description="loading ? '正在加载文档' : '还没有文档'"
      :image-size="64"
    />
  </div>
</template>

<style scoped>
.document-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.document-cell div {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.document-cell strong {
  overflow: hidden;
  color: var(--color-ink);
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-cell small {
  color: var(--color-muted);
  font-size: 11px;
}

.document-cell .error-message,
.mobile-error strong {
  color: #b65349;
  font-weight: 600;
}

.document-cell .error-code {
  font-family: var(--font-data);
}

.mobile-error {
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: var(--color-muted);
  font-size: 12px;
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.row-actions :deep(.el-button) {
  margin-left: 0;
  padding-right: 7px;
  padding-left: 7px;
}

.chunk-button {
  color: var(--color-primary);
  background: var(--color-primary-soft);
  border-color: transparent;
}

.open-button {
  color: var(--color-primary);
  font-weight: 600;
}

.preview-button {
  color: #3d587e;
  font-weight: 600;
}

.pulse-dot {
  width: 6px;
  height: 6px;
  display: inline-block;
  margin-right: 4px;
  background: currentColor;
  border-radius: 50%;
  animation: pulse 1.1s ease-in-out infinite;
}

@keyframes pulse {
  50% {
    opacity: 0.35;
  }
}
</style>
