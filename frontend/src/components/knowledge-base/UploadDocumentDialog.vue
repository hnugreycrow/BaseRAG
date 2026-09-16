<script setup lang="ts">
import { UploadFilled } from '@element-plus/icons-vue'
import type { UploadFile, UploadFiles, UploadUserFile } from 'element-plus'
import { computed, ref, watch } from 'vue'

import type { DocumentBatchUploadResult } from '../../api'

const props = defineProps<{
  modelValue: boolean
  loading: boolean
  result: DocumentBatchUploadResult | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submit: [files: File[]]
}>()

const fileList = ref<UploadUserFile[]>([])
const submittedFiles = ref<File[]>([])
const fileError = ref('')
const selectedFiles = computed(() => fileList.value.flatMap((file) => (file.raw ? [file.raw] : [])))
const failures = computed(
  () =>
    props.result?.results.filter(
      (item) =>
        item.status === 'FAILED' &&
        fileList.value.some((file) => file.raw === submittedFiles.value[item.index]),
    ) ?? [],
)
const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    fileList.value = []
    submittedFiles.value = []
    fileError.value = ''
  },
)

watch(
  () => props.result,
  (result) => {
    if (!result) return
    const uploaded = new Set(
      result.results
        .filter((item) => item.status === 'UPLOADED')
        .map((item) => submittedFiles.value[item.index]),
    )
    fileList.value = fileList.value.filter((file) => !file.raw || !uploaded.has(file.raw))
  },
)

function handleChange(file: UploadFile, files: UploadFiles) {
  const raw = file.raw
  fileError.value = ''
  if (!raw) return
  if (!/\.(md|markdown)$/i.test(raw.name)) {
    fileList.value = files.filter((item) => item.uid !== file.uid)
    fileError.value = '请选择 .md 或 .markdown 格式的 Markdown 文件'
    return
  }
  if (raw.size > 5 * 1024 * 1024) {
    fileList.value = files.filter((item) => item.uid !== file.uid)
    fileError.value = '单个文件不能超过 5 MiB'
    return
  }
  fileList.value = files
}

function handleExceed() {
  fileError.value = '每次最多选择 10 个文件'
}

function submit() {
  if (props.loading || selectedFiles.value.length === 0) return
  submittedFiles.value = [...selectedFiles.value]
  emit('submit', selectedFiles.value)
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="上传文档"
    width="min(520px, calc(100vw - 28px))"
    align-center
    :close-on-click-modal="!loading"
    :close-on-press-escape="!loading"
    :show-close="!loading"
  >
    <el-upload
      v-model:file-list="fileList"
      drag
      action="#"
      accept=".md,.markdown,text/markdown"
      multiple
      :auto-upload="false"
      :disabled="loading"
      :limit="10"
      :on-change="handleChange"
      :on-exceed="handleExceed"
    >
      <el-icon class="upload-icon"><UploadFilled /></el-icon>
      <p class="upload-title">拖入文件，或点击选择</p>
      <p class="upload-help">支持最多 10 个 Markdown 文件，单个最大 5 MiB</p>
    </el-upload>
    <p v-if="fileError" class="file-error">{{ fileError }}</p>
    <ul v-if="failures.length" class="upload-failures">
      <li v-for="item in failures" :key="item.index">
        {{ item.fileName || `第 ${item.index + 1} 个文件` }}：{{ item.message }}
      </li>
    </ul>
    <p class="process-note">上传后文档不会自动处理，请在文档列表中点击“开始分块”。</p>
    <template #footer>
      <el-button :disabled="loading" @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="loading"
        :disabled="selectedFiles.length === 0"
        @click="submit"
      >
        上传文档
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.upload-icon {
  margin-bottom: 10px;
  color: var(--color-primary);
  font-size: 34px;
}

.upload-title {
  margin: 0;
  color: var(--color-ink);
  font-size: 14px;
  font-weight: 650;
}

.upload-help,
.process-note,
.file-error,
.upload-failures {
  font-size: 11px;
}

.upload-help {
  margin: 7px 0 0;
  color: var(--color-muted);
}

.process-note {
  margin: 12px 0 0;
  padding: 10px 12px;
  color: #647188;
  line-height: 1.6;
  background: #f5f7fb;
  border-radius: 8px;
}

.file-error,
.upload-failures {
  margin: 8px 0 0;
  color: var(--el-color-danger);
}

.upload-failures {
  padding-left: 18px;
  line-height: 1.6;
}
</style>
