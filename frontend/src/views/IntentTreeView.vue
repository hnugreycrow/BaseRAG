<script setup lang="ts">
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { getErrorMessage } from '../api/http'
import { listKnowledgeBases, type KnowledgeBase } from '../api/knowledgeBase'
import {
  createIntentNode,
  deleteIntentNode,
  listIntentNodes,
  listIntentTools,
  updateIntentNode,
  type IntentKind,
  type IntentNode,
  type IntentNodeInput,
  type IntentToolOption,
} from '../api/intentTree'

interface TreeItem extends IntentNode {
  children: TreeItem[]
}

type NodeType = 'GROUP' | IntentKind

const typeOptions: { value: NodeType; label: string; hint: string }[] = [
  { value: 'GROUP', label: '分类目录', hint: '继续添加下级节点' },
  { value: 'KB', label: '知识库检索', hint: '搜索指定公共知识库' },
  { value: 'MCP', label: '只读工具', hint: '调用已注册工具' },
  { value: 'SYSTEM', label: '系统直答', hint: '直接回复用户' },
]

const nodes = ref<IntentNode[]>([])
const knowledgeBases = ref<KnowledgeBase[]>([])
const tools = ref<IntentToolOption[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const dialogOpen = ref(false)
const editingId = ref<string | null>(null)
const nodeType = ref<NodeType | null>(null)
const examplesText = ref('')
const examplesError = ref('')
const typeError = ref('')
const formRef = ref<FormInstance>()
const form = reactive<IntentNodeInput>({
  parentId: null,
  name: '',
  description: '',
  examples: [],
  kind: null,
  toolName: null,
  knowledgeBaseIds: [],
  enabled: true,
  sortOrder: 0,
})

const rules = computed<FormRules>(() => ({
  name: [{ required: true, message: '请输入节点名称', trigger: 'blur' }],
  description: form.kind
    ? [{ required: true, message: '请说明这个节点负责哪些问题', trigger: 'blur' }]
    : [],
  knowledgeBaseIds:
    form.kind === 'KB'
      ? [
          {
            type: 'array',
            required: true,
            min: 1,
            message: '至少选择一个公共知识库',
            trigger: 'change',
          },
        ]
      : [],
  toolName:
    form.kind === 'MCP'
      ? [{ required: true, message: '请选择一个只读工具', trigger: 'change' }]
      : [],
}))

const nodesById = computed(() => new Map(nodes.value.map((node) => [node.id, node])))

function depthOf(node: IntentNode): number {
  let depth = 1
  let current = node
  while (current.parentId) {
    const parent = nodesById.value.get(current.parentId)
    if (!parent) break
    depth++
    current = parent
  }
  return depth
}

function order(items: TreeItem[]) {
  items.sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name))
  items.forEach((item) => order(item.children))
}

const tree = computed(() => {
  const byId = new Map(
    nodes.value.map((node) => [node.id, { ...node, children: [] as TreeItem[] }]),
  )
  const roots: TreeItem[] = []
  for (const item of byId.values()) {
    const parent = item.parentId ? byId.get(item.parentId) : null
    if (parent) parent.children.push(item)
    else roots.push(item)
  }
  order(roots)
  return roots
})

const activeLeafCount = computed(
  () =>
    nodes.value.filter((node) => {
      if (!node.kind || nodes.value.some((child) => child.parentId === node.id)) return false
      let current: IntentNode | undefined = node
      while (current) {
        if (!current.enabled) return false
        current = current.parentId ? nodesById.value.get(current.parentId) : undefined
      }
      return true
    }).length,
)

const editingHasChildren = computed(
  () => !!editingId.value && nodes.value.some((node) => node.parentId === editingId.value),
)

const parentOptions = computed(() =>
  nodes.value.filter((node) => {
    if (node.kind || node.id === editingId.value || depthOf(node) >= 3) return false
    let current: IntentNode | undefined = node
    while (current) {
      if (current.id === editingId.value) return false
      current = current.parentId ? nodesById.value.get(current.parentId) : undefined
    }
    return true
  }),
)

function typeLabel(kind: IntentKind | null) {
  return typeOptions.find((option) => option.value === (kind ?? 'GROUP'))?.label ?? '分类目录'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [loadedNodes, loadedTools] = await Promise.all([listIntentNodes(), listIntentTools()])
    nodes.value = loadedNodes
    tools.value = loadedTools
    const allKnowledgeBases: KnowledgeBase[] = []
    let page = 1
    while (true) {
      const result = await listKnowledgeBases(page, 100)
      allKnowledgeBases.push(...result.items)
      if (allKnowledgeBases.length >= result.total || result.items.length === 0) break
      page++
    }
    knowledgeBases.value = allKnowledgeBases
  } catch (cause) {
    error.value = getErrorMessage(cause)
  } finally {
    loading.value = false
  }
}

function clearValidation() {
  examplesError.value = ''
  typeError.value = ''
  nextTick(() => formRef.value?.clearValidate())
}

function nextSortOrder(parentId: string | null) {
  return (
    Math.max(
      -1,
      ...nodes.value.filter((node) => node.parentId === parentId).map((node) => node.sortOrder),
    ) + 1
  )
}

function openCreate(parentId: string | null = null) {
  editingId.value = null
  nodeType.value = null
  Object.assign(form, {
    parentId,
    name: '',
    description: '',
    examples: [],
    kind: null,
    toolName: null,
    knowledgeBaseIds: [],
    enabled: true,
    sortOrder: nextSortOrder(parentId),
  })
  examplesText.value = ''
  dialogOpen.value = true
  clearValidation()
}

function openEdit(node: IntentNode) {
  editingId.value = node.id
  nodeType.value = node.kind ?? 'GROUP'
  Object.assign(form, {
    parentId: node.parentId,
    name: node.name,
    description: node.description,
    examples: [...node.examples],
    kind: node.kind,
    toolName: node.toolName,
    knowledgeBaseIds: [...node.knowledgeBaseIds],
    enabled: node.enabled,
    sortOrder: node.sortOrder,
  })
  examplesText.value = node.examples.join('\n')
  dialogOpen.value = true
  clearValidation()
}

function chooseType(value: NodeType) {
  if (editingHasChildren.value && value !== 'GROUP') return
  nodeType.value = value
  typeError.value = ''
  form.kind = value === 'GROUP' ? null : value
  form.toolName = null
  form.knowledgeBaseIds = []
  clearValidation()
}

async function save() {
  if (saving.value) return
  typeError.value = nodeType.value ? '' : '请选择节点用途'
  if (typeError.value) return
  form.name = form.name.trim()
  form.description = form.description.trim()
  form.examples = examplesText.value
    .split('\n')
    .map((item) => item.trim())
    .filter(Boolean)
  examplesError.value =
    form.examples.length > 4
      ? '最多填写 4 条示例问题'
      : form.examples.some((item) => item.length > 120)
        ? '每条示例问题不能超过 120 个字'
        : ''
  if (examplesError.value) return
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const input: IntentNodeInput = {
    ...form,
    parentId: form.parentId || null,
    examples: [...form.examples],
    knowledgeBaseIds: [...form.knowledgeBaseIds],
  }
  saving.value = true
  try {
    if (editingId.value) await updateIntentNode(editingId.value, input)
    else await createIntentNode(input)
    dialogOpen.value = false
    await load()
    ElMessage.success('意图节点已保存')
  } catch (cause) {
    ElMessage.error(getErrorMessage(cause))
  } finally {
    saving.value = false
  }
}

async function remove(node: IntentNode) {
  try {
    await ElMessageBox.confirm('确定删除“' + node.name + '”吗？', '删除意图节点', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await deleteIntentNode(node.id)
    await load()
    ElMessage.success('意图节点已删除')
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(getErrorMessage(cause))
  }
}

onMounted(load)
</script>

<template>
  <main class="intent-page">
    <header class="intent-header">
      <div>
        <h1>全局意图树</h1>
      </div>
      <el-button type="primary" @click="openCreate()">新增节点</el-button>
    </header>

    <div class="intent-meta">
      <span class="meta-count"><span class="meta-dot" />启用叶子 {{ activeLeafCount }} / 32</span>
      <span class="meta-divider" aria-hidden="true" />
      <span>最多三级</span>
    </div>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <section class="intent-tree-panel admin-card" :aria-busy="loading">
      <div v-if="loading" class="intent-loading">正在加载意图节点…</div>
      <div v-else-if="tree.length === 0" class="intent-empty">
        <div class="empty-path" aria-hidden="true">
          <span class="path-point path-root" />
          <span class="path-line" />
          <span class="path-point" />
        </div>
        <div>
          <h2>还没有意图节点</h2>
          <p>可以先创建分类目录，也可以直接创建一个连接知识库、只读工具或系统直答的叶子节点。</p>
          <p class="empty-note">未配置可用叶子时，会话问答将检索全部公共知识库。</p>
          <el-button type="primary" plain @click="openCreate()">创建第一个节点</el-button>
        </div>
      </div>
      <el-tree
        v-else-if="tree.length > 0"
        class="intent-tree"
        :data="tree"
        node-key="id"
        default-expand-all
        :expand-on-click-node="false"
      >
        <template #default="slotProps">
          <div v-if="slotProps?.data" class="intent-node">
            <div class="intent-node-main">
              <div class="intent-node-heading">
                <strong>{{ slotProps.data.name }}</strong>
                <span class="intent-kind" :class="'kind-' + (slotProps.data.kind || 'GROUP')">
                  {{ typeLabel(slotProps.data.kind) }}
                </span>
                <span v-if="!slotProps.data.enabled" class="inactive-label">已停用</span>
              </div>
              <span v-if="slotProps.data.description" class="intent-description">{{
                slotProps.data.description
              }}</span>
            </div>
            <div class="intent-actions">
              <el-button
                v-if="!slotProps.data.kind && depthOf(slotProps.data) < 3"
                link
                type="primary"
                @click.stop="openCreate(slotProps.data.id)"
                >添加下级</el-button
              >
              <el-button link @click.stop="openEdit(slotProps.data)">编辑</el-button>
              <el-button link type="danger" @click.stop="remove(slotProps.data)">删除</el-button>
            </div>
          </div>
        </template>
      </el-tree>
    </section>

    <el-dialog
      v-model="dialogOpen"
      class="intent-editor"
      :title="editingId ? '编辑意图节点' : '新增意图节点'"
      width="640px"
      top="5vh"
      :close-on-click-modal="!saving"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        :validate-on-rule-change="false"
        label-position="top"
        @submit.prevent="save"
      >
        <div class="form-grid">
          <el-form-item label="节点名称" prop="name" required>
            <el-input v-model="form.name" maxlength="100" placeholder="例如：人事制度" />
          </el-form-item>
          <el-form-item label="所属上级">
            <el-select
              v-model="form.parentId"
              clearable
              placeholder="无，作为根节点"
              @clear="form.parentId = null"
            >
              <el-option
                v-for="node in parentOptions"
                :key="node.id"
                :label="node.name"
                :value="node.id"
              />
            </el-select>
          </el-form-item>
        </div>

        <el-form-item label="节点用途" required>
          <div
            class="type-grid"
            role="radiogroup"
            aria-label="节点用途"
            :aria-invalid="!!typeError"
          >
            <button
              v-for="option in typeOptions"
              :key="option.value"
              type="button"
              class="type-option"
              :class="{ selected: nodeType === option.value }"
              :disabled="editingHasChildren && option.value !== 'GROUP'"
              role="radio"
              :aria-checked="nodeType === option.value"
              @click="chooseType(option.value)"
            >
              <span class="type-option-label">{{ option.label }}</span>
              <span class="type-option-hint">{{ option.hint }}</span>
            </button>
          </div>
          <p v-if="typeError" class="field-error" role="alert">{{ typeError }}</p>
          <p v-if="editingHasChildren" class="field-note">这个节点已有下级，需保留为分类目录。</p>
        </el-form-item>

        <el-form-item label="描述" prop="description" :required="form.kind !== null">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            maxlength="300"
            show-word-limit
            :placeholder="
              form.kind
                ? '说明哪些问题应归入这个节点，帮助模型准确分类'
                : '可选：说明这个分类目录包含什么'
            "
          />
        </el-form-item>

        <el-form-item v-if="form.kind === 'KB'" label="公共知识库" prop="knowledgeBaseIds" required>
          <el-select
            v-model="form.knowledgeBaseIds"
            multiple
            filterable
            placeholder="至少选择一个知识库"
          >
            <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.kind === 'MCP'" label="只读工具" prop="toolName" required>
          <el-select v-model="form.toolName" placeholder="选择已注册的只读工具">
            <el-option
              v-for="tool in tools"
              :key="tool.name"
              :label="tool.name"
              :value="tool.name"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="示例问题">
          <el-input
            v-model="examplesText"
            type="textarea"
            :rows="2"
            placeholder="可选，每行一条，例如：年假怎么申请？"
            :class="{ 'field-invalid': examplesError }"
            @input="examplesError = ''"
          />
          <p v-if="examplesError" class="field-error" role="alert">{{ examplesError }}</p>
          <p v-else class="field-note">最多 4 条，每条不超过 120 个字。</p>
        </el-form-item>

        <details class="advanced-settings">
          <summary>显示与生效设置</summary>
          <div class="advanced-content">
            <div class="advanced-item">
              <div>
                <strong>同级显示顺序</strong>
                <p>数字越小，在同一层越靠前。</p>
              </div>
              <el-input-number v-model="form.sortOrder" :min="0" :step="1" />
            </div>
            <div class="advanced-item">
              <div>
                <strong>参与问答分类</strong>
                <p>关闭后，此节点及其下级不会参与分类。</p>
              </div>
              <el-switch v-model="form.enabled" aria-label="参与问答分类" />
            </div>
          </div>
        </details>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">
          {{ editingId ? '保存修改' : '创建节点' }}
        </el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.intent-page {
  margin: 0 auto;
}
.intent-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}
.eyebrow {
  margin: 0 0 8px;
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
}
.intent-header h1 {
  margin: 0;
  color: var(--color-ink);
  font-size: 25px;
  line-height: 1.25;
}
.page-description {
  margin: 8px 0 0;
  color: var(--color-muted);
  font-size: 14px;
  line-height: 1.6;
}
.intent-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 22px 0 12px;
  color: var(--color-muted);
  font-size: 13px;
}
.meta-count {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--color-text);
  font-weight: 600;
}
.meta-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--color-success);
}
.meta-divider {
  width: 1px;
  height: 14px;
  background: var(--color-line);
}
.meta-note {
  margin-left: auto;
}
.intent-tree-panel {
  min-height: 170px;
  overflow: hidden;
}
.intent-loading {
  padding: var(--card-padding);
  color: var(--color-muted);
  font-size: 14px;
}
.intent-empty {
  display: flex;
  align-items: flex-start;
  gap: 22px;
  max-width: 690px;
  padding: var(--card-padding);
}
.empty-path {
  display: flex;
  flex: 0 0 20px;
  flex-direction: column;
  align-items: center;
  padding-top: 6px;
}
.path-point {
  width: 10px;
  height: 10px;
  border: 2px solid #9eafea;
  border-radius: 50%;
  background: white;
}
.path-root {
  border-color: var(--color-primary);
  background: var(--color-primary);
}
.path-line {
  width: 1px;
  height: 52px;
  background: #cbd5f4;
}
.intent-empty h2 {
  margin: 0 0 6px;
  color: var(--color-ink);
  font-size: 18px;
}
.intent-empty p {
  margin: 0 0 10px;
  color: var(--color-text);
  font-size: 14px;
  line-height: 1.65;
}
.intent-empty .empty-note {
  color: var(--color-muted);
  font-size: 12px;
}
.intent-empty .el-button {
  margin-top: 5px;
}
.intent-tree {
  padding: var(--card-padding);
}
.intent-tree :deep(.el-tree-node__content) {
  min-height: 62px;
  height: auto;
  padding: 6px 8px;
  border-radius: 8px;
}
.intent-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-width: 0;
  gap: 16px;
}
.intent-node-main {
  min-width: 0;
}
.intent-node-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.intent-node-heading strong {
  overflow: hidden;
  color: var(--color-ink);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.intent-kind,
.inactive-label {
  flex: none;
  padding: 3px 7px;
  border-radius: 5px;
  font-size: 11px;
  line-height: 1.3;
}
.intent-kind {
  color: #4f5d78;
  background: #eef2f7;
}
.kind-KB {
  color: #176b55;
  background: #e5f5ee;
}
.kind-MCP {
  color: #925d19;
  background: #fff3dc;
}
.kind-SYSTEM {
  color: #3f59b5;
  background: #edf1ff;
}
.inactive-label {
  color: #7b879e;
  background: #f1f3f7;
}
.intent-description {
  display: block;
  overflow: hidden;
  max-width: 650px;
  margin-top: 3px;
  color: var(--color-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.intent-actions {
  display: flex;
  flex: none;
  gap: 4px;
}
.intent-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.form-grid .el-form-item {
  min-width: 0;
}
.type-grid {
  display: grid;
  width: 100%;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.type-option {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--color-line);
  border-radius: 8px;
  text-align: left;
  transition:
    border-color 0.15s,
    background-color 0.15s;
}
.type-option:hover:not(:disabled) {
  border-color: #aab9ee;
}
.type-option.selected {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}
.type-option:disabled {
  opacity: 0.48;
  cursor: not-allowed;
}
.type-option-label {
  color: var(--color-ink);
  font-size: 13px;
  font-weight: 600;
}
.type-option-hint {
  overflow: hidden;
  max-width: 100%;
  margin-top: 3px;
  color: var(--color-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.field-note,
.field-error {
  width: 100%;
  margin: 5px 0 0;
  font-size: 12px;
  line-height: 1.5;
}
.field-note {
  color: var(--color-muted);
}
.field-error {
  color: var(--el-color-danger);
}
.field-invalid :deep(.el-textarea__inner) {
  border-color: var(--el-color-danger);
}
.advanced-settings {
  margin-top: 2px;
  border-top: 1px solid var(--color-line);
  color: var(--color-text);
}
.advanced-settings summary {
  padding: 12px 0 4px;
  color: var(--color-muted);
  font-size: 13px;
  cursor: pointer;
}
.advanced-content {
  padding-top: 4px;
}
.advanced-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 10px 0;
}
.advanced-item + .advanced-item {
  border-top: 1px solid var(--color-line);
}
.advanced-item strong {
  color: var(--color-ink);
  font-size: 13px;
  font-weight: 600;
}
.advanced-item p {
  margin: 3px 0 0;
  color: var(--color-muted);
  font-size: 12px;
}
.advanced-item :deep(.el-input-number) {
  width: 118px;
}
@media (max-width: 640px) {
  .intent-header {
    align-items: flex-start;
  }
  .intent-header h1 {
    font-size: 23px;
  }
  .intent-header .el-button {
    flex: none;
  }
  .meta-note {
    display: none;
  }
  .intent-loading {
    padding: var(--card-padding);
    color: var(--color-muted);
    font-size: 14px;
  }
  .intent-empty {
    padding: var(--card-padding);
  }
  .intent-node {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }
  .intent-actions {
    gap: 8px;
  }
  .form-grid {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
</style>
