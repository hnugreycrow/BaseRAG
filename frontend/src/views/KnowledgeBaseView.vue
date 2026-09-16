<script setup lang="ts">
import { ArrowLeft, Collection, Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'

import ChunkDetailDrawer from '../components/knowledge-base/ChunkDetailDrawer.vue'
import ChunkTable from '../components/knowledge-base/ChunkTable.vue'
import CreateKnowledgeBaseDialog from '../components/knowledge-base/CreateKnowledgeBaseDialog.vue'
import DocumentTable from '../components/knowledge-base/DocumentTable.vue'
import KnowledgeBaseTable from '../components/knowledge-base/KnowledgeBaseTable.vue'
import RenameDialog from '../components/knowledge-base/RenameDialog.vue'
import UploadDocumentDialog from '../components/knowledge-base/UploadDocumentDialog.vue'
import { useKnowledgeBaseWorkspace } from '../components/knowledge-base/useKnowledgeBaseWorkspace'

const {
  chunkDetail,
  batchSubmitting,
  createDialogOpen,
  detailDrawerOpen,
  detailLoading,
  chunks,
  documents,
  goToDocuments,
  goToKnowledgeBases,
  handleChunk,
  handleBatchChunk,
  handleCreate,
  handlePageChange,
  handlePageSizeChange,
  handleRemoveDocument,
  handleRemoveKnowledgeBase,
  handleRename,
  handleUpload,
  level,
  loading,
  knowledgeBases,
  models,
  openChunk,
  openDocument,
  openKnowledgeBase,
  openRename,
  page,
  pageSize,
  pageTitle,
  processingIds,
  selectedIds,
  selectedCount,
  toggleSelection,
  setSelection,
  query,
  refreshCurrent,
  renameDialogOpen,
  renameMaxLength,
  renameTarget,
  renameTitle,
  saving,
  searchPlaceholder,
  selectedChunk,
  selectedDocument,
  selectedKnowledgeBase,
  total,
  uploading,
  uploadResult,
  uploadDialogOpen,
} = useKnowledgeBaseWorkspace()
</script>

<template>
  <div class="knowledge-page">
    <nav v-if="selectedKnowledgeBase" class="scope-rail" aria-label="当前位置">
      <button :class="{ active: level === 'knowledge-bases' }" @click="goToKnowledgeBases">
        <el-icon><Collection /></el-icon>
        知识库
      </button>
      <template v-if="selectedKnowledgeBase">
        <i>/</i>
        <button :class="{ active: level === 'documents' }" @click="goToDocuments">
          {{ selectedKnowledgeBase.name }}
        </button>
      </template>
      <template v-if="selectedDocument">
        <i>/</i>
        <button class="active">{{ selectedDocument.name }}</button>
      </template>
    </nav>

    <header class="page-header">
      <div class="title-group">
        <el-button
          v-if="level !== 'knowledge-bases'"
          class="back-button"
          text
          :icon="ArrowLeft"
          aria-label="返回上一级"
          @click="level === 'chunks' ? goToDocuments() : goToKnowledgeBases()"
        />
        <div>
          <h1>{{ pageTitle }}</h1>
          <span
            v-if="selectedKnowledgeBase?.embeddingProvider && level !== 'knowledge-bases'"
            class="model-binding"
          >
            {{ selectedKnowledgeBase.embeddingProvider }} ({{
              selectedKnowledgeBase.embeddingModelId
            }}) · {{ selectedKnowledgeBase.embeddingModel }} ·
            {{ selectedKnowledgeBase.embeddingDimensions }} 维
          </span>
        </div>
      </div>

      <div class="primary-actions">
        <el-button
          v-if="level === 'knowledge-bases'"
          type="primary"
          :icon="Plus"
          @click="createDialogOpen = true"
        >
          新建知识库
        </el-button>
        <el-button
          v-else-if="level === 'documents'"
          type="primary"
          :icon="Upload"
          @click="uploadDialogOpen = true"
        >
          上传文档
        </el-button>
        <el-button
          v-else-if="selectedDocument"
          :icon="Refresh"
          :loading="processingIds.has(selectedDocument.id)"
          :disabled="selectedDocument.status === 'PROCESSING'"
          @click="handleChunk(selectedDocument)"
        >
          重新分块
        </el-button>
      </div>
    </header>

    <section class="table-panel">
      <div class="table-toolbar">
        <div v-if="level === 'documents'" class="batch-actions">
          <span v-if="selectedCount > 0">已选 {{ selectedCount }} 篇</span>
          <el-button
            type="primary"
            :loading="batchSubmitting"
            :disabled="selectedCount === 0"
            @click="handleBatchChunk"
          >
            批量分块
          </el-button>
        </div>
        <div class="toolbar-actions">
          <el-input
            v-model="query"
            :aria-label="searchPlaceholder"
            :placeholder="searchPlaceholder"
            clearable
            class="search-input"
          >
            <template #prefix
              ><el-icon><Search /></el-icon
            ></template>
          </el-input>
          <el-tooltip content="刷新" placement="top">
            <el-button
              class="refresh-button"
              :icon="Refresh"
              aria-label="刷新"
              @click="refreshCurrent"
            />
          </el-tooltip>
        </div>
      </div>

      <KnowledgeBaseTable
        v-if="level === 'knowledge-bases'"
        :rows="knowledgeBases"
        :loading="loading"
        @open="openKnowledgeBase"
        @rename="(row) => openRename({ kind: 'knowledge-base', value: row })"
        @remove="handleRemoveKnowledgeBase"
      />
      <DocumentTable
        v-else-if="level === 'documents'"
        :rows="documents"
        :loading="loading"
        :processing-ids="processingIds"
        :selected-ids="selectedIds"
        @toggle-selection="toggleSelection"
        @selection-change="setSelection"
        @open="openDocument"
        @rename="(row) => openRename({ kind: 'document', value: row })"
        @remove="handleRemoveDocument"
        @chunk="handleChunk"
      />
      <ChunkTable v-else :rows="chunks" :loading="loading" @open="openChunk" />

      <footer v-if="total > 0" class="pagination-footer">
        <el-pagination
          :current-page="page"
          :page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @update:current-page="handlePageChange"
          @update:page-size="handlePageSizeChange"
        />
      </footer>
    </section>

    <CreateKnowledgeBaseDialog
      v-model="createDialogOpen"
      :models="models"
      :loading="saving"
      @submit="handleCreate"
    />
    <UploadDocumentDialog
      v-model="uploadDialogOpen"
      :loading="uploading"
      :result="uploadResult"
      @submit="handleUpload"
    />
    <RenameDialog
      v-if="renameTarget"
      v-model="renameDialogOpen"
      :title="renameTitle"
      :current-name="renameTarget.value.name"
      :max-length="renameMaxLength"
      :loading="saving"
      @submit="handleRename"
    />
    <ChunkDetailDrawer
      v-model="detailDrawerOpen"
      :chunk="selectedChunk"
      :detail="chunkDetail"
      :loading="detailLoading"
    />
  </div>
</template>

<style scoped src="../components/knowledge-base/knowledge-base-view.css"></style>
