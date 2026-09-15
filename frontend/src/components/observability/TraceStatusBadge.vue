<script setup lang="ts">
import { computed } from 'vue'

import type { RagRunStatus, RagStageStatus } from '../../api'
import { RUN_STATUS_LABELS, STAGE_STATUS_LABELS } from './tracePresentation'

const props = defineProps<{
  status: RagRunStatus | RagStageStatus
}>()

const label = computed(() => {
  if (props.status in RUN_STATUS_LABELS) return RUN_STATUS_LABELS[props.status as RagRunStatus]
  return STAGE_STATUS_LABELS[props.status as RagStageStatus]
})
</script>

<template>
  <span class="status-badge" :class="`status-${status.toLowerCase()}`">
    <i aria-hidden="true"></i>{{ label }}
  </span>
</template>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #607087;
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
}

.status-badge i {
  width: 7px;
  height: 7px;
  background: #9aa6b8;
  border-radius: 50%;
}

.status-completed,
.status-success {
  color: var(--color-success);
}

.status-completed i,
.status-success i {
  background: var(--color-success);
}

.status-running {
  color: var(--color-primary);
}

.status-running i {
  background: var(--color-primary);
  box-shadow: 0 0 0 3px rgb(66 99 235 / 12%);
}

.status-degraded {
  color: #b47719;
}

.status-degraded i {
  background: #d49a3a;
}

.status-failed {
  color: #b65349;
}

.status-failed i {
  background: #d96c5f;
}

.status-cancelled,
.status-interrupted,
.status-skipped {
  color: #748096;
}
</style>
