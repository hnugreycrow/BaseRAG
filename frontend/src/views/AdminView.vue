<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { RouterLink } from "vue-router";
import AppIcon from "../components/AppIcon.vue";
import {
  createKnowledgeBase,
  createDocumentChunks,
  deleteDocument,
  deleteKnowledgeBase,
  errorMessage,
  getChunk,
  importDocument,
  listChunks,
  listDocuments,
  listKnowledgeBases,
  listEmbeddingModels,
  renameDocument,
  renameKnowledgeBase,
  statusLabel,
  type ChunkDetail,
  type ChunkRecord,
  type DocumentRecord,
  type KnowledgeBase,
  type EmbeddingModel,
} from "../api/index";
import { connectWorkspace } from "../stores/workspace";

type Level = "bases" | "documents" | "chunks";
const level = ref<Level>("bases");
const bases = ref<KnowledgeBase[]>([]);
const documents = ref<DocumentRecord[]>([]);
const chunks = ref<ChunkRecord[]>([]);
const selectedBase = ref<KnowledgeBase>();
const selectedDocument = ref<DocumentRecord>();
const loading = ref(false);
const mutating = ref(false);
const loadError = ref("");
const search = ref("");
const page = ref(1);
const pageSize = 10;
const fileInput = ref<HTMLInputElement>();
const editDialogOpen = ref(false);
const editKind = ref<"base" | "document">("base");
const editingId = ref<string>();
const editName = ref("");
const embeddingModels = ref<EmbeddingModel[]>([]);
const selectedEmbeddingModelId = ref("");
const chunkDrawerOpen = ref(false);
const chunkDetail = ref<ChunkDetail>();
const chunkLoading = ref(false);

const levelTitle = computed(
  () => ({ bases: "知识库", documents: "文档", chunks: "分块" })[level.value],
);
const filteredRows = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase();
  const source =
    level.value === "bases"
      ? bases.value
      : level.value === "documents"
        ? documents.value
        : chunks.value;
  if (!keyword) return source;
  return source.filter((row) => {
    if ("name" in row) return row.name.toLocaleLowerCase().includes(keyword);
    return `${row.heading} ${row.preview}`
      .toLocaleLowerCase()
      .includes(keyword);
  });
});
const visibleRows = computed(() =>
  filteredRows.value.slice((page.value - 1) * pageSize, page.value * pageSize),
);

watch([level, search], () => {
  page.value = 1;
});
watch(filteredRows, (rows) => {
  page.value = Math.min(
    page.value,
    Math.max(1, Math.ceil(rows.length / pageSize)),
  );
});
onMounted(async () => {
  await Promise.all([loadBases(), loadEmbeddingModels()]);
});

async function loadEmbeddingModels() {
  try {
    embeddingModels.value = await listEmbeddingModels();
    selectedEmbeddingModelId.value =
      embeddingModels.value.find((item) => item.defaultModel)?.id ||
      embeddingModels.value[0]?.id ||
      "";
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function runLoad(task: () => Promise<void>) {
  loading.value = true;
  loadError.value = "";
  try {
    await task();
  } catch (error) {
    loadError.value = errorMessage(error);
    ElMessage.error(loadError.value);
  } finally {
    loading.value = false;
  }
}
async function loadBases() {
  await runLoad(async () => {
    bases.value = await listKnowledgeBases();
    if (selectedBase.value)
      selectedBase.value = bases.value.find(
        (item) => item.id === selectedBase.value?.id,
      );
  });
}
async function openDocuments(base: KnowledgeBase) {
  selectedBase.value = base;
  selectedDocument.value = undefined;
  documents.value = [];
  chunks.value = [];
  search.value = "";
  level.value = "documents";
  await runLoad(async () => {
    documents.value = await listDocuments(base.id);
  });
}
async function openChunks(document: DocumentRecord) {
  if (!selectedBase.value) return;
  selectedDocument.value = document;
  chunks.value = [];
  search.value = "";
  level.value = "chunks";
  await runLoad(async () => {
    chunks.value = await listChunks(selectedBase.value!.id, document.id);
  });
}
async function refreshCurrent() {
  if (level.value === "bases") return loadBases();
  if (level.value === "documents" && selectedBase.value)
    return openDocuments(selectedBase.value);
  if (level.value === "chunks" && selectedDocument.value)
    return openChunks(selectedDocument.value);
}
function goTo(target: Level) {
  if (target === "documents" && !selectedBase.value) return;
  if (target === "chunks" && !selectedDocument.value) return;
  level.value = target;
  search.value = "";
}
function showCreateBase() {
  editKind.value = "base";
  editingId.value = undefined;
  editName.value = "";
  selectedEmbeddingModelId.value =
    embeddingModels.value.find((item) => item.defaultModel)?.id ||
    embeddingModels.value[0]?.id ||
    "";
  editDialogOpen.value = true;
}
function showRenameBase(base: KnowledgeBase) {
  editKind.value = "base";
  editingId.value = base.id;
  editName.value = base.name;
  editDialogOpen.value = true;
}
function showRenameDocument(document: DocumentRecord) {
  editKind.value = "document";
  editingId.value = document.id;
  editName.value = document.name;
  editDialogOpen.value = true;
}
async function saveEdit() {
  const name = editName.value.trim();
  if (!name) return ElMessage.warning("请输入名称");
  mutating.value = true;
  try {
    if (editKind.value === "base") {
      if (editingId.value) await renameKnowledgeBase(editingId.value, name);
      else {
        if (!selectedEmbeddingModelId.value)
          return ElMessage.warning("请选择向量模型");
        await createKnowledgeBase(name, selectedEmbeddingModelId.value);
      }
      await loadBases();
      ElMessage.success(editingId.value ? "知识库名称已更新" : "知识库已创建");
    } else if (selectedBase.value && editingId.value) {
      await renameDocument(selectedBase.value.id, editingId.value, name);
      documents.value = await listDocuments(selectedBase.value.id);
      selectedBase.value.documentCount = documents.value.length;
      ElMessage.success("文档名称已更新");
    }
    editDialogOpen.value = false;
    void connectWorkspace();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    mutating.value = false;
  }
}
async function removeBase(base: KnowledgeBase) {
  try {
    await ElMessageBox.confirm(
      `删除“${base.name}”后，其中的 ${base.documentCount} 份文档及全部分块也会永久删除。`,
      "删除知识库",
      {
        confirmButtonText: "确认删除",
        cancelButtonText: "取消",
        type: "warning",
      },
    );
    mutating.value = true;
    await deleteKnowledgeBase(base.id);
    if (selectedBase.value?.id === base.id) {
      selectedBase.value = undefined;
      selectedDocument.value = undefined;
      level.value = "bases";
    }
    await loadBases();
    void connectWorkspace();
    ElMessage.success("知识库已删除");
  } catch (error) {
    if (error !== "cancel" && error !== "close")
      ElMessage.error(errorMessage(error));
  } finally {
    mutating.value = false;
  }
}
async function removeDocument(document: DocumentRecord) {
  if (!selectedBase.value) return;
  try {
    await ElMessageBox.confirm(
      `删除“${document.name}”后，其 ${document.chunkCount} 个分块也会永久删除。`,
      "删除文档",
      {
        confirmButtonText: "确认删除",
        cancelButtonText: "取消",
        type: "warning",
      },
    );
    mutating.value = true;
    await deleteDocument(selectedBase.value.id, document.id);
    documents.value = await listDocuments(selectedBase.value.id);
    selectedBase.value.documentCount = documents.value.length;
    void loadBases();
    void connectWorkspace();
    ElMessage.success("文档已删除");
  } catch (error) {
    if (error !== "cancel" && error !== "close")
      ElMessage.error(errorMessage(error));
  } finally {
    mutating.value = false;
  }
}
function chooseFile() {
  fileInput.value?.click();
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file || !selectedBase.value) return;
  if (!/\.(md|markdown)$/i.test(file.name))
    return ElMessage.warning("请选择 .md 或 .markdown 文件");
  if (!file.size) return ElMessage.warning("文档不能为空");
  if (file.size > 5 * 1024 * 1024)
    return ElMessage.warning("文件不能超过 5 MiB");
  mutating.value = true;
  try {
    await importDocument(selectedBase.value.id, file);
    documents.value = await listDocuments(selectedBase.value.id);
    selectedBase.value.documentCount = documents.value.length;
    void loadBases();
    void connectWorkspace();
    ElMessage.success("文档已上传，请点击“开始分块”生成检索索引");
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    mutating.value = false;
  }
}
async function startChunking(document: DocumentRecord) {
  if (!selectedBase.value) return;
  if (document.status === "READY") {
    try {
      await ElMessageBox.confirm(
        `重新分块会替换“${document.name}”现有的 ${document.chunkCount} 个分块和向量，是否继续？`,
        "重新分块",
        {
          confirmButtonText: "确认重新分块",
          cancelButtonText: "取消",
          type: "warning",
        },
      );
    } catch (error) {
      if (error === "cancel" || error === "close") return;
      ElMessage.error(errorMessage(error));
      return;
    }
  }
  mutating.value = true;
  try {
    const result = await createDocumentChunks(
      selectedBase.value.id,
      document.id,
    );
    documents.value = await listDocuments(selectedBase.value.id);
    void connectWorkspace();
    ElMessage.success(
      `${document.status === "READY" ? "重新分块" : "分块"}完成，共生成 ${result.chunkCount} 个分块`,
    );
  } catch (error) {
    documents.value = await listDocuments(selectedBase.value.id);
    ElMessage.error(errorMessage(error));
  } finally {
    mutating.value = false;
  }
}
async function showChunk(chunk: ChunkRecord) {
  if (!selectedBase.value || !selectedDocument.value) return;
  chunkDrawerOpen.value = true;
  chunkLoading.value = true;
  chunkDetail.value = undefined;
  try {
    chunkDetail.value = await getChunk(
      selectedBase.value.id,
      selectedDocument.value.id,
      chunk.id,
    );
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    chunkLoading.value = false;
  }
}
function date(value: string) {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime())
    ? "—"
    : parsed.toLocaleString("zh-CN", { hour12: false });
}
function shortId(value: string) {
  return value.slice(0, 8);
}
</script>

<template>
  <div class="admin-shell">
    <aside class="admin-sidebar">
      <RouterLink
        to="/admin"
        class="admin-brand"
        aria-label="JAgent 知识库管理"
      >
        <span class="brand-glyph"><i></i><i></i><i></i></span>
        <span><strong>JAgent</strong><small>KNOWLEDGE DESK</small></span>
      </RouterLink>
      <div class="nav-caption">管理</div>
      <nav aria-label="后台导航">
        <RouterLink to="/admin" class="nav-item active">
          <AppIcon name="book" :size="18" /><span>知识库管理</span>
        </RouterLink>
      </nav>
      <div class="sidebar-note">
      </div>
      <RouterLink to="/chat" class="chat-entry">
        <AppIcon name="chat" :size="17" /><span>进入知识问答</span
        ><AppIcon name="arrowRight" :size="15" />
      </RouterLink>
    </aside>

    <main class="admin-main">
      <header class="topbar">
        <div><span>后台管理</span><b>/</b><strong>知识库管理</strong></div>
      </header>
      <div class="page-wrap">
        <section class="page-heading">
          <div>
            <span class="eyebrow">KNOWLEDGE INVENTORY</span>
            <h1>知识库管理</h1>
          </div>
          <div class="heading-actions">
            <el-button :disabled="loading || mutating" @click="refreshCurrent"
              ><AppIcon name="refresh" :size="16" />刷新</el-button
            >
            <el-button
              v-if="level === 'bases'"
              type="primary"
              @click="showCreateBase"
              ><AppIcon name="plus" :size="16" />新建知识库</el-button
            >
            <el-button
              v-else-if="level === 'documents'"
              type="primary"
              :disabled="mutating"
              @click="chooseFile"
              ><AppIcon name="upload" :size="16" />导入文档</el-button
            >
            <input
              ref="fileInput"
              class="sr-only"
              type="file"
              accept=".md,.markdown,text/markdown"
              @change="upload"
            />
          </div>
        </section>

        <section class="table-panel">
          <div class="table-heading">
            <div>
              <div class="table-title-row">
                <button
                  v-if="level !== 'bases'"
                  class="back-button"
                  aria-label="返回上一级"
                  @click="goTo(level === 'chunks' ? 'documents' : 'bases')"
                >
                  <AppIcon name="arrowLeft" :size="16" />
                </button>
                <h2>{{ levelTitle }}列表</h2>
              </div>
            </div>
            <el-input
              v-model="search"
              clearable
              class="table-search"
              :placeholder="`搜索${levelTitle}`"
              ><template #prefix><AppIcon name="search" :size="16" /></template
            ></el-input>
          </div>

          <el-table
            v-if="level === 'bases'"
            v-loading="loading"
            :data="visibleRows"
            row-key="id"
            empty-text="暂无知识库，点击右上角新建"
            class="asset-table"
          >
            <el-table-column label="知识库名称" min-width="290">
              <template #default="{ row }"
                ><button class="primary-cell" @click="openDocuments(row)">
                  <span class="cell-icon"
                    ><AppIcon name="database" :size="18" /></span
                  ><span
                    ><strong>{{ row.name }}</strong
                    ><small>ID · {{ shortId(row.id) }}</small></span
                  >
                </button></template
              >
            </el-table-column>
            <el-table-column
              label="文档数"
              width="130"
              sortable
              prop="documentCount"
              ><template #default="{ row }"
                ><span class="number-cell">{{
                  row.documentCount
                }}</span></template
              ></el-table-column
            >
            <el-table-column label="向量模型" min-width="220"
              ><template #default="{ row }"
                ><span v-if="row.embeddingModel" class="model-cell"
                  >{{ row.embeddingModel }}
                  <i>{{ row.embeddingDimensions }}D</i></span
                ><span v-else class="empty-value"
                  >历史知识库，首次上传时绑定默认模型</span
                ></template
              ></el-table-column
            >
            <el-table-column label="创建时间" width="190"
              ><template #default="{ row }"
                ><span class="date-cell">{{
                  date(row.createdAt)
                }}</span></template
              ></el-table-column
            >
            <el-table-column
              label="操作"
              width="210"
              fixed="right"
              align="right"
              ><template #default="{ row }"
                ><el-button link type="primary" @click="openDocuments(row)"
                  >管理文档</el-button
                ><el-button link @click="showRenameBase(row)">重命名</el-button
                ><el-button
                  link
                  type="danger"
                  :disabled="mutating"
                  @click="removeBase(row)"
                  >删除</el-button
                ></template
              ></el-table-column
            >
          </el-table>

          <el-table
            v-else-if="level === 'documents'"
            v-loading="loading || mutating"
            :data="visibleRows"
            row-key="id"
            empty-text="暂无文档，点击右上角导入 Markdown"
            class="asset-table"
          >
            <el-table-column label="文档名称" min-width="320"
              ><template #default="{ row }"
                ><button class="primary-cell" @click="openChunks(row)">
                  <span class="cell-icon document"
                    ><AppIcon name="file" :size="18" /></span
                  ><span
                    ><strong>{{ row.name }}</strong
                    ><small>ID · {{ shortId(row.id) }}</small></span
                  >
                </button></template
              ></el-table-column
            >
            <el-table-column label="状态" width="130"
              ><template #default="{ row }"
                ><el-tag
                  :type="
                    row.status === 'READY'
                      ? 'success'
                      : row.status === 'FAILED'
                        ? 'danger'
                        : 'warning'
                  "
                  effect="light"
                  round
                  >{{ statusLabel(row.status) }}</el-tag
                ></template
              ></el-table-column
            >
            <el-table-column
              label="分块数"
              width="120"
              sortable
              prop="chunkCount"
              ><template #default="{ row }"
                ><span class="number-cell">{{ row.chunkCount }}</span></template
              ></el-table-column
            >
            <el-table-column label="导入时间" width="190"
              ><template #default="{ row }"
                ><span class="date-cell">{{
                  date(row.createdAt)
                }}</span></template
              ></el-table-column
            >
            <el-table-column
              label="操作"
              width="320"
              fixed="right"
              align="right"
              ><template #default="{ row }"
                ><el-button
                  v-if="row.status === 'READY'"
                  link
                  type="primary"
                  @click="openChunks(row)"
                  >查看分块</el-button
                ><el-button
                  v-if="row.status === 'READY'"
                  link
                  type="primary"
                  :disabled="mutating"
                  @click="startChunking(row)"
                  >重新分块</el-button
                ><el-button
                  v-else-if="row.status !== 'PROCESSING'"
                  link
                  type="primary"
                  :disabled="mutating"
                  @click="startChunking(row)"
                  >{{ row.status === "FAILED" ? "重新分块" : "开始分块" }}</el-button
                ><el-button v-else link disabled>分块中</el-button
                ><el-button link @click="showRenameDocument(row)"
                  >重命名</el-button
                ><el-button
                  link
                  type="danger"
                  :disabled="mutating"
                  @click="removeDocument(row)"
                  >删除</el-button
                ></template
              ></el-table-column
            >
          </el-table>

          <el-table
            v-else
            v-loading="loading"
            :data="visibleRows"
            row-key="id"
            empty-text="当前文档暂无可用分块"
            class="asset-table chunk-table"
          >
            <el-table-column label="序号" width="92"
              ><template #default="{ row }"
                ><span class="chunk-index"
                  >#{{ String(row.chunkIndex + 1).padStart(3, "0") }}</span
                ></template
              ></el-table-column
            >
            <el-table-column label="标题路径" min-width="220"
              ><template #default="{ row }"
                ><strong class="heading-cell">{{
                  row.heading || "正文"
                }}</strong></template
              ></el-table-column
            >
            <el-table-column label="内容预览" min-width="390"
              ><template #default="{ row }"
                ><span class="preview-cell">{{ row.preview }}</span></template
              ></el-table-column
            >
            <el-table-column label="原文行" width="120"
              ><template #default="{ row }"
                ><span class="line-cell"
                  >L{{ row.lineStart }}–{{ row.lineEnd }}</span
                ></template
              ></el-table-column
            >
            <el-table-column
              label="字符"
              width="90"
              prop="characterCount"
              sortable
            />
            <el-table-column
              label="操作"
              width="100"
              fixed="right"
              align="right"
              ><template #default="{ row }"
                ><el-button link type="primary" @click="showChunk(row)"
                  >查看详情</el-button
                ></template
              ></el-table-column
            >
          </el-table>
          <div class="table-footer">
            <span
              >显示 {{ visibleRows.length }} /
              {{ filteredRows.length }} 条</span
            ><el-pagination
              v-model:current-page="page"
              small
              background
              layout="prev, pager, next"
              :page-size="pageSize"
              :total="filteredRows.length"
            />
          </div>
        </section>
      </div>
    </main>

    <el-dialog
      v-model="editDialogOpen"
      :title="
        editKind === 'base'
          ? editingId
            ? '重命名知识库'
            : '新建知识库'
          : '重命名文档'
      "
      width="min(440px, calc(100vw - 32px))"
      destroy-on-close
    >
      <label class="field-label" for="entity-name">名称</label
      ><el-input
        id="entity-name"
        v-model="editName"
        :maxlength="editKind === 'base' ? 200 : 255"
        show-word-limit
        autofocus
        placeholder="输入清晰、易识别的名称"
        @keyup.enter="saveEdit"
      />
      <template v-if="editKind === 'base' && !editingId">
        <label class="field-label model-label" for="embedding-model"
          >向量模型</label
        >
        <el-select
          id="embedding-model"
          v-model="selectedEmbeddingModelId"
          class="model-select"
          placeholder="选择配置文件中的向量模型"
        >
          <el-option
            v-for="model in embeddingModels"
            :key="model.id"
            :label="`${model.model} · ${model.dimensions}D`"
            :value="model.id"
          >
            <span>{{ model.model }}</span>
            <small>{{ model.provider }} · {{ model.dimensions }}D</small>
          </el-option>
        </el-select>
        <p class="dialog-help">创建后不可更换</p>
      </template>
      <p v-else class="dialog-help">名称用于管理和定位知识内容，不会改变已有分块。</p>
      <template #footer
        ><el-button @click="editDialogOpen = false">取消</el-button
        ><el-button type="primary" :loading="mutating" @click="saveEdit"
          >保存</el-button
        ></template
      >
    </el-dialog>
    <el-drawer
      v-model="chunkDrawerOpen"
      title="分块详情"
      size="min(620px, 92vw)"
      destroy-on-close
    >
      <div v-loading="chunkLoading" class="chunk-detail">
        <template v-if="chunkDetail"
          ><div class="detail-kicker">
            CHUNK #{{ String(chunkDetail.chunkIndex + 1).padStart(3, "0") }}
          </div>
          <h2>{{ chunkDetail.heading || "正文" }}</h2>
          <div class="detail-meta">
            <span
              >原文 L{{ chunkDetail.lineStart }}–{{ chunkDetail.lineEnd }}</span
            ><span>{{ chunkDetail.characterCount }} 字符</span
            ><span>ID {{ shortId(chunkDetail.id) }}</span>
          </div>
          <pre>{{ chunkDetail.content }}</pre>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.admin-shell {
  --navy: #14213a;
  --blue: #315de6;
  --blue-soft: #edf2ff;
  --teal: #149783;
  --ink: #1b263b;
  --muted: #78849a;
  --line: #e3e8f1;
  display: flex;
  min-height: 100vh;
  min-height: 100dvh;
  color: var(--ink);
  background: #f4f7fb;
}
.admin-sidebar {
  position: sticky;
  top: 0;
  width: 238px;
  height: 100vh;
  height: 100dvh;
  display: flex;
  flex: 0 0 238px;
  flex-direction: column;
  padding: 22px 15px 16px;
  color: #dce5f5;
  background: var(--navy);
}
.admin-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 8px;
}
.admin-brand > span:last-child {
  display: flex;
  flex-direction: column;
  line-height: 1.1;
}
.admin-brand strong {
  color: #fff;
  font-size: 16px;
  letter-spacing: 0.2px;
}
.admin-brand small {
  margin-top: 5px;
  color: #71809d;
  font:
    10px/1 ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
  letter-spacing: 1.55px;
}
.brand-glyph {
  position: relative;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: flex-end;
  gap: 3px;
  padding: 9px 8px;
  background: #315de6;
  border-radius: 9px 9px 3px 9px;
  box-shadow: 0 8px 20px #06112755;
}
.brand-glyph i {
  width: 4px;
  background: #fff;
  border-radius: 2px 2px 0 0;
}
.brand-glyph i:nth-child(1) {
  height: 8px;
  opacity: 0.55;
}
.brand-glyph i:nth-child(2) {
  height: 14px;
}
.brand-glyph i:nth-child(3) {
  height: 11px;
  opacity: 0.78;
}
.nav-caption {
  margin: 43px 12px 9px;
  color: #63728e;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 1.4px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 11px;
  min-height: 44px;
  padding: 10px 12px;
  color: #aebbd0;
  font-size: 13px;
  border-radius: 8px;
}
.nav-item.active {
  color: #fff;
  background: #ffffff0d;
  box-shadow: inset 3px 0 #5fe0cb;
}
.nav-item i {
  min-width: 23px;
  margin-left: auto;
  padding: 1px 6px;
  color: #8fa0bb;
  font-size: 11px;
  font-style: normal;
  text-align: center;
  background: #ffffff0a;
  border-radius: 10px;
}
.sidebar-note {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: auto;
  padding: 15px 10px;
  border-top: 1px solid #ffffff12;
}
.sidebar-note > div {
  display: flex;
  flex-direction: column;
}
.sidebar-note strong {
  color: #cdd7e8;
  font-size: 12px;
  font-weight: 600;
}
.sidebar-note small {
  margin-top: 3px;
  color: #667692;
  font-size: 10px;
}
.signal {
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  border: 1px solid #43cbb436;
  border-radius: 50%;
}
.signal i {
  width: 6px;
  height: 6px;
  background: #45cdb5;
  border-radius: 50%;
  box-shadow: 0 0 0 4px #45cdb512;
}
.chat-entry {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 40px;
  padding: 9px 10px;
  color: #91a1ba;
  font-size: 13px;
  border: 1px solid #ffffff10;
  border-radius: 8px;
}
.chat-entry:hover {
  color: #fff;
  border-color: #ffffff22;
  background: #ffffff08;
}
.chat-entry span {
  flex: 1;
}
.admin-main {
  min-width: 0;
  flex: 1;
}
.topbar {
  height: 62px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 34px;
  background: #ffffffd9;
  border-bottom: 1px solid var(--line);
  backdrop-filter: blur(12px);
}
.topbar > div {
  display: flex;
  gap: 9px;
  color: #8b96a8;
  font-size: 13px;
}
.topbar b {
  color: #c1c8d3;
  font-weight: 400;
}
.topbar strong {
  color: #344158;
  font-weight: 600;
}
.topbar-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #758197;
  font-size: 12px;
}
.topbar-status i {
  width: 6px;
  height: 6px;
  background: #32b98a;
  border-radius: 50%;
  box-shadow: 0 0 0 3px #32b98a14;
}
.page-wrap {
  max-width: 1450px;
  margin: 0 auto;
  padding: 32px 36px 42px;
}
.page-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 26px;
}
.eyebrow {
  color: #8591a6;
  font:
    11px/1.2 ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
  letter-spacing: 1.7px;
}
.page-heading h1 {
  margin-top: 6px;
  color: #16223a;
  font-size: 28px;
  line-height: 1.2;
  font-weight: 700;
  letter-spacing: -0.8px;
}
.page-heading p {
  margin-top: 8px;
  color: #7a8699;
  font-size: 14px;
}
.heading-actions {
  display: flex;
  gap: 10px;
}
.heading-actions :deep(.el-button) {
  gap: 7px;
}
.table-panel {
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 10px;
  box-shadow: 0 10px 30px #2b426508;
}
.table-heading {
  min-height: 82px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 17px 22px;
  border-bottom: 1px solid #edf0f5;
}
.table-title-row {
  display: flex;
  align-items: center;
  gap: 9px;
}
.table-heading h2 {
  color: #202e47;
  font-size: 15px;
  font-weight: 650;
}
.table-heading p {
  margin-top: 5px;
  color: #919bad;
  font-size: 12px;
}
.row-count {
  min-width: 22px;
  padding: 1px 6px;
  color: #647188;
  font:
    11px ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
  text-align: center;
  background: #f1f3f7;
  border-radius: 9px;
}
.back-button {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  color: #68758b;
  border: 1px solid #e1e6ee;
  border-radius: 6px;
}
.back-button:hover {
  color: var(--blue);
  background: var(--blue-soft);
  border-color: #cfd9fa;
}
.table-search {
  width: 280px;
}
.asset-table {
  width: 100%;
  --el-table-header-bg-color: #f8f9fc;
  --el-table-row-hover-bg-color: #f8faff;
}
.asset-table::before {
  display: none;
}
.asset-table :deep(th.el-table__cell) {
  height: 43px;
  color: #748096;
  font-weight: 650;
  letter-spacing: 0.15px;
}
.asset-table :deep(td.el-table__cell) {
  padding: 11px 0;
  border-bottom-color: #edf0f4;
}
.primary-cell {
  display: flex;
  align-items: center;
  gap: 11px;
  max-width: 100%;
  text-align: left;
}
.primary-cell > span:last-child {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.primary-cell strong {
  overflow: hidden;
  max-width: 430px;
  color: #28364e;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.primary-cell small {
  margin-top: 4px;
  color: #9ca6b5;
  font:
    10px ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
  letter-spacing: 0.35px;
}
.primary-cell:hover strong {
  color: var(--blue);
}
.cell-icon {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  color: #315de6;
  background: #edf2ff;
  border: 1px solid #dce5fd;
  border-radius: 8px;
}
.cell-icon.document {
  color: #137f72;
  background: #edf8f6;
  border-color: #d5ede8;
}
.number-cell {
  display: inline-grid;
  min-width: 30px;
  height: 25px;
  place-items: center;
  color: #42516a;
  font:
    12px ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
  background: #f3f5f8;
  border-radius: 5px;
}
.model-cell {
  color: #536178;
  font:
    12px ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
}
.model-cell i {
  margin-left: 6px;
  padding: 2px 5px;
  color: #647491;
  font-style: normal;
  background: #f0f3f8;
  border-radius: 3px;
}
.empty-value,
.date-cell {
  color: #8d98aa;
  font-size: 12px;
}
.chunk-index,
.line-cell {
  color: #58677f;
  font:
    12px ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
}
.heading-cell {
  display: block;
  overflow: hidden;
  color: #344158;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.preview-cell {
  display: -webkit-box;
  overflow: hidden;
  color: #68758a;
  font-size: 12px;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.table-footer {
  min-height: 55px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 22px;
  color: #8e99aa;
  font-size: 11px;
  border-top: 1px solid #edf0f4;
}
.field-label {
  display: block;
  margin-bottom: 8px;
  color: #39465c;
  font-size: 13px;
  font-weight: 600;
}
.dialog-help {
  margin-top: 9px;
  color: #929cad;
  font-size: 12px;
}
.model-label {
  margin-top: 18px;
}
.model-select {
  width: 100%;
}
.model-select small {
  float: right;
  margin-left: 20px;
  color: #929cad;
}
.chunk-detail {
  min-height: 280px;
}
.detail-kicker {
  color: var(--blue);
  font:
    11px ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
  letter-spacing: 1.4px;
}
.chunk-detail h2 {
  margin-top: 7px;
  color: #1f2c45;
  font-size: 20px;
  font-weight: 680;
}
.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 13px;
}
.detail-meta span {
  padding: 4px 8px;
  color: #69768c;
  font:
    11px ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
  background: #f2f5f9;
  border-radius: 4px;
}
.chunk-detail pre {
  margin: 22px 0 0;
  padding: 20px;
  overflow: auto;
  color: #334159;
  font:
    14px/1.85 "Microsoft YaHei",
    sans-serif;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f7f9fc;
  border: 1px solid #e5eaf2;
  border-radius: 8px;
}
@media (max-width: 980px) {
  .admin-sidebar {
    width: 76px;
    flex-basis: 76px;
    align-items: center;
    padding-inline: 10px;
  }
  .admin-brand > span:last-child,
  .nav-caption,
  .nav-item span,
  .nav-item i,
  .sidebar-note div,
  .chat-entry span,
  .chat-entry svg:last-child {
    display: none;
  }
  .admin-brand {
    padding: 0;
  }
  .nav-item {
    width: 44px;
    justify-content: center;
    padding: 10px;
  }
  .sidebar-note {
    padding: 15px 0;
  }
  .chat-entry {
    width: 44px;
    justify-content: center;
    padding: 9px;
  }
}
@media (max-width: 720px) {
  .admin-shell {
    display: block;
  }
  .admin-sidebar {
    position: static;
    width: 100%;
    height: 58px;
    flex-direction: row;
    justify-content: space-between;
    padding: 10px 14px;
  }
  .brand-glyph {
    width: 32px;
    height: 32px;
    padding: 8px 7px;
  }
  .admin-sidebar nav {
    margin-left: auto;
  }
  .sidebar-note {
    display: none;
  }
  .chat-entry {
    margin-left: 8px;
  }
  .topbar {
    display: none;
  }
  .page-wrap {
    padding: 22px 14px 30px;
  }
  .page-heading {
    align-items: flex-start;
    flex-direction: column;
    margin-bottom: 20px;
  }
  .page-heading h1 {
    font-size: 24px;
  }
  .heading-actions {
    width: 100%;
    justify-content: flex-end;
  }
  .table-heading {
    align-items: stretch;
    flex-direction: column;
    gap: 13px;
  }
  .table-search {
    width: 100%;
  }
  .table-footer {
    padding-inline: 14px;
  }
}
.topbar-status.error {
  color: #b65d5d;
}
.topbar-status.error i {
  background: #cf6060;
  box-shadow: 0 0 0 3px #cf606014;
}
</style>
