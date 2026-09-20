<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import {
  documentContentUrl,
  getDocumentPreview,
  getErrorMessage,
  type DocumentPreview,
  type DocumentPreviewBlock,
} from '../api'
import MarkdownContent from '../components/common/MarkdownContent.vue'

interface DocxSection {
  kind: DocumentPreviewBlock['kind']
  blocks: DocumentPreviewBlock[]
}

const route = useRoute()
const loading = ref(true)
const error = ref('')
const preview = ref<DocumentPreview | null>(null)

const knowledgeBaseId = computed(() => String(route.params.knowledgeBaseId || ''))
const documentId = computed(() => String(route.params.documentId || ''))
const pdfUrl = computed(() => documentContentUrl(knowledgeBaseId.value, documentId.value))
const docxSections = computed<DocxSection[]>(() => {
  const sections: DocxSection[] = []
  for (const block of preview.value?.blocks ?? []) {
    const previous = sections.at(-1)
    if (block.kind === 'LIST' && previous?.kind === 'LIST') {
      previous.blocks.push(block)
    } else {
      sections.push({ kind: block.kind, blocks: [block] })
    }
  }
  return sections
})

const formatLabels = {
  MARKDOWN: 'Markdown',
  PDF: 'PDF',
  DOCX: 'Word',
} as const

function formatBytes(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function sourceLabel(block: DocumentPreviewBlock) {
  const labels = { LINE: '行', PAGE: '页', PARAGRAPH: '段' } as const
  const unit = labels[block.sourceUnit]
  return block.sourceStart === block.sourceEnd
    ? '第 ' + block.sourceStart + ' ' + unit
    : '第 ' + block.sourceStart + '–' + block.sourceEnd + ' ' + unit
}

function headingTag(block: DocumentPreviewBlock) {
  return 'h' + Math.max(1, Math.min(6, block.level ?? 2))
}

async function loadPreview() {
  loading.value = true
  error.value = ''
  preview.value = null
  try {
    const result = await getDocumentPreview(knowledgeBaseId.value, documentId.value)
    preview.value = result
    document.title = result.name + ' · 文档预览'
  } catch (reason) {
    error.value = getErrorMessage(reason)
  } finally {
    loading.value = false
  }
}

watch([knowledgeBaseId, documentId], () => void loadPreview(), { immediate: true })
</script>

<template>
  <main class="preview-shell" :class="'format-' + (preview?.format || 'loading').toLowerCase()">
    <header class="preview-header">
      <div class="file-mark" aria-hidden="true">
        {{ preview ? formatLabels[preview.format].slice(0, 2) : '…' }}
      </div>
      <div class="file-identity">
        <span class="eyebrow">文档预览</span>
        <h1>{{ preview?.name || '正在读取文档' }}</h1>
        <p v-if="preview">
          {{ formatLabels[preview.format] }} · {{ formatBytes(preview.fileSizeBytes) }}
        </p>
      </div>
      <a
        v-if="preview"
        class="original-link"
        :href="pdfUrl"
        target="_blank"
        rel="noopener noreferrer"
      >
        打开原文件
      </a>
    </header>

    <section v-if="loading" class="preview-state" aria-live="polite">
      <span class="loading-line"></span>
      <strong>正在准备预览</strong>
      <p>正在读取文档内容与格式信息。</p>
    </section>

    <section v-else-if="error" class="preview-state preview-error" role="alert">
      <span class="state-code">读取失败</span>
      <strong>无法打开这篇文档</strong>
      <p>{{ error }}</p>
      <el-button type="primary" @click="loadPreview">重新加载</el-button>
    </section>

    <template v-else-if="preview">
      <article v-if="preview.format === 'MARKDOWN'" class="reading-sheet markdown-sheet">
        <MarkdownContent :content="preview.content || ''" />
      </article>

      <section v-else-if="preview.format === 'PDF'" class="pdf-stage">
        <object :data="pdfUrl" type="application/pdf" class="pdf-frame">
          <div class="pdf-fallback">
            <strong>浏览器无法在页面内显示 PDF</strong>
            <p>可以在新标签页中使用浏览器自带的 PDF 阅读器打开。</p>
            <a :href="pdfUrl" target="_blank" rel="noopener noreferrer">打开 PDF</a>
          </div>
        </object>
      </section>

      <article v-else class="reading-sheet docx-sheet">
        <div
          v-for="(section, sectionIndex) in docxSections"
          :key="sectionIndex"
          class="docx-section"
          :class="'section-' + section.kind.toLowerCase()"
        >
          <template v-if="section.kind === 'HEADING'">
            <component :is="headingTag(section.blocks[0]!)">
              {{ section.blocks[0]!.content }}
            </component>
            <span class="source-position">{{ sourceLabel(section.blocks[0]!) }}</span>
          </template>
          <ul v-else-if="section.kind === 'LIST'">
            <li v-for="block in section.blocks" :key="block.sourceStart">
              {{ block.content }}
            </li>
          </ul>
          <pre v-else-if="section.kind === 'TABLE'" class="docx-table">{{
            section.blocks[0]!.content
          }}</pre>
          <pre v-else-if="section.kind === 'CODE'" class="docx-code"><code>{{
            section.blocks[0]!.content
          }}</code></pre>
          <p v-else>{{ section.blocks[0]!.content }}</p>
        </div>
        <el-empty
          v-if="docxSections.length === 0"
          description="文档中没有可预览的文本"
          :image-size="72"
        />
      </article>
    </template>
  </main>
</template>

<style scoped>
.preview-shell {
  --format-accent: #4263eb;
  min-height: 100dvh;
  padding: 0 28px 48px;
  background: linear-gradient(90deg, var(--format-accent) 0 5px, transparent 5px), #f2f5fa;
}

.preview-shell.format-pdf {
  --format-accent: #dd665b;
}

.preview-shell.format-docx {
  --format-accent: #3b72b8;
}

.preview-header {
  position: sticky;
  z-index: 5;
  top: 0;
  max-width: 1180px;
  min-height: 88px;
  display: flex;
  align-items: center;
  gap: 16px;
  margin: 0 auto 24px;
  padding: 14px 0;
  background: rgb(242 245 250 / 94%);
  border-bottom: 1px solid #dce3ed;
  backdrop-filter: blur(14px);
}

.file-mark {
  width: 45px;
  height: 52px;
  display: grid;
  flex: 0 0 auto;
  place-items: end center;
  padding-bottom: 8px;
  color: white;
  background:
    linear-gradient(135deg, transparent 0 10px, rgb(255 255 255 / 24%) 10px 11px, transparent 11px)
      top right / 16px 16px no-repeat,
    var(--format-accent);
  border-radius: 4px 10px 4px 4px;
  font-family: var(--font-data);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.04em;
}

.file-identity {
  min-width: 0;
  flex: 1;
}

.eyebrow {
  color: var(--format-accent);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.14em;
}

.file-identity h1 {
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--color-ink);
  font-size: 18px;
  font-weight: 720;
  letter-spacing: -0.025em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-identity p {
  margin: 4px 0 0;
  color: var(--color-muted);
  font-family: var(--font-data);
  font-size: 12px;
}

.original-link {
  min-height: 36px;
  display: inline-flex;
  align-items: center;
  padding: 0 14px;
  color: #354968;
  background: white;
  border: 1px solid #d9e0ea;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
}

.original-link:hover {
  color: var(--format-accent);
  border-color: color-mix(in srgb, var(--format-accent) 45%, white);
}

.reading-sheet,
.preview-state {
  max-width: 880px;
  margin: 0 auto;
  background: white;
  border: 1px solid #e0e6ee;
  box-shadow: 0 22px 60px rgb(41 56 84 / 8%);
}

.reading-sheet {
  min-height: calc(100dvh - 160px);
  padding: clamp(34px, 6vw, 72px) clamp(24px, 8vw, 86px);
  border-radius: 4px;
}

.preview-state {
  min-height: 360px;
  display: flex;
  align-items: center;
  flex-direction: column;
  justify-content: center;
  padding: 40px;
  border-radius: 10px;
  text-align: center;
}

.preview-state strong {
  margin-top: 18px;
  color: var(--color-ink);
  font-size: 18px;
}

.preview-state p {
  max-width: 520px;
  margin: 8px 0 22px;
  color: var(--color-muted);
  line-height: 1.7;
}

.loading-line {
  width: 92px;
  height: 3px;
  overflow: hidden;
  background: #dfe5ef;
  border-radius: 99px;
}

.loading-line::after {
  width: 44%;
  height: 100%;
  display: block;
  background: var(--format-accent);
  border-radius: inherit;
  content: '';
  animation: loading 1.2s ease-in-out infinite alternate;
}

.state-code {
  padding: 5px 9px;
  color: #a4483f;
  background: #fff0ee;
  border-radius: 5px;
  font-size: 12px;
  font-weight: 700;
}

.pdf-stage {
  max-width: 1180px;
  height: calc(100dvh - 136px);
  min-height: 520px;
  margin: 0 auto;
  overflow: hidden;
  background: #525966;
  border: 1px solid #d8dee8;
  border-radius: 6px;
  box-shadow: 0 22px 60px rgb(41 56 84 / 12%);
}

.pdf-frame {
  width: 100%;
  height: 100%;
  border: 0;
}

.pdf-fallback {
  padding: 64px 24px;
  color: white;
  text-align: center;
}

.pdf-fallback p {
  color: #d5dbe5;
}

.pdf-fallback a {
  color: #cbd7ff;
  text-decoration: underline;
}

.docx-section {
  position: relative;
  color: #273650;
  font-size: 15px;
  line-height: 1.85;
}

.docx-section h1,
.docx-section h2,
.docx-section h3,
.docx-section h4,
.docx-section h5,
.docx-section h6 {
  margin: 1.55em 0 0.65em;
  color: var(--color-ink);
  line-height: 1.35;
}

.docx-section:first-child :is(h1, h2, h3, h4, h5, h6) {
  margin-top: 0;
}

.docx-section h1 {
  padding-bottom: 0.45em;
  border-bottom: 1px solid var(--color-line);
  font-size: 1.9em;
}

.docx-section h2 {
  font-size: 1.45em;
}

.docx-section h3 {
  font-size: 1.18em;
}

.docx-section p,
.docx-section ul,
.docx-section pre {
  margin: 0 0 1.1em;
}

.docx-section ul {
  padding-left: 1.6em;
}

.source-position {
  position: absolute;
  top: 0.35em;
  right: calc(100% + 22px);
  color: #9aa6b8;
  font-family: var(--font-data);
  font-size: 10px;
  white-space: nowrap;
}

.docx-table {
  overflow-x: auto;
  padding: 14px 16px;
  color: #34435b;
  background: #f6f8fb;
  border: 1px solid #e0e6ef;
  border-radius: 8px;
  font-family: var(--font-data);
  line-height: 1.7;
}

.docx-code {
  overflow-x: auto;
  padding: 16px 18px;
  color: #dfe7ff;
  background: #1d2940;
  border-radius: 10px;
}

@keyframes loading {
  from {
    transform: translateX(0);
  }
  to {
    transform: translateX(128%);
  }
}

@media (max-width: 720px) {
  .preview-shell {
    padding: 0 12px 24px;
  }

  .preview-header {
    min-height: 76px;
    margin-bottom: 12px;
  }

  .file-mark {
    width: 38px;
    height: 44px;
  }

  .original-link {
    padding: 0 10px;
    font-size: 12px;
  }

  .reading-sheet {
    min-height: calc(100dvh - 112px);
    padding: 28px 20px;
  }

  .pdf-stage {
    height: calc(100dvh - 100px);
    min-height: 420px;
  }

  .source-position {
    position: static;
    display: block;
    margin: -0.4em 0 0.8em;
  }
}
</style>
