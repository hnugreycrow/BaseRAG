<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { AssistantMessage } from '../../api'
const props = defineProps<{ message: AssistantMessage | null; highlighted: string | null }>()
const open = defineModel<boolean>({ required: true })
// sources 是完整提示词证据快照；来源抽屉只展示回答真正使用的引用。
const citedSources = computed(() => {
  const cited = new Set(props.message?.citations ?? [])
  return (props.message?.sources ?? []).filter((source) => cited.has(source.citationId))
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
      <article
        v-for="source in citedSources"
        :id="'source-' + message?.id + '-' + source.citationId"
        :key="source.citationId"
        class="source-card"
        :class="{ highlighted: highlighted === source.citationId }"
      >
        <header>
          <span class="citation">{{ source.citationId }}</span>
          <h3>{{ source.documentName }}</h3>
        </header>
        <p>
          {{ source.knowledgeBaseName
          }}<template v-if="source.primaryLocation.heading">
            · {{ source.primaryLocation.heading }}</template
          >
        </p>
        <small>{{ source.primaryLocation.range.label }}</small>
        <ul class="source-locations">
          <li v-for="location in source.locations" :key="location.chunkId">
            {{ location.heading ? location.heading + ' · ' : '' }}{{ location.range.label }}
          </li>
        </ul>
        <blockquote>{{ source.content }}</blockquote>
      </article>
      <el-empty v-if="!citedSources.length" description="暂无引用来源" :image-size="64" />
    </el-drawer>
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
  border: 1px solid var(--color-line);
  border-radius: 10px;
  padding: 16px;
  margin-bottom: 16px;
  scroll-margin: 12px;
}
.source-card.highlighted {
  border-color: #aabcf5;
  background: #f8faff;
}
header {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
h3 {
  margin: 0;
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
p,
small {
  font-size: 12px;
  line-height: 1.7;
  color: var(--color-muted);
  overflow-wrap: anywhere;
}
blockquote {
  margin: 14px 0 0;
  color: #52627b;
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 360px;
  overflow-y: auto;
}
.source-locations {
  margin: 8px 0 0;
  padding-left: 18px;
  color: var(--color-muted);
  font-size: 12px;
}
</style>
