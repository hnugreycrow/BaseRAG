<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import type { Clarification } from '../../api'

const props = defineProps<{
  clarification: Clarification
  activeId?: string
  busy: boolean
  supplement?: string
}>()
const emit = defineEmits<{
  confirm: [answer: { option: Clarification['options'][number] | null; text: string }]
  cancel: []
}>()
const formId = useId()
const selectedId = ref<string | null>(null)
const supplement = computed(() => props.supplement ?? '')
const available = computed(
  () => props.clarification.status === 'PENDING' && props.activeId === props.clarification.id,
)
const selected = computed(
  () => props.clarification.options.find((option) => option.nodeId === selectedId.value) ?? null,
)
const canSubmit = computed(
  () => available.value && !props.busy && Boolean(selected.value || supplement.value.trim()),
)
const statusText = computed(() => {
  if (props.clarification.status === 'CANCELLED') {
    return '已取消澄清，可以提出新问题。'
  }
  if (props.clarification.status === 'RESUMING') {
    return '已收到补充，正在继续处理。'
  }
  return '此项澄清已处理。'
})
watch(
  () => props.clarification.id,
  () => {
    selectedId.value = null
  },
)
defineExpose({ confirm })
function confirm() {
  if (!canSubmit.value) {
    return
  }
  emit('confirm', { option: selected.value, text: supplement.value.trim() })
}
</script>

<template>
  <form
    class="clarification-choices"
    :class="{ 'is-history': !available }"
    :aria-labelledby="`${formId}-title`"
    :aria-busy="busy"
    @submit.prevent="confirm"
  >
    <div class="clarification-header">
      <span class="clarification-eyebrow">需要你确认</span>
      <span
        v-if="clarification.totalSteps && clarification.totalSteps > 1"
        class="clarification-progress"
      >
        第 {{ clarification.currentStep || 1 }} / {{ clarification.totalSteps }} 项
      </span>
    </div>
    <h3 :id="`${formId}-title`">{{ clarification.question || '你想了解哪一项？' }}</h3>
    <p :id="`${formId}-hint`" class="clarification-hint">
      {{ available ? '选择一个方向，也可在下方输入框补充说明。' : statusText }}
    </p>
    <fieldset
      class="clarification-options"
      :disabled="!available || busy"
      :aria-describedby="`${formId}-hint`"
    >
      <legend class="sr-only">选择知识库意图</legend>
      <label
        v-for="(option, index) in clarification.options"
        :key="option.nodeId"
        class="clarification-option"
        :class="{ 'is-selected': selectedId === option.nodeId }"
      >
        <input
          v-model="selectedId"
          type="radio"
          :name="`${formId}-intent`"
          :value="option.nodeId"
          :aria-describedby="option.description ? `${formId}-description-${index}` : undefined"
        />
        <span class="option-copy">
          <span class="option-label">{{ option.label }}</span>
          <span
            v-if="option.description"
            :id="`${formId}-description-${index}`"
            class="option-description"
            >{{ option.description }}</span
          >
        </span>
        <span class="option-number" aria-hidden="true">{{ index + 1 }}</span>
      </label>
    </fieldset>
    <template v-if="available">
      <div class="clarification-actions">
        <button type="button" class="clarification-cancel" :disabled="busy" @click="emit('cancel')">
          取消澄清
        </button>
        <button type="submit" class="clarification-confirm" :disabled="!canSubmit">
          {{ busy ? '正在处理…' : '确认并继续' }}
        </button>
      </div>
    </template>
  </form>
</template>

<style scoped>
.clarification-choices {
  margin: 0 0 12px;
  padding: 12px;
  max-height: 45dvh;
  overflow-y: auto;
  border: 1px solid var(--color-line);
  border-radius: 16px;
  background: var(--color-surface);
  color: var(--color-text);
}
.clarification-header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin-bottom: 6px;
  font-size: 12px;
}
.clarification-eyebrow {
  font-weight: 600;
  color: var(--color-primary);
}
.clarification-progress {
  color: var(--color-text);
  white-space: nowrap;
}
h3 {
  margin: 0;
  color: var(--color-ink);
  font-size: 14px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.clarification-hint {
  margin: 4px 0 10px;
  font-size: 13px;
  line-height: 1.6;
}
.clarification-options {
  display: grid;
  gap: 6px;
  min-width: 0;
  padding: 0;
  margin: 0;
  border: 0;
}
.clarification-option {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 9px 10px;
  min-height: 44px;
  border: 1px solid var(--color-line);
  border-radius: 10px;
  cursor: pointer;
}
.clarification-option.is-selected {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}
.clarification-option:focus-within {
  outline: 2px solid var(--color-primary);
  outline-offset: 3px;
}
input[type='radio'] {
  width: 16px;
  height: 16px;
  margin: 3px 0 0;
  flex-shrink: 0;
  accent-color: var(--color-primary);
}
.option-copy {
  display: grid;
  gap: 2px;
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}
.option-label {
  color: var(--color-ink);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
}
.option-description {
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.option-number {
  font-size: 12px;
  line-height: 22px;
  color: var(--color-text);
}
.clarification-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 8px;
}
button {
  min-height: 36px;
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.clarification-confirm {
  color: var(--color-surface);
  background: var(--color-primary);
}
.clarification-cancel {
  color: var(--color-text);
}
button:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 3px;
}
button:disabled {
  opacity: 0.55;
  cursor: default;
}
fieldset:disabled .clarification-option {
  cursor: default;
}
.is-history .clarification-eyebrow {
  color: var(--color-text);
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
  border: 0;
}
@media (max-width: 600px) {
  .clarification-choices {
    padding: 12px;
  }
  .clarification-option {
    padding: 8px 10px;
    gap: 10px;
  }
  button {
    min-height: 44px;
  }
  .clarification-actions {
    justify-content: space-between;
  }
}
</style>
