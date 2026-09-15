<script setup lang="ts">
import { ArrowLeft, RefreshRight, WarningFilled } from '@element-plus/icons-vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiRequestError, getErrorMessage, getRagRun, type RagRunDetail } from '../api'
import TraceStatusBadge from '../components/observability/TraceStatusBadge.vue'
import {
  buildWaterfall,
  EXECUTION_MODE_LABELS,
  formatDateTime,
  formatDuration,
  shortId,
  STAGE_NAME_LABELS,
  STAGE_STATUS_LABELS,
} from '../components/observability/tracePresentation'

const route = useRoute()
const router = useRouter()
const detail = ref<RagRunDetail | null>(null)
const loading = ref(true)
const error = ref('')
const notFound = ref(false)
let requestSequence = 0

const waterfall = computed(() => buildWaterfall(detail.value?.stages ?? []))
const modelAttempts = computed(() =>
  (detail.value?.stages ?? []).filter((stage) => stage.stageName === 'ANSWER_MODEL'),
)
const candidateStages = computed(() =>
  (detail.value?.stages ?? []).filter((stage) =>
    ['CANDIDATE_MERGE', 'DEDUPLICATION', 'RERANK', 'PROMPT_ASSEMBLY'].includes(stage.stageName),
  ),
)

async function loadDetail() {
  const runId = String(route.params.runId ?? '')
  const sequence = ++requestSequence
  loading.value = true
  error.value = ''
  notFound.value = false
  try {
    const result = await getRagRun(runId)
    if (sequence === requestSequence) detail.value = result
  } catch (cause) {
    if (sequence !== requestSequence) return
    detail.value = null
    notFound.value = cause instanceof ApiRequestError && cause.status === 404
    error.value = getErrorMessage(cause)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

function goBack() {
  if (window.history.length > 1) {
    router.back()
    return
  }
  void router.push({ name: 'observability', query: route.query })
}

function stageAriaLabel(stage: (typeof waterfall.value.stages)[number]) {
  return `${STAGE_NAME_LABELS[stage.stageName]}，${STAGE_STATUS_LABELS[stage.status]}，开始偏移 ${formatDuration(stage.offsetMs)}，耗时 ${formatDuration(stage.elapsedMs)}`
}

watch(() => route.params.runId, loadDetail)
onMounted(loadDetail)
</script>

<template>
  <div class="trace-detail-page">
    <button class="back-link" type="button" @click="goBack">
      <el-icon><ArrowLeft /></el-icon>返回运行列表
    </button>

    <div v-if="loading" class="detail-loading" aria-label="正在加载运行详情">
      <i></i><i></i><i></i>
    </div>

    <section v-else-if="error" class="detail-error" role="alert">
      <el-icon><WarningFilled /></el-icon>
      <h1>{{ notFound ? '找不到这次问答运行' : '链路详情加载失败' }}</h1>
      <p>
        {{ notFound ? '记录可能已过保留期，或当前账号没有访问权限。' : error }}
      </p>
      <div>
        <el-button @click="goBack">返回列表</el-button>
        <el-button v-if="!notFound" type="primary" :icon="RefreshRight" @click="loadDetail">
          重新加载
        </el-button>
      </div>
    </section>

    <template v-else-if="detail">
      <header class="detail-heading">
        <div>
          <div class="heading-meta">
            <TraceStatusBadge :status="detail.run.status" />
            <span>{{ formatDateTime(detail.run.startedAt) }}</span>
          </div>
          <h1>运行 {{ shortId(detail.run.id) }}</h1>
          <p>
            请求 <code>{{ detail.run.requestId }}</code>
          </p>
          <p v-if="detail.run.errorCode" class="run-error-code">
            运行错误 <code>{{ detail.run.errorCode }}</code>
          </p>
        </div>
      </header>

      <section class="run-facts" aria-label="运行摘要">
        <div class="fact-primary">
          <span>总耗时</span>
          <strong>{{ formatDuration(detail.run.totalMs) }}</strong>
          <small>{{ EXECUTION_MODE_LABELS[detail.run.executionMode] }}</small>
        </div>
        <dl>
          <div>
            <dt>首字耗时（思考或正文）</dt>
            <dd>{{ formatDuration(detail.run.endToEndTtftMs) }}</dd>
          </div>
          <div>
            <dt>模型首字耗时（思考或正文）</dt>
            <dd>{{ formatDuration(detail.run.modelTtftMs) }}</dd>
          </div>
          <div>
            <dt>候选数</dt>
            <dd>{{ detail.run.candidateCount }}</dd>
          </div>
          <div>
            <dt>证据数</dt>
            <dd>{{ detail.run.evidenceCount }}</dd>
          </div>
          <div>
            <dt>最终模型</dt>
            <dd>
              {{
                detail.run.model
                  ? `${detail.run.provider ?? 'model'} / ${detail.run.model}`
                  : '未调用'
              }}
            </dd>
          </div>
          <div v-if="detail.run.displayName">
            <dt>所属用户</dt>
            <dd>{{ detail.run.displayName }} · {{ detail.run.username }}</dd>
          </div>
        </dl>
      </section>

      <section class="waterfall-panel" aria-labelledby="waterfall-title">
        <div class="section-heading">
          <div>
            <h2 id="waterfall-title">阶段瀑布</h2>
          </div>
          <strong>{{ formatDuration(waterfall.durationMs) }}</strong>
        </div>

        <div v-if="waterfall.stages.length" class="waterfall">
          <div class="waterfall-axis" aria-hidden="true">
            <span>阶段 / 顺序</span>
            <div>
              <i>0</i><i>25%</i><i>50%</i><i>75%</i
              ><i>{{ formatDuration(waterfall.durationMs) }}</i>
            </div>
            <span>耗时</span>
          </div>
          <article
            v-for="stage in waterfall.stages"
            :key="stage.id"
            class="waterfall-row"
            :class="`stage-${stage.status.toLowerCase()}`"
          >
            <div class="stage-name">
              <span>{{ String(stage.sequenceNo).padStart(2, '0') }}</span>
              <div>
                <strong>{{ STAGE_NAME_LABELS[stage.stageName] }}</strong>
                <small v-if="stage.subQuestionId">子问题 {{ shortId(stage.subQuestionId) }}</small>
              </div>
            </div>
            <div class="stage-lane">
              <span class="grid-line line-25"></span>
              <span class="grid-line line-50"></span>
              <span class="grid-line line-75"></span>
              <div
                class="stage-bar"
                :style="{
                  left: `${stage.leftPercent}%`,
                  width: `${stage.widthPercent}%`,
                }"
                :aria-label="stageAriaLabel(stage)"
                role="img"
              >
                <i></i>
              </div>
            </div>
            <div class="stage-duration">
              <strong>{{ formatDuration(stage.elapsedMs) }}</strong>
              <small>+{{ formatDuration(stage.offsetMs) }}</small>
            </div>
            <div class="stage-mobile-detail">
              <TraceStatusBadge :status="stage.status" />
              <span>偏移 +{{ formatDuration(stage.offsetMs) }}</span>
              <span>耗时 {{ formatDuration(stage.elapsedMs) }}</span>
            </div>
            <div
              v-if="stage.reasonCode || stage.errorCode || stage.ttftMs !== undefined"
              class="stage-note"
            >
              <code v-if="stage.reasonCode">{{ stage.reasonCode }}</code>
              <code v-if="stage.errorCode">{{ stage.errorCode }}</code>
              <span v-if="stage.ttftMs !== undefined"
                >首内容 {{ formatDuration(stage.ttftMs) }}</span
              >
            </div>
          </article>
        </div>
        <el-empty v-else description="暂无阶段记录" :image-size="72" />
      </section>

      <section
        v-if="detail.degradationReasons.length"
        class="degradation-panel"
        aria-labelledby="degradation-title"
      >
        <div class="section-heading compact">
          <div>
            <h2 id="degradation-title">降级原因</h2>
          </div>
        </div>
        <ul>
          <li v-for="reason in detail.degradationReasons" :key="reason">
            <code>{{ reason }}</code>
          </li>
        </ul>
      </section>

      <div class="detail-grid">
        <section class="attempt-panel" aria-labelledby="attempt-title">
          <div class="section-heading compact">
            <div>
              <h2 id="attempt-title">回答模型尝试</h2>
            </div>
            <strong>{{ modelAttempts.length }} 次</strong>
          </div>
          <div v-if="modelAttempts.length" class="detail-list">
            <article v-for="attempt in modelAttempts" :key="attempt.id">
              <div>
                <TraceStatusBadge :status="attempt.status" />
                <strong
                  >{{ attempt.provider ?? 'model' }} / {{ attempt.model ?? '未知模型' }}</strong
                >
              </div>
              <dl>
                <div>
                  <dt>原因</dt>
                  <dd>{{ attempt.reasonCode ?? '—' }}</dd>
                </div>
                <div>
                  <dt>TTFT</dt>
                  <dd>{{ formatDuration(attempt.ttftMs) }}</dd>
                </div>
                <div>
                  <dt>耗时</dt>
                  <dd>{{ formatDuration(attempt.elapsedMs) }}</dd>
                </div>
                <div>
                  <dt>错误</dt>
                  <dd>{{ attempt.errorCode ?? '—' }}</dd>
                </div>
              </dl>
            </article>
          </div>
          <p v-else class="quiet-empty">本次运行未调用回答模型。</p>
        </section>

        <section class="candidate-panel" aria-labelledby="candidate-title">
          <div class="section-heading compact">
            <div>
              <h2 id="candidate-title">候选流转</h2>
            </div>
          </div>
          <ol v-if="candidateStages.length" class="candidate-flow">
            <li v-for="stage in candidateStages" :key="stage.id">
              <span>{{ STAGE_NAME_LABELS[stage.stageName] }}</span>
              <strong>{{ stage.inputCount ?? '—' }} <i>→</i> {{ stage.outputCount ?? '—' }}</strong>
              <TraceStatusBadge :status="stage.status" />
            </li>
          </ol>
          <p v-else class="quiet-empty">本次运行没有候选处理阶段。</p>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped src="../components/observability/observability-detail.css"></style>
