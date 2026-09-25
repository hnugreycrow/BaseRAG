<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getErrorMessage } from '../../api/http'
import { getRetrievalSettings, type RetrievalSettings } from '../../api/settings'

const settings = ref<RetrievalSettings | null>(null)
const loading = ref(false)
const error = ref('')
const loadedAt = ref('')
const groups = computed(() => {
  const s = settings.value
  if (!s) {
    return []
  }
  return [
    {
      title: '召回与融合',
      description: '控制每个子问题的向量召回数量与结果排序。',
      rows: [
        ['向量检索', s.vectorEnabled ? '已启用' : '已关闭', '是否从知识库召回向量相似的文档分块。'],
        [
          '每个子问题召回上限',
          `${s.recallBudget} 条`,
          '运行时实际使用的召回预算，已解析配置中的自动取值。',
        ],
        [
          '召回超时',
          `${s.channelTimeoutMs / 1000} 秒`,
          '数据库召回阶段的时间预算，不包含问题向量化耗时。',
        ],
        [
          '融合策略',
          s.fusionStrategy.toUpperCase(),
          '按名次融合召回结果，使不同向量模型的结果可统一排序。',
        ],
        ['RRF 平滑常数', String(s.rrfK), '数值越小，越强调排名靠前的结果。'],
        ['向量通道权重', String(s.vectorWeight), '向量检索结果在 RRF 融合中的权重。'],
      ],
    },
    {
      title: '去重与重排',
      description: '从候选分块中挑选用于生成回答的证据。',
      rows: [
        [
          '相邻分块去重阈值',
          String(s.deduplicationOverlapThreshold),
          '按字符三元组重叠率判断近似重复，取值范围为 0 到 1。',
        ],
        [
          '专用模型重排',
          s.rerankEnabled ? '已启用' : '已关闭',
          s.rerankEnabled
            ? '调用模型重新评估候选相关性；模型顺序见“模型服务”。'
            : '使用本地降级选择证据，证据数量上限仍然生效。',
        ],
        [
          '重排阶段候选上限',
          `${s.rerankInputLimit} 条`,
          '去重后进入重排阶段的最大候选数，关闭专用模型时仍用于限制候选。',
        ],
        [
          '回答证据上限',
          `${s.selectedEvidenceLimit} 条`,
          '最终送入回答上下文的证据数量上限，不保证每次都达到此数量。',
        ],
      ],
    },
    {
      title: '问题规划与路由',
      description: '控制复杂问题拆解和意图判断。',
      rows: [
        [
          '子问题上限',
          `${s.maxSubQuestions} 个`,
          '一次问题允许拆解出的最大子问题数，也是执行任务并发上限。',
        ],
        [
          '规划历史轮数',
          `${s.planningRecentTurns} 轮`,
          '问题规划最多读取的最近完整对话轮数，受可用历史轮数限制。',
        ],
        [
          '路由置信度阈值',
          String(s.routingConfidenceThreshold),
          '意图判断低于阈值时回退到知识检索。',
        ],
        ['路由超时', `${s.routingTimeoutMs / 1000} 秒`, '意图判断超过此时间后回退到知识检索。'],
      ],
    },
    {
      title: '回答上下文',
      description: '控制问题长度以及会话记忆的使用范围。',
      rows: [
        [
          '问题长度上限',
          `${s.maxQuestionChars} 字符`,
          '按 UTF-16 长度计数，部分表情符号会占用两个字符。',
        ],
        ['保留最近原文', `${s.recentTurns} 轮`, '构造回答上下文时保留的最近完整对话轮数。'],
        [
          '摘要更新批次',
          `${s.summaryBatchTurns} 轮`,
          '摘要与最近原文重叠的轮数，也是后续摘要更新的最大批次。',
        ],
        ['摘要长度上限', `${s.summaryMaxChars} 字符`, '话题摘要的 Unicode 字符数上限。'],
      ],
    },
  ]
})
async function load() {
  if (loading.value) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    settings.value = await getRetrievalSettings()
    loadedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (cause) {
    error.value = getErrorMessage(cause)
  } finally {
    loading.value = false
  }
}
defineExpose({ load, loading })
onMounted(load)
</script>

<template>
  <div :aria-busy="loading">
    <p class="scope-note">
      生效范围：服务端修改并重启后，用于后续请求；已有回答与文档分块不会因此重算。
    </p>
    <div v-if="error" class="retrieval-error" role="alert">
      <strong>检索配置读取失败</strong>
      <p>{{ error }}</p>
      <p v-if="settings">下方保留上次读取的配置，可能已过期。</p>
      <el-button :disabled="loading" @click="load">重试</el-button>
    </div>
    <div v-if="loading && !settings" class="loading-panel" role="status">
      正在读取检索与回答配置…
    </div>
    <template v-if="settings">
      <div class="snapshot-meta">
        <span>检索与回答配置</span><span>最近读取 {{ loadedAt }}</span>
      </div>
      <section
        v-for="(group, index) in groups"
        :key="group.title"
        class="retrieval-section admin-card"
        :aria-labelledby="`retrieval-group-${index}`"
      >
        <header>
          <h3 :id="`retrieval-group-${index}`">{{ group.title }}</h3>
          <p>{{ group.description }}</p>
        </header>
        <dl>
          <div v-for="row in group.rows" :key="row[0]" class="setting-row">
            <dt>{{ row[0] }}</dt>
            <dd>
              <strong>{{ row[1] }}</strong>
              <p>{{ row[2] }}</p>
            </dd>
          </div>
        </dl>
      </section>
    </template>
  </div>
</template>

<style scoped>
p {
  margin: 6px 0 0;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.7;
}
.scope-note {
  margin: 0 0 20px;
}
.snapshot-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: var(--color-muted);
  font-size: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.retrieval-section {
  overflow: hidden;
  margin-bottom: var(--card-gap);
}
header {
  padding: var(--card-padding);
  background: var(--card-background);
  border-bottom: 1px solid var(--color-line);
}
h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
}
dl {
  margin: 0;
}
.setting-row {
  display: grid;
  grid-template-columns: minmax(140px, 25%) 1fr;
  gap: 24px;
  padding: var(--card-padding);
}
.setting-row + .setting-row {
  border-top: 1px solid var(--color-line);
}
dt {
  font-size: 13px;
  padding-top: 2px;
}
dd {
  margin: 0;
  min-width: 0;
}
dd strong {
  font-size: 14px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.loading-panel {
  padding: 32px;
  text-align: center;
  color: var(--color-muted);
}
.retrieval-error {
  padding: 18px;
  margin-bottom: 20px;
  background: #fff5f3;
  color: #a33324;
  border-radius: 8px;
  font-size: 13px;
}
.retrieval-error .el-button {
  margin-top: 12px;
}
@media (max-width: 600px) {
  header {
    padding: var(--card-padding);
  }
  .setting-row {
    grid-template-columns: 1fr;
    gap: 8px;
    padding: var(--card-padding);
  }
}
</style>
