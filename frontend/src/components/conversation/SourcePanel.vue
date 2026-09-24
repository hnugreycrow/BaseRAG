<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { AnswerSource, AssistantMessage } from '../../api'
import MarkdownContent from '../common/MarkdownContent.vue'
import { markdownSummary } from '../common/markdown'

const props = defineProps<{
  message: AssistantMessage | null
  highlighted: string | null
  conversationId?: string
  locateRequest?: number
}>()
const open = defineModel<boolean>({ required: true })
// sources 是完整提示词证据快照；来源抽屉只展示回答真正使用的引用。
const citedSources = computed(() => {
  const cited = new Set(props.message?.citations ?? [])
  return (props.message?.sources ?? []).filter((source) => cited.has(source.citationId))
})
const selectedCitationId = ref<string | null>(null)
const previewOpen = ref(false)
const activeHighlight = ref<string | null>(null)
let highlightTimer: ReturnType<typeof setTimeout> | undefined
const selectedSource = computed(
  () => citedSources.value.find((source) => source.citationId === selectedCitationId.value) ?? null,
)

function sourceSummary(content: string) {
  return markdownSummary(content)
}

function originalFileUrl(source: AnswerSource) {
  const base = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')
  return `${base}/conversations/${encodeURIComponent(props.conversationId || '')}/messages/${encodeURIComponent(props.message?.id || '')}/sources/${encodeURIComponent(source.citationId)}/content`
}

function showPreview(source: AnswerSource) {
  selectedCitationId.value = source.citationId
  previewOpen.value = true
}

function clearPreview() {
  previewOpen.value = false
  selectedCitationId.value = null
}

watch(() => props.message?.id, clearPreview)
watch(open, (value) => {
  if (!value) clearPreview()
})

watch(previewOpen, (value) => {
  if (!value) selectedCitationId.value = null
})

const wide = ref(window.innerWidth >= 1180)
function resize() {
  wide.value = window.innerWidth >= 1180
}
window.addEventListener('resize', resize)
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  clearTimeout(highlightTimer)
})
async function locate() {
  clearTimeout(highlightTimer)
  activeHighlight.value = null
  await nextTick()
  if (!open.value || !props.highlighted || !props.message) {
    return
  }
  activeHighlight.value = props.highlighted
  document
    .getElementById('source-' + props.message.id + '-' + props.highlighted)
    ?.scrollIntoView({ block: 'nearest', behavior: 'auto' })
  highlightTimer = setTimeout(() => {
    activeHighlight.value = null
  }, 1600)
}
watch(() => [props.highlighted, props.message?.id, props.locateRequest, open.value], locate, {
  immediate: true,
})
</script>
<template>
  <div class="source-space" :class="{ expanded: open && wide }">
    <el-drawer
      v-model="open"
      title="引用来源"
      size="min(380px, 100vw)"
      :modal="!wide"
      :modal-penetrable="wide"
      :lock-scroll="!wide"
      @opened="locate"
    >
      <p class="source-count">{{ citedSources.length }} 个来源</p>
      <button
        v-for="source in citedSources"
        :id="'source-' + message?.id + '-' + source.citationId"
        :key="source.citationId"
        type="button"
        class="source-card"
        :class="{ highlighted: activeHighlight === source.citationId }"
        :aria-label="'预览来源 ' + source.citationId + '：' + source.documentName"
        @click="showPreview(source)"
      >
        <span class="source-title">
          <span class="citation">{{ source.citationId }}</span>
          <strong>{{ source.documentName }}</strong>
        </span>
        <span class="source-meta">
          {{ source.knowledgeBaseName
          }}<template v-if="source.primaryLocation.heading">
            · {{ source.primaryLocation.heading }}</template
          >
        </span>
        <span class="source-range">
          {{ source.primaryLocation.range.label
          }}<template v-if="source.locations.length > 1">
            · 共 {{ source.locations.length }} 处</template
          >
        </span>
        <span class="source-summary">{{ sourceSummary(source.content) }}</span>
        <span class="source-action">查看引用片段</span>
      </button>
      <el-empty v-if="!citedSources.length" description="暂无引用来源" :image-size="64" />
    </el-drawer>

    <el-dialog
      v-model="previewOpen"
      :title="
        selectedSource
          ? selectedSource.citationId + ' · ' + selectedSource.documentName
          : '引用预览'
      "
      width="min(760px, calc(100vw - 32px))"
      append-to-body
      align-center
      class="source-preview-dialog"
    >
      <template v-if="selectedSource">
        <p class="preview-meta">{{ selectedSource.knowledgeBaseName }}</p>
        <ul class="preview-locations">
          <li v-for="location in selectedSource.locations" :key="location.chunkId">
            {{ location.heading ? location.heading + ' · ' : '' }}{{ location.range.label }}
          </li>
        </ul>
        <MarkdownContent class="preview-content" :content="selectedSource.content" />
        <a
          v-if="conversationId && message?.status === 'COMPLETED'"
          class="source-original-link"
          :href="originalFileUrl(selectedSource)"
          target="_blank"
          rel="noopener noreferrer"
          >查看原文件</a
        >
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.source-space {
  width: 0;
  flex: 0 0 0;
}
.source-space.expanded {
  width: 380px;
  flex-basis: 380px;
}
@media (prefers-reduced-motion: no-preference) {
  .source-space {
    transition:
      width var(--motion-duration-layout) var(--motion-ease),
      flex-basis var(--motion-duration-layout) var(--motion-ease);
  }
  .source-card {
    transition:
      background-color var(--motion-duration-layout) ease,
      border-color var(--motion-duration-layout) ease;
  }
}
.source-count {
  font-size: 13px;
  color: var(--color-muted);
  margin: 0 0 18px;
}
.source-card {
  display: block;
  width: 100%;
  border: 1px solid var(--color-line);
  border-radius: 10px;
  padding: 16px;
  margin-bottom: 16px;
  background: #fff;
  color: inherit;
  text-align: left;
  cursor: pointer;
  scroll-margin: 12px;
}
.source-card:hover,
.source-card:focus-visible,
.source-card.highlighted {
  border-color: #aabcf5;
  background: #f8faff;
}
.source-card:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}
.source-title {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.source-title strong {
  font-size: 14px;
  font-weight: 550;
  overflow-wrap: anywhere;
}
.citation {
  padding: 2px 6px;
  background: var(--color-primary-soft);
  color: var(--color-primary);
  border-radius: 4px;
  font-size: 12px;
}
.source-meta,
.source-range,
.source-summary,
.source-action {
  display: block;
  overflow-wrap: anywhere;
}
.source-meta,
.source-range,
.preview-meta,
.preview-locations {
  font-size: 12px;
  line-height: 1.7;
  color: var(--color-muted);
}
.source-meta {
  margin-top: 10px;
}
.source-summary {
  margin-top: 12px;
  color: #52627b;
  font-size: 13px;
  line-height: 1.7;
}
.source-action {
  margin-top: 10px;
  color: var(--color-primary);
  font-size: 12px;
}
.preview-meta {
  margin: 0;
}
.preview-locations {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 20px;
  margin: 6px 0 18px;
  padding-left: 18px;
}
.preview-content {
  border-top: 1px solid var(--color-line);
  padding-top: 16px;
  color: var(--color-ink);
  font-size: 14px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
</style>

<style>
.source-preview-dialog {
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - 32px);
}
.source-preview-dialog .el-dialog__body {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}
</style>
