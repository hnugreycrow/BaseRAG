<script setup lang="ts">
import { UploadFilled } from '@element-plus/icons-vue'
import type { UploadFile, UploadFiles, UploadUserFile } from 'element-plus'
import { computed, ref, watch } from 'vue'

const props = defineProps<{
  modelValue: boolean
  loading: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submit: [file: File]
}>()

const fileList = ref<UploadUserFile[]>([])
const selectedFile = ref<File | null>(null)
const fileError = ref('')
const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    fileList.value = []
    selectedFile.value = null
    fileError.value = ''
  },
)

function handleChange(file: UploadFile, files: UploadFiles) {
  const raw = file.raw
  fileError.value = ''

  if (!raw) return
  if (!raw.name.toLowerCase().endsWith('.md')) {
    selectedFile.value = null
    fileList.value = []
    fileError.value = '请选择 .md 格式的 Markdown 文件'
    return
  }
  if (raw.size > 5 * 1024 * 1024) {
    selectedFile.value = null
    fileList.value = []
    fileError.value = '文件不能超过 5 MB'
    return
  }

  selectedFile.value = raw
  fileList.value = files.slice(-1)
}

function handleRemove() {
  selectedFile.value = null
}

function submit() {
  if (selectedFile.value) emit('submit', selectedFile.value)
}
</script>

<template>
  <el-dialog v-model="visible" title="上传文档" width="min(520px, calc(100vw - 28px))" align-center>
    <el-upload
      v-model:file-list="fileList"
      drag
      action="#"
      accept=".md,text/markdown"
      :auto-upload="false"
      :limit="1"
      :on-change="handleChange"
      :on-remove="handleRemove"
    >
      <el-icon class="upload-icon"><UploadFilled /></el-icon>
      <p class="upload-title">拖入文件，或点击选择</p>
      <p class="upload-help">支持单个 Markdown 文件，最大 5 MB</p>
    </el-upload>
    <p v-if="fileError" class="file-error">{{ fileError }}</p>
    <p class="process-note">上传后文档不会自动处理，请在文档列表中点击“开始分块”。</p>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="loading" :disabled="!selectedFile" @click="submit">
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
.file-error {
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

.file-error {
  margin: 8px 0 0;
  color: var(--el-color-danger);
}
</style>
