<script setup lang="ts">
import { onMounted, ref } from "vue";
import type { Exchange } from "../stores/conversations";
import AppIcon from "./AppIcon.vue";
const props = defineProps<{ exchange: Exchange; citationId?: string }>();
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
onMounted(() => dialog.value?.showModal());
function dismiss(event: MouseEvent) {
  if (event.target === dialog.value) dialog.value?.close();
}
</script>
<template>
  <dialog
    ref="dialog"
    class="source-dialog"
    aria-labelledby="sources-title"
    @close="emit('close')"
    @click="dismiss"
  >
    <div class="source-drawer">
      <header class="drawer-heading">
        <div>
          <span class="eyebrow">回答依据</span>
          <h2 id="sources-title">
            检索来源 <span>{{ exchange.answer?.sources.length || 0 }}</span>
          </h2>
        </div>
        <button
          class="icon-button"
          aria-label="关闭检索来源"
          autofocus
          @click="dialog?.close()"
        >
          <AppIcon name="close" />
        </button>
      </header>
      <div class="drawer-body">
        <p class="source-question">{{ exchange.question }}</p>
        <p class="muted small">
          以下为本次回答实际送入模型的完整片段。相似度表示检索相关性，不代表答案正确率。
        </p>
        <div v-if="!exchange.answer?.sources.length" class="empty-state">
          <AppIcon name="book" :size="30" />
          <p>本次没有检索到可用资料。</p>
        </div>
        <details
          v-for="(source, index) in exchange.answer?.sources"
          :key="source.chunkId"
          class="source-card"
          :open="citationId ? source.citationId === citationId : index === 0"
        >
          <summary>
            <div class="source-card-label">
              <span class="citation-badge">[{{ source.citationId }}]</span
              ><span
                v-if="exchange.answer?.citations.includes(source.citationId)"
                class="status ready"
                >回答已引用</span
              ><AppIcon name="down" :size="16" />
            </div>
            <h3>{{ source.documentName }}</h3>
            <p>
              {{ source.knowledgeBaseName }} · {{ source.heading || "文档正文" }}
            </p>
            <div class="source-meta">
              <span>第 {{ source.lineStart }}–{{ source.lineEnd }} 行</span
              ><span>相似度 {{ source.similarity.toFixed(3) }}</span>
            </div>
          </summary>
          <pre>{{ source.content }}</pre>
        </details>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.source-dialog {
  position: fixed;
  inset: 0 0 0 auto;
  width: 440px;
  max-width: 100%;
  height: 100vh;
  height: 100dvh;
  max-height: 100dvh;
  margin: 0;
  padding: 0;
  color: var(--ink);
  background: white;
  border: 0;
  box-shadow: -18px 0 55px #1220391a;
}
.source-dialog::backdrop {
  background: #10101066;
  backdrop-filter: blur(2px);
}
.source-dialog[open] {
  display: flex;
}
.source-drawer {
  display: flex;
  flex-direction: column;
  width: 100%;
  min-height: 0;
}
.drawer-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 15px;
  padding: 21px 22px;
  border-bottom: 1px solid var(--line);
}
.drawer-heading .eyebrow {
  color: #8b8b8b;
  font-size: 11px;
  letter-spacing: 1.4px;
}
.drawer-heading h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 3px;
  font-size: 17px;
  font-weight: 650;
}
.drawer-heading h2 span {
  min-width: 20px;
  padding: 1px 6px;
  color: #0a765c;
  font-size: 11px;
  text-align: center;
  background: #e8f5f1;
  border-radius: 9px;
}
.drawer-body {
  overflow-y: auto;
  padding: 21px;
}
.source-question {
  color: #2d2d2d;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.drawer-body > .muted {
  margin: 7px 0 19px;
  font-size: 12px;
  line-height: 1.8;
}
.source-card {
  overflow: hidden;
  margin-bottom: 12px;
  border: 1px solid var(--line);
  border-radius: 9px;
}
.source-card summary {
  padding: 14px;
  list-style: none;
  cursor: pointer;
}
.source-card summary::-webkit-details-marker {
  display: none;
}
.source-card summary:hover {
  background: #fafafa;
}
.source-card-label {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 8px;
}
.source-card-label > svg {
  margin-left: auto;
  color: #969696;
}
.source-card[open] .source-card-label > svg {
  transform: rotate(180deg);
}
.citation-badge {
  color: #0a765c;
  font-size: 12px;
  font-weight: 650;
}
.status {
  display: inline-flex;
  align-items: center;
  padding: 2px 6px;
  color: #188359;
  font-size: 11px;
  background: #eaf7f1;
  border-radius: 4px;
}
.source-card h3 {
  color: #333;
  font-size: 13px;
  font-weight: 620;
  overflow-wrap: anywhere;
}
.source-card summary p {
  margin-top: 4px;
  color: #878787;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.source-meta {
  display: flex;
  justify-content: space-between;
  gap: 6px;
  margin-top: 11px;
  color: #9b9b9b;
  font-size: 11px;
}
.source-card pre {
  margin: 0;
  padding: 15px;
  color: #484848;
  font:
    13px/1.85 ui-monospace,
    Consolas,
    "Microsoft YaHei",
    monospace;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f7f7f7;
  border-top: 1px solid var(--line);
}
.empty-state {
  padding: 35px 12px;
  color: #989898;
  text-align: center;
}
@media (max-width: 760px) {
  .source-dialog {
    width: 100%;
  }
}
</style>
