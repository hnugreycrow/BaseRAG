<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getErrorMessage } from '../api/http'
import { getModelSettings, type ModelSettings } from '../api/settings'

import RetrievalSettingsPanel from '../components/settings/RetrievalSettingsPanel.vue'

const activeTab = ref<'models' | 'retrieval'>('models')
const retrievalPanel = ref<InstanceType<typeof RetrievalSettingsPanel> | null>(null)
const tabs = [
  { id: 'models', label: '模型服务' },
  { id: 'retrieval', label: '检索与回答' },
] as const
function moveTab(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
    return
  }
  event.preventDefault()
  activeTab.value =
    event.key === 'Home'
      ? 'models'
      : event.key === 'End'
        ? 'retrieval'
        : activeTab.value === 'models'
          ? 'retrieval'
          : 'models'
  document.getElementById(`settings-tab-${activeTab.value}`)?.focus()
}
function refreshActive() {
  if (activeTab.value === 'retrieval') {
    void retrievalPanel.value?.load()
  } else {
    void load()
  }
}
const activeLoading = computed(() =>
  activeTab.value === 'models' ? loading.value : (retrievalPanel.value?.loading ?? false),
)

const settings = ref<ModelSettings | null>(null)
const loading = ref(false)
const error = ref('')
const loadedAt = ref('')
const groups = computed(() =>
  settings.value
    ? [
        {
          key: 'chat',
          title: '对话模型',
          description: '用于生成回答，按下方顺序尝试候选模型。',
          meta: `当前档位 · ${settings.value.chatTier}`,
          models: settings.value.chat,
        },
        {
          key: 'embedding',
          title: '向量模型',
          description: '用于文档向量化与检索。默认模型仅用于新建知识库，已有知识库保持绑定。',
          meta: `每批 ${settings.value.embeddingBatchSize} 条`,
          models: settings.value.embedding,
        },
        {
          key: 'rerank',
          title: '重排模型',
          description: '用于调整检索结果顺序，按下方顺序尝试；跳过重排表示使用本地降级策略。',
          meta: '按配置顺序降级',
          models: settings.value.rerank,
        },
      ]
    : [],
)

async function load() {
  if (loading.value) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    settings.value = await getModelSettings()
    loadedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (cause) {
    error.value = getErrorMessage(cause)
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <div class="settings-page" :aria-busy="activeLoading">
    <header class="page-header">
      <div>
        <h1>系统配置</h1>
        <p>查看当前生效的模型服务、检索策略与回答设置。</p>
      </div>
      <el-button :icon="Refresh" :loading="activeLoading" @click="refreshActive"
        >刷新配置</el-button
      >
    </header>

    <div class="settings-tabs" role="tablist" aria-label="配置分类" @keydown="moveTab">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        :id="`settings-tab-${tab.id}`"
        type="button"
        role="tab"
        :aria-selected="activeTab === tab.id"
        :aria-controls="`settings-panel-${tab.id}`"
        :tabindex="activeTab === tab.id ? 0 : -1"
        @click="activeTab = tab.id"
      >
        {{ tab.label }}
      </button>
    </div>
    <div class="configuration-note">
      <el-tag type="info" effect="plain">由服务端管理 · 只读</el-tag>
      <p>此处展示当前进程的配置。刷新仅重新读取配置；修改需在服务端完成并重启服务。</p>
    </div>
    <div
      v-show="activeTab === 'models'"
      id="settings-panel-models"
      role="tabpanel"
      aria-labelledby="settings-tab-models"
      tabindex="0"
    >
      <div v-if="error" class="error-message" role="alert">
        <strong>配置读取失败</strong>
        <p>{{ error }}</p>
        <span v-if="settings">下方保留上次读取的配置，可能已过期。</span>
        <el-button @click="load" :disabled="loading">重试</el-button>
      </div>
      <div v-if="loading && !settings" class="loading-panel" role="status">正在读取系统配置…</div>

      <template v-if="settings">
        <div class="snapshot-meta">
          <span>配置概览</span><span>最近读取 {{ loadedAt }} · 未执行连接检测</span>
        </div>
        <section
          v-for="group in groups"
          :key="group.key"
          class="model-section"
          :aria-labelledby="`${group.key}-title`"
        >
          <header class="group-header">
            <div>
              <h3 :id="`${group.key}-title`">{{ group.title }}</h3>
              <p>{{ group.description }}</p>
            </div>
            <span class="group-meta">{{ group.meta }}</span>
          </header>
          <div v-if="!group.models.length" class="empty-message">暂无已配置模型</div>
          <ol v-else class="model-list">
            <li v-for="(model, index) in group.models" :key="model.id" class="model-row">
              <span class="sequence" :aria-label="`第 ${index + 1} 项`">{{
                String(index + 1).padStart(2, '0')
              }}</span>
              <div class="model-identity">
                <div class="model-name">
                  <strong>{{ model.localFallback ? '跳过重排' : model.model }}</strong
                  ><el-tag v-if="model.defaultModel" size="small" effect="plain">{{
                    group.key === 'chat' ? '首选' : '默认'
                  }}</el-tag>
                </div>
                <span
                  >{{ model.localFallback ? '本地降级 · 不调用远端模型' : model.provider }} ·
                  {{ model.id }}</span
                >
              </div>
              <div class="model-properties">
                <span v-if="model.dimensions">{{ model.dimensions }} 维</span>
                <span v-if="group.key === 'chat'">{{
                  model.supportsThinking ? '支持思考' : '标准生成'
                }}</span>
                <span v-if="!model.localFallback">超时 {{ model.timeoutMs / 1000 }} 秒</span>
              </div>
              <div class="credential-state">
                <template v-if="!model.localFallback">
                  <el-tag :type="model.credentialConfigured ? 'info' : 'warning'" effect="light">{{
                    model.credentialConfigured ? '凭据已配置' : '未配置凭据'
                  }}</el-tag>
                  <span>连接未检测</span>
                </template>
                <span v-else>无需凭据</span>
              </div>
            </li>
          </ol>
        </section>
        <section class="policy-section" aria-labelledby="policy-title">
          <div>
            <h3 id="policy-title">调用保护</h3>
            <p>对可重试的错误进行重试，达到失败阈值后暂时熔断。</p>
          </div>
          <dl>
            <div>
              <dt>最大重试次数</dt>
              <dd>{{ settings.maxRetries }} <small>次</small></dd>
            </div>
            <div>
              <dt>熔断失败阈值</dt>
              <dd>{{ settings.failureThreshold }} <small>次</small></dd>
            </div>
            <div>
              <dt>熔断持续时间</dt>
              <dd>{{ settings.openDurationMs / 1000 }} <small>秒</small></dd>
            </div>
          </dl>
        </section>
      </template>
    </div>
    <div
      v-show="activeTab === 'retrieval'"
      id="settings-panel-retrieval"
      role="tabpanel"
      aria-labelledby="settings-tab-retrieval"
      tabindex="0"
    >
      <KeepAlive
        ><RetrievalSettingsPanel v-if="activeTab === 'retrieval'" ref="retrievalPanel"
      /></KeepAlive>
    </div>
  </div>
</template>

<style scoped>
.settings-tabs {
  display: flex;
  gap: 24px;
  margin-top: 24px;
}
.settings-tabs button {
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  padding: 14px 2px;
  color: var(--color-muted);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}
.settings-tabs button[aria-selected='true'] {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: 600;
}
.settings-tabs button:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 3px;
  border-radius: 3px;
}

.settings-page {
  padding: 29px 30px 45px;
  color: var(--color-ink);
}
.page-header,
.group-header,
.snapshot-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}
h1 {
  margin: 0;
  font-size: 25px;
  letter-spacing: -0.7px;
}
p {
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.7;
  margin: 7px 0 0;
}
.section-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 30px;
  padding-bottom: 14px;
  border-bottom: 2px solid var(--color-primary);
  width: fit-content;
  color: var(--color-primary);
}
h2 {
  margin: 0;
  font-size: 14px;
}
.configuration-note {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 0 22px;
  border-top: 1px solid var(--color-line);
}
.configuration-note p {
  margin: 0;
}
.snapshot-meta {
  color: var(--color-muted);
  font-size: 12px;
  margin-bottom: 12px;
}
.model-section,
.policy-section {
  background: white;
  border: 1px solid var(--color-line);
  border-radius: 12px;
  margin-bottom: 18px;
  overflow: hidden;
}
.group-header {
  padding: 22px 24px;
  background: #fafbfd;
  border-bottom: 1px solid var(--color-line);
}
h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
}
.group-meta {
  flex-shrink: 0;
  color: var(--color-muted);
  font-size: 12px;
}
.model-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.model-row {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) auto 110px;
  align-items: center;
  gap: 18px;
  padding: 20px 24px;
}
.model-row + .model-row {
  border-top: 1px solid var(--color-line);
}
.sequence {
  font-size: 12px;
  color: var(--color-muted);
  font-variant-numeric: tabular-nums;
}
.model-name {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 6px;
}
.model-name strong {
  font-size: 14px;
  overflow-wrap: anywhere;
}
.model-identity > span {
  color: var(--color-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}
.model-properties,
.credential-state {
  display: flex;
  gap: 8px;
  font-size: 12px;
  color: var(--color-muted);
}
.model-properties {
  flex-wrap: wrap;
  justify-content: flex-end;
}
.model-properties span {
  padding: 4px 8px;
  background: #f4f6fa;
  border-radius: 5px;
}
.credential-state {
  flex-direction: column;
  align-items: flex-end;
}
.policy-section {
  padding: 22px 24px;
}
dl {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
  margin: 22px 0 0;
}
dt {
  font-size: 12px;
  color: var(--color-muted);
}
dd {
  margin: 8px 0 0;
  font-size: 22px;
  font-variant-numeric: tabular-nums;
}
dd small {
  font-size: 12px;
  font-weight: normal;
  color: var(--color-muted);
}
.error-message {
  padding: 18px;
  margin-bottom: 20px;
  background: #fff5f3;
  color: #a33324;
  border-radius: 8px;
  font-size: 13px;
}
.error-message .el-button {
  margin: 12px 0 0 12px;
}
.empty-message,
.loading-panel {
  padding: 32px;
  text-align: center;
  color: var(--color-muted);
  font-size: 13px;
}
@media (max-width: 760px) {
  .settings-page {
    padding: 24px 16px;
  }
  .configuration-note,
  .group-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
  }
  .snapshot-meta {
    flex-wrap: wrap;
    gap: 8px;
  }
  .model-row {
    grid-template-columns: 24px minmax(0, 1fr);
    gap: 12px;
    padding: 18px;
  }
  .model-properties {
    grid-column: 2;
    justify-content: flex-start;
  }
  .credential-state {
    grid-column: 2;
    align-items: center;
    flex-direction: row;
  }
  .group-header,
  .policy-section {
    padding: 18px;
  }
  dl {
    gap: 10px;
  }
}
</style>
