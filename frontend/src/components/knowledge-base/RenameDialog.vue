<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = defineProps<{
  modelValue: boolean
  title: string
  currentName: string
  loading: boolean
  maxLength: number
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submit: [name: string]
}>()

const name = ref('')
const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

watch(
  () => props.modelValue,
  (open) => {
    if (open) name.value = props.currentName
  },
)

function submit() {
  const nextName = name.value.trim()
  if (!nextName || nextName === props.currentName) return
  emit('submit', nextName)
}
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="min(440px, calc(100vw - 28px))" align-center>
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="名称" required>
        <el-input
          v-model="name"
          :maxlength="maxLength"
          show-word-limit
          autofocus
          @keyup.enter="submit"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="loading"
        :disabled="!name.trim() || name.trim() === currentName"
        @click="submit"
      >
        保存名称
      </el-button>
    </template>
  </el-dialog>
</template>
