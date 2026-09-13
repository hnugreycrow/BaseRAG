<script setup lang="ts">
import { computed, reactive, watch } from 'vue'

import type { EmbeddingModel } from '../../api'

const props = defineProps<{
  modelValue: boolean
  models: EmbeddingModel[]
  loading: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submit: [payload: { name: string; embeddingModelId: string }]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const form = reactive({ name: '', embeddingModelId: '' })

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    form.name = ''
    form.embeddingModelId =
      props.models.find((model) => model.defaultModel)?.id ?? props.models[0]?.id ?? ''
  },
)

function submit() {
  const name = form.name.trim()
  if (!name || !form.embeddingModelId) return
  emit('submit', { name, embeddingModelId: form.embeddingModelId })
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="新建知识库"
    width="min(480px, calc(100vw - 28px))"
    align-center
  >
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="名称" required>
        <el-input
          v-model="form.name"
          maxlength="200"
          show-word-limit
          placeholder="例如：产品使用手册"
          autofocus
          @keyup.enter="submit"
        />
      </el-form-item>
      <el-form-item label="向量模型" required>
        <el-select
          v-model="form.embeddingModelId"
          placeholder="选择用于检索的向量模型"
          style="width: 100%"
        >
          <el-option v-for="model in models" :key="model.id" :value="model.id">
            <div class="model-option">
              <span>{{ model.model }}</span>
              <small>{{ model.provider }} · {{ model.dimensions }} 维</small>
            </div>
          </el-option>
        </el-select>
        <p class="field-help">创建后向量模型不可更换，以保证分块向量一致。</p>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="loading"
        :disabled="!form.name.trim() || !form.embeddingModelId"
        @click="submit"
      >
        创建知识库
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.model-option {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.model-option small {
  color: var(--color-muted);
  font-family: var(--font-data);
  font-size: 10px;
}

.field-help {
  margin: 7px 0 0;
  color: var(--color-muted);
  font-size: 11px;
  line-height: 1.6;
}
</style>
