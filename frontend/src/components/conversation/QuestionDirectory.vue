<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { getErrorMessage, listQuestions, type QuestionSummary } from '../../api'

const props = withDefaults(
  defineProps<{
    conversationId: string
    activeTurn?: number | null
    visibleQuestions?: QuestionSummary[]
  }>(),
  { visibleQuestions: () => [] },
)
const railItems = computed(() => {
  const index = props.visibleQuestions.findIndex((item) => item.turnIndex === props.activeTurn)
  const start = Math.max(0, Math.min(index - 9, props.visibleQuestions.length - 20))
  return props.visibleQuestions.slice(start, start + 20)
})
const displayItems = computed(() => {
  const merged = new Map(items.value.map((item) => [item.turnIndex, item]))
  props.visibleQuestions.forEach((item) => merged.set(item.turnIndex, item))
  return [...merged.values()].sort((a, b) => a.turnIndex - b.turnIndex)
})
const emit = defineEmits<{ jump: [turnIndex: number] }>()
const open = ref(false)
const items = ref<QuestionSummary[]>([])
const loading = ref(false)
const hasMore = ref(false)
const error = ref('')
const trigger = ref<HTMLButtonElement | null>(null)
const listElement = ref<HTMLElement | null>(null)
let sequence = 0

watch([open, () => props.activeTurn], async () => {
  if (!open.value) {
    return
  }
  await nextTick()
  listElement.value
    ?.querySelector<HTMLElement>('[aria-current="location"]')
    ?.scrollIntoView?.({ block: 'nearest' })
})

watch(
  () => props.conversationId,
  () => {
    sequence += 1
    open.value = false
    items.value = []
    loading.value = false
    error.value = ''
  },
)

async function load(reset = false) {
  if (loading.value) {
    return
  }
  const request = ++sequence
  const id = props.conversationId
  loading.value = true
  error.value = ''
  if (reset) {
    items.value = []
  }
  try {
    const page = await listQuestions(id, reset ? undefined : items.value.at(-1)?.turnIndex)
    if (request !== sequence || id !== props.conversationId) {
      return
    }
    const list = listElement.value
    const previousHeight = list?.scrollHeight ?? 0
    const previousTop = list?.scrollTop ?? 0
    items.value.push(...page.items)
    hasMore.value = page.hasMore
    await nextTick()
    if (request !== sequence) {
      return
    }
    if (!reset && list) {
      list.scrollTop = previousTop + list.scrollHeight - previousHeight
    } else {
      list
        ?.querySelector<HTMLElement>('[aria-current="location"]')
        ?.scrollIntoView?.({ block: 'nearest' })
    }
  } catch (cause) {
    if (request === sequence) {
      error.value = getErrorMessage(cause)
    }
  } finally {
    if (request === sequence) {
      loading.value = false
    }
  }
}

function reveal() {
  if (open.value) {
    return
  }
  open.value = true
  if (!items.value.length) {
    void load(true)
  }
}

function close() {
  open.value = false
  trigger.value?.focus()
}

function onFocusOut(event: FocusEvent) {
  if (!(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node)) {
    open.value = false
  }
}

function jump(turnIndex: number) {
  close()
  emit('jump', turnIndex)
}

function onScroll(event: Event) {
  const list = event.target as HTMLElement
  if (list.scrollTop < 40 && hasMore.value && !error.value) {
    void load()
  }
}
</script>

<template>
  <div
    class="question-directory"
    :class="{ 'is-open': open }"
    @mouseenter="reveal"
    @mouseleave="open = false"
    @keydown.esc.stop="close"
    @focusout="onFocusOut"
  >
    <button
      ref="trigger"
      type="button"
      class="directory-toggle"
      :aria-expanded="open"
      aria-controls="question-directory-panel"
      aria-label="提问目录"
      @click="reveal"
    >
      <span
        v-for="item in railItems.length
          ? railItems
          : [{ turnIndex: 0 }, { turnIndex: 1 }, { turnIndex: 2 }, { turnIndex: 3 }]"
        :key="item.turnIndex"
        class="rail-tick"
        :class="{ active: item.turnIndex === activeTurn }"
        aria-hidden="true"
      ></span>
    </button>
    <section
      v-if="open"
      id="question-directory-panel"
      class="directory-panel"
      aria-label="提问目录"
    >
      <div ref="listElement" class="directory-list" @scroll.passive="onScroll">
        <p v-if="error" role="alert">{{ error }}</p>
        <button
          v-if="hasMore || loading || error"
          type="button"
          class="directory-more"
          :disabled="loading"
          @click="load()"
        >
          {{ loading ? '加载中…' : error ? '重新加载' : '加载更早提问' }}
        </button>
        <button
          v-for="item in displayItems"
          :key="item.id"
          type="button"
          class="question-link"
          :class="{ active: item.turnIndex === activeTurn }"
          :aria-current="item.turnIndex === activeTurn ? 'location' : undefined"
          :title="item.preview"
          @click="jump(item.turnIndex)"
        >
          <span class="question-preview">{{ item.preview }}</span
          ><span class="question-tick" aria-hidden="true"></span>
        </button>
        <p v-if="!displayItems.length && !loading && !error">暂无提问</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.question-directory {
  position: fixed;
  right: 16px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 25;
  padding-left: 16px;
}
.directory-toggle {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 28px;
  min-height: 48px;
  max-height: 48dvh;
  overflow: hidden;
  padding: 14px 8px;
  border: 0;
  background: transparent;
  cursor: pointer;
}
.rail-tick,
.question-tick {
  display: block;
  width: 9px;
  height: 3px;
  flex: 0 0 auto;
  border-radius: 2px;
  background: #d2d3d6;
}
.rail-tick.active {
  width: 13px;
  background: #62656c;
}
.directory-panel {
  position: absolute;
  top: 50%;
  right: 0;
  transform: translateY(-50%);
  width: min(272px, calc(100vw - 32px));
  padding: 10px;
  border: 1px solid #eeeeef;
  border-radius: 16px;
  background: white;
  box-shadow: 0 8px 28px rgb(25 30 45 / 8%);
}
.directory-list {
  max-height: min(288px, 48dvh);
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: #e3e4e7 transparent;
}
.directory-panel button {
  font: inherit;
  cursor: pointer;
  border: 0;
  background: transparent;
}
.question-link {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 36px;
  padding: 7px 10px;
  text-align: left;
  border-radius: 7px;
  color: #777d88;
}
.question-preview {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  line-height: 22px;
  font-size: 13px;
}
.question-link:hover {
  background: #f5f6f8;
  color: var(--chat-ink);
}
.question-link.active {
  background: #f1f4ff;
  color: var(--chat-blue);
  font-weight: 500;
}
.question-link.active .question-tick {
  width: 12px;
  background: var(--chat-blue);
}
.question-tick {
  margin-right: 2px;
}
.directory-more {
  width: 100%;
  padding: 8px 0;
  color: #898c96;
  font-size: 12px !important;
}
.directory-list p {
  font-size: 13px;
  color: #777d88;
}
button:focus-visible {
  outline: 2px solid var(--chat-blue);
  outline-offset: 2px;
  border-radius: 4px;
}
@media (max-width: 600px) {
  .question-directory {
    right: 4px;
  }
  .directory-panel {
    width: min(252px, calc(100vw - 24px));
    padding: 8px;
    border-radius: 14px;
  }
}
</style>
