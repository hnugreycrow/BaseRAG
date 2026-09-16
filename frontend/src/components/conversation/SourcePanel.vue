<script setup lang="ts">
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { AnswerSource, AssistantMessage } from '../../api'

const props = defineProps<{ message: AssistantMessage | null; highlighted: string | null }>()
const open = defineModel<boolean>({ required: true })
// sources 是完整提示词证据快照；来源抽屉只展示回答真正使用的引用。
const citedSources = computed(() => {
  const cited = new Set(props.message?.citations ?? [])
  return (props.message?.sources ?? []).filter((source) => cited.has(source.citationId))
})
const selectedCitationId = ref<string | null>(null)
const previewOpen = ref(false)
const selectedSource = computed(
  () => citedSources.value.find((source) => source.citationId === selectedCitationId.value) ?? null,
)

function renderMarkdown(content: string) {
  const html = marked.parse(content, { async: false }) as string
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
}

function sourceSummary(content: string) {
  const container = document.createElement('div')
  container.innerHTML = renderMarkdown(content)
  container
    .querySelectorAll('p, h1, h2, h3, h4, h5, h6, li, blockquote, pre, tr')
    .forEach((node) => node.append(' '))
  const plainText = (container.textContent ?? '').replace(/\s+/g, ' ').trim()
  return plainText.length > 120 ? plainText.slice(0, 120).trimEnd() + '…' : plainText || '暂无内容'
}

const previewHtml = computed(() =>
  selectedSource.value ? renderMarkdown(selectedSource.value.content) : '',
)

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
onBeforeUnmount(() => window.removeEventListener('resize', resize))
function locate() {
  if (props.highlighted && props.message)
    void nextTick(() =>
      document
        .getElementById('source-' + props.message!.id + '-' + props.highlighted)
        ?.scrollIntoView({ block: 'nearest' }),
    )
}
watch(() => [props.highlighted, props.message?.id], locate)
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
        :class="{ highlighted: highlighted === source.citationId }"
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
        <div class="preview-content markdown-body" v-html="previewHtml"></div>
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
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 1.3em 0 0.5em;
  line-height: 1.4;
}
.markdown-body :deep(h1:first-child),
.markdown-body :deep(h2:first-child),
.markdown-body :deep(h3:first-child) {
  margin-top: 0;
}
.markdown-body :deep(p),
.markdown-body :deep(ul),
.markdown-body :deep(ol),
.markdown-body :deep(blockquote),
.markdown-body :deep(pre) {
  margin: 0 0 1em;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 1.6em;
}
.markdown-body :deep(blockquote) {
  border-left: 3px solid #aabcf5;
  padding-left: 1em;
  color: #52627b;
}
.markdown-body :deep(pre) {
  overflow-x: auto;
  border-radius: 8px;
  padding: 12px;
  background: #f4f6fa;
  line-height: 1.5;
}
.markdown-body :deep(code) {
  border-radius: 4px;
  padding: 1px 4px;
  background: #f4f6fa;
  font-size: 0.9em;
}
.markdown-body :deep(pre code) {
  padding: 0;
}
.markdown-body :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
  border-collapse: collapse;
  margin-bottom: 1em;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid var(--color-line);
  padding: 6px 10px;
}
.markdown-body :deep(img) {
  max-width: 100%;
  height: auto;
}
.markdown-body :deep(a) {
  color: var(--color-primary);
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
