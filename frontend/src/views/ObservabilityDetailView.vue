<script setup lang="ts">
import { ArrowLeft, RefreshRight, WarningFilled } from '@element-plus/icons-vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiRequestError, getErrorMessage, getRagRun, type RagRunDetail } from '../api'
import TraceStatusBadge from '../components/observability/TraceStatusBadge.vue'
import {
  buildWaterfall,
  buildTraceTree,
  initiallyExpanded,
  visibleTraceRows,
  reasonKind,
  reasonLabel,
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

const waterfall = computed(() => buildWaterfall(detail.value?.stages ?? [], detail.value?.run))
const tree = computed(() => buildTraceTree(waterfall.value.stages))
const expanded = ref(new Set<string>())
const selectedId = ref<string | null>(null)
const visibleRows = computed(() => visibleTraceRows(tree.value, expanded.value))
watch(tree, (roots) => {
  expanded.value = initiallyExpanded(roots)
  selectedId.value = null
})
function toggleStage(id: string) {
  const next = new Set(expanded.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  expanded.value = next
}
function expandAll() {
  expanded.value = new Set(waterfall.value.stages.map((stage) => stage.id))
}
const firstModelOffset = computed(() => {
  const attempts = waterfall.value.stages.filter(
    (stage) => stage.stageName === 'ANSWER_MODEL' && stage.status !== 'SKIPPED',
  )
  return attempts.length ? Math.min(...attempts.map((stage) => stage.offsetMs)) : null
})
const answerDuration = computed(() => {
  if (firstModelOffset.value == null) {
    return null
  }
  return waterfall.value.stages.find((stage) => stage.stageName === 'ANSWER')?.elapsedMs ?? null
})
const modelAttempts = computed(() =>
  (detail.value?.stages ?? []).filter(
    (stage) => stage.stageName === 'ANSWER_MODEL' && stage.status !== 'SKIPPED',
  ),
)
const degradationReasons = computed(
  () =>
    detail.value?.degradationReasonDetails ??
    (detail.value?.degradationReasons ?? []).map((reasonCode) => ({
      reasonCode,
      stageName: undefined,
      reasonLabel: undefined,
    })),
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
            <strong>{{ detail.run.errorMessage || '处理失败，请根据请求 ID 查询日志' }}</strong>
            <span
              >错误代码 <code>{{ detail.run.errorCode }}</code></span
            >
          </p>
        </div>
      </header>

      <section class="question-panel admin-card" aria-labelledby="question-title">
        <span id="question-title">提问问题</span>
        <p>{{ detail.run.question ?? '问题不可用' }}</p>
      </section>

      <section class="run-facts admin-card" aria-label="运行摘要">
        <div class="fact-primary">
          <span>总耗时</span>
          <strong>{{ formatDuration(detail.run.totalMs) }}</strong>
          <small>{{ EXECUTION_MODE_LABELS[detail.run.executionMode] }}</small>
        </div>
        <dl>
          <div>
            <dt>服务端首次发送（思考或正文）</dt>
            <dd>{{ formatDuration(detail.run.endToEndTtftMs) }}</dd>
          </div>
          <div>
            <dt>模型首字耗时（思考或正文）</dt>
            <dd>{{ formatDuration(detail.run.modelTtftMs) }}</dd>
          </div>
          <div>
            <dt>生成前准备</dt>
            <dd>{{ firstModelOffset == null ? '不适用' : formatDuration(firstModelOffset) }}</dd>
          </div>
          <div>
            <dt>回答生成（含重试与校验）</dt>
            <dd>{{ firstModelOffset == null ? '不适用' : formatDuration(answerDuration) }}</dd>
          </div>
          <div v-if="detail.run.firstReasoningMs != null">
            <dt>服务端首次发送思考</dt>
            <dd>{{ formatDuration(detail.run.firstReasoningMs) }}</dd>
          </div>
          <div v-if="detail.run.firstAnswerMs != null">
            <dt>服务端首次发送正文</dt>
            <dd>{{ formatDuration(detail.run.firstAnswerMs) }}</dd>
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

      <section class="waterfall-panel admin-card" aria-labelledby="waterfall-title">
        <div class="section-heading">
          <div>
            <h2 id="waterfall-title">阶段瀑布</h2>
          </div>
          <div class="trace-controls">
            <button type="button" @click="expandAll">全部展开</button>
            <button type="button" @click="expanded = new Set()">全部折叠</button>
            <strong>{{ formatDuration(waterfall.durationMs) }}</strong>
          </div>
        </div>

        <div v-if="waterfall.stages.length" class="waterfall">
          <div class="waterfall-axis" aria-hidden="true">
            <span>阶段 / 状态</span>
            <div>
              <i v-for="fraction in [0, 0.25, 0.5, 0.75, 1]" :key="fraction">{{
                formatDuration(waterfall.durationMs * fraction)
              }}</i>
            </div>
            <span>耗时</span>
          </div>
          <article
            v-for="stage in visibleRows"
            :key="stage.id"
            class="waterfall-row"
            :class="`stage-${stage.status.toLowerCase()}`"
          >
            <div class="stage-name" :style="{ paddingLeft: `${Math.min(stage.depth, 6) * 14}px` }">
              <button
                v-if="stage.children.length"
                type="button"
                class="tree-toggle"
                :aria-label="`${expanded.has(stage.id) ? '折叠' : '展开'}${STAGE_NAME_LABELS[stage.stageName]}`"
                :aria-expanded="expanded.has(stage.id)"
                @click="toggleStage(stage.id)"
              >
                <span aria-hidden="true">{{ expanded.has(stage.id) ? '−' : '+' }}</span>
              </button>
              <span v-else class="tree-spacer" aria-hidden="true"></span>
              <div>
                <button
                  class="stage-select"
                  type="button"
                  :aria-expanded="selectedId === stage.id"
                  :aria-controls="`stage-detail-${stage.id}`"
                  @click="selectedId = selectedId === stage.id ? null : stage.id"
                >
                  {{ STAGE_NAME_LABELS[stage.stageName] }}
                  <small v-if="stage.subQuestionId">{{ stage.subQuestionId }}</small>
                </button>
                <TraceStatusBadge :status="stage.status" />
              </div>
            </div>
            <div class="stage-lane">
              <span class="grid-line line-25"></span>
              <span class="grid-line line-50"></span>
              <span class="grid-line line-75"></span>
              <div
                class="stage-bar"
                :class="{ 'stage-event': stage.status === 'SKIPPED' }"
                :title="
                  stage.elapsedMs === 0 && stage.status !== 'SKIPPED'
                    ? '毫秒精度；标记宽度不代表实际耗时'
                    : stageAriaLabel(stage)
                "
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
              <strong>{{
                stage.status === 'SKIPPED' ? '未执行' : formatDuration(stage.elapsedMs)
              }}</strong>
              <small v-if="stage.elapsedMs === 0 && stage.status !== 'SKIPPED'">毫秒精度</small>
              <small>+{{ formatDuration(stage.offsetMs) }}</small>
            </div>
            <div class="stage-mobile-detail">
              <TraceStatusBadge :status="stage.status" />
              <span>偏移 +{{ formatDuration(stage.offsetMs) }}</span>
              <span>{{
                stage.status === 'SKIPPED' ? '未执行' : `耗时 ${formatDuration(stage.elapsedMs)}`
              }}</span>
              <span v-if="stage.elapsedMs === 0 && stage.status !== 'SKIPPED'">毫秒精度</span>
            </div>
            <div
              v-if="stage.reasonCode || stage.errorCode || stage.ttftMs != null"
              class="stage-note"
            >
              <span v-if="stage.reasonCode">{{ reasonKind(stage) }}：{{ reasonLabel(stage) }}</span>
              <span v-if="stage.errorCode">
                {{ stage.errorMessage || '处理失败，请根据请求 ID 查询日志' }}
                <code>{{ stage.errorCode }}</code>
              </span>
              <span v-if="stage.ttftMs != null">首内容 {{ formatDuration(stage.ttftMs) }}</span>
            </div>
            <dl
              v-if="selectedId === stage.id"
              :id="`stage-detail-${stage.id}`"
              class="stage-inspector"
            >
              <div>
                <dt>开始偏移</dt>
                <dd>+{{ formatDuration(stage.offsetMs) }}</dd>
              </div>
              <div>
                <dt>耗时</dt>
                <dd>
                  {{ stage.status === 'SKIPPED' ? '未执行' : formatDuration(stage.elapsedMs) }}
                </dd>
              </div>
              <div v-if="stage.inputCount != null">
                <dt>输入数</dt>
                <dd>{{ stage.inputCount }}</dd>
              </div>
              <div v-if="stage.outputCount != null">
                <dt>输出数</dt>
                <dd>{{ stage.outputCount }}</dd>
              </div>
              <div v-if="stage.model">
                <dt>模型</dt>
                <dd>{{ stage.provider }} / {{ stage.model }}</dd>
              </div>
              <div v-if="stage.queueMs != null">
                <dt>排队耗时</dt>
                <dd>{{ formatDuration(stage.queueMs) }}</dd>
              </div>
              <div v-if="stage.attemptIndex != null">
                <dt>模型尝试</dt>
                <dd>第 {{ stage.attemptIndex }} 次</dd>
              </div>
              <div v-if="stage.ttftMs != null">
                <dt>模型首内容</dt>
                <dd>{{ formatDuration(stage.ttftMs) }}</dd>
              </div>
              <div v-if="stage.firstReasoningMs != null">
                <dt>模型首次思考</dt>
                <dd>{{ formatDuration(stage.firstReasoningMs) }}</dd>
              </div>
              <div v-if="stage.firstAnswerMs != null">
                <dt>模型首次正文</dt>
                <dd>{{ formatDuration(stage.firstAnswerMs) }}</dd>
              </div>
              <div v-if="stage.reasonCode">
                <dt>{{ reasonKind(stage) }}</dt>
                <dd>
                  {{ reasonLabel(stage) }} <code>{{ stage.reasonCode }}</code>
                </dd>
              </div>
              <div v-if="stage.errorCode">
                <dt>错误</dt>
                <dd>
                  {{ stage.errorMessage }} <code>{{ stage.errorCode }}</code>
                </dd>
              </div>
            </dl>
          </article>
        </div>
        <el-empty v-else description="暂无阶段记录" :image-size="72" />
      </section>

      <section
        v-if="degradationReasons.length"
        class="degradation-panel admin-card"
        aria-labelledby="degradation-title"
      >
        <div class="section-heading compact">
          <div>
            <h2 id="degradation-title">降级原因</h2>
          </div>
        </div>
        <ul>
          <li
            v-for="reason in degradationReasons"
            :key="`${reason.stageName}:${reason.reasonCode}:${reason.reasonLabel}`"
          >
            <span v-if="reason.stageName">{{ STAGE_NAME_LABELS[reason.stageName] }}： </span>
            {{ reasonLabel(reason) }} <code>{{ reason.reasonCode }}</code>
          </li>
        </ul>
      </section>

      <div class="detail-grid">
        <section class="attempt-panel admin-card" aria-labelledby="attempt-title">
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
                  <dt>{{ reasonKind(attempt) }}</dt>
                  <dd :title="attempt.reasonCode ? reasonLabel(attempt) : undefined">
                    {{ attempt.reasonCode ? reasonLabel(attempt) : '—' }}
                    <code v-if="attempt.reasonCode">{{ attempt.reasonCode }}</code>
                  </dd>
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
                  <dd v-if="attempt.errorCode">
                    {{ attempt.errorMessage || '处理失败，请根据请求 ID 查询日志' }}
                    <code>{{ attempt.errorCode }}</code>
                  </dd>
                  <dd v-else>—</dd>
                </div>
              </dl>
            </article>
          </div>
          <p v-else class="quiet-empty">本次运行未调用回答模型。</p>
        </section>

        <section class="candidate-panel admin-card" aria-labelledby="candidate-title">
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
