<script setup lang="ts">
import { computed } from 'vue'

import { renderMarkdown } from './markdown'

const props = defineProps<{ content: string }>()
const html = computed(() => renderMarkdown(props.content))
</script>

<template>
  <div class="markdown-content" v-html="html"></div>
</template>

<style scoped>
.markdown-content {
  color: var(--color-ink);
  font-size: 15px;
  line-height: 1.85;
  overflow-wrap: anywhere;
}

.markdown-content :deep(h1),
.markdown-content :deep(h2),
.markdown-content :deep(h3),
.markdown-content :deep(h4),
.markdown-content :deep(h5),
.markdown-content :deep(h6) {
  margin: 1.55em 0 0.65em;
  color: #15233e;
  line-height: 1.35;
  scroll-margin-top: 96px;
}

.markdown-content :deep(h1:first-child),
.markdown-content :deep(h2:first-child),
.markdown-content :deep(h3:first-child) {
  margin-top: 0;
}

.markdown-content :deep(h1) {
  padding-bottom: 0.45em;
  border-bottom: 1px solid var(--color-line);
  font-size: 2em;
  letter-spacing: -0.04em;
}

.markdown-content :deep(h2) {
  font-size: 1.5em;
  letter-spacing: -0.025em;
}

.markdown-content :deep(h3) {
  font-size: 1.2em;
}

.markdown-content :deep(p),
.markdown-content :deep(ul),
.markdown-content :deep(ol),
.markdown-content :deep(blockquote),
.markdown-content :deep(pre),
.markdown-content :deep(table) {
  margin: 0 0 1.15em;
}

.markdown-content :deep(ul),
.markdown-content :deep(ol) {
  padding-left: 1.65em;
}

.markdown-content :deep(li + li) {
  margin-top: 0.28em;
}

.markdown-content :deep(blockquote) {
  padding: 0.15em 0 0.15em 1.1em;
  color: #52627b;
  border-left: 3px solid #91a6ec;
}

.markdown-content :deep(pre) {
  overflow-x: auto;
  padding: 16px 18px;
  color: #dfe7ff;
  background: #1d2940;
  border-radius: 10px;
  line-height: 1.6;
}

.markdown-content :deep(code) {
  padding: 2px 5px;
  color: #304b9a;
  background: #eef2fb;
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 0.9em;
}

.markdown-content :deep(pre code) {
  padding: 0;
  color: inherit;
  background: transparent;
}

.markdown-content :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
  border-collapse: collapse;
}

.markdown-content :deep(th),
.markdown-content :deep(td) {
  padding: 8px 12px;
  border: 1px solid var(--color-line);
  text-align: left;
}

.markdown-content :deep(th) {
  background: #f6f8fc;
}

.markdown-content :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
}

.markdown-content :deep(a) {
  color: var(--color-primary);
  text-decoration: underline;
  text-decoration-color: #a8b7ed;
  text-underline-offset: 3px;
}
</style>
