<script setup lang="ts">
import { ArrowRight, Refresh, Search, WarningFilled } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import { getErrorMessage, listAdminUsers, type AuthUser, type RagExecutionMode } from '../api'
import TraceStatusBadge from '../components/observability/TraceStatusBadge.vue'
import {
  EXECUTION_MODE_LABELS,
  formatDateTime,
  formatDuration,
  formatRate,
  RUN_STATUS_LABELS,
} from '../components/observability/tracePresentation'
import { useRagRunObservability } from '../components/observability/useRagRunObservability'

const route = useRoute()
const {
  aggregate,
  applyFilters,
  changePage,
  changePageSize,
  draft,
  error,
  isAdmin,
  loading,
  page,
  pageSize,
  refresh,
  resetFilters,
  runs,
  total,
  validationError,
} = useRagRunObservability()

const users = ref<AuthUser[]>([])
const usersLoading = ref(false)
let userRequestSequence = 0

const userOptions = computed(() => {
  const merged = new Map(users.value.map((user) => [user.id, user]))
  runs.value.forEach((run) => {
    if (!run.ownerId || merged.has(run.ownerId)) return
    merged.set(run.ownerId, {
      id: run.ownerId,
      username: run.username ?? run.ownerId,
      displayName: run.displayName ?? run.username ?? '未知用户',
      role: 'USER',
      enabled: true,
      lastLoginAt: null,
      createdAt: run.startedAt,
      updatedAt: run.startedAt,
    })
  })
  return [...merged.values()]
})

const latencyRows = computed(() => [
  { label: '总耗时', value: aggregate.value?.totalMs },
  { label: '端到端 TTFT', value: aggregate.value?.endToEndTtftMs },
  { label: '模型 TTFT', value: aggregate.value?.modelTtftMs },
])

async function searchUsers(query = '') {
  if (!isAdmin.value) return
  const sequence = ++userRequestSequence
  usersLoading.value = true
  try {
    const result = await listAdminUsers(1, 50, query)
    if (sequence === userRequestSequence) users.value = result.items
  } catch (cause) {
    if (sequence === userRequestSequence) console.warn(getErrorMessage(cause))
  } finally {
    if (sequence === userRequestSequence) usersLoading.value = false
  }
}

function detailLink(runId: string) {
  return { name: 'observability-detail', params: { runId }, query: route.query }
}

function executionModeLabel(mode: RagExecutionMode) {
  return EXECUTION_MODE_LABELS[mode]
}

onMounted(() => {
  if (isAdmin.value) void searchUsers()
})
</script>

<template>
  <div class="observability-page">
    <header class="trace-heading">
      <div>
        <span class="eyebrow">RAG RUN LEDGER</span>
        <h1>问答链路追踪</h1>
        <p>每行对应一次回答版本，沿阶段轨迹定位等待、降级和失败。</p>
      </div>
      <div class="heading-actions">
        <span class="privacy-note">仅记录安全元数据</span>
        <el-button :icon="Refresh" :loading="loading" @click="refresh">刷新数据</el-button>
      </div>
    </header>

    <section class="telemetry-board" aria-label="当前筛选范围汇总">
      <div class="request-volume">
        <span>问答运行</span>
        <strong>{{ aggregate?.requestCount ?? '—' }}</strong>
        <small>当前时间范围</small>
      </div>
      <div class="rate-ledger">
        <div class="rate-row">
          <span>成功率</span>
          <div class="rate-track" aria-hidden="true">
            <i :style="{ width: `${(aggregate?.successRate ?? 0) * 100}%` }"></i>
          </div>
          <strong>{{ aggregate ? formatRate(aggregate.successRate) : '—' }}</strong>
        </div>
        <div class="rate-row degraded-rate">
          <span>降级率</span>
          <div class="rate-track" aria-hidden="true">
            <i :style="{ width: `${(aggregate?.degradedRate ?? 0) * 100}%` }"></i>
          </div>
          <strong>{{ aggregate ? formatRate(aggregate.degradedRate) : '—' }}</strong>
        </div>
      </div>
      <div class="latency-ledger">
        <div class="latency-head"><span>延迟指标</span><span>P50</span><span>P95</span></div>
        <div v-for="item in latencyRows" :key="item.label" class="latency-row">
          <span>{{ item.label }}</span>
          <strong>{{ formatDuration(item.value?.p50Ms) }}</strong>
          <strong>{{ formatDuration(item.value?.p95Ms) }}</strong>
        </div>
      </div>
    </section>

    <section class="filter-panel" aria-labelledby="trace-filter-title">
      <div class="filter-intro">
        <div>
          <span id="trace-filter-title">筛选运行</span>
          <small>默认查看最近 24 小时</small>
        </div>
        <el-button text @click="resetFilters">恢复默认</el-button>
      </div>
      <div class="filter-grid">
        <label class="filter-field">
          <span>时间范围</span>
          <el-select v-model="draft.range" aria-label="时间范围">
            <el-option label="最近 1 小时" value="1h" />
            <el-option label="最近 24 小时" value="24h" />
            <el-option label="最近 7 天" value="7d" />
            <el-option label="自定义" value="custom" />
          </el-select>
        </label>
        <template v-if="draft.range === 'custom'">
          <label class="filter-field">
            <span>开始时间（包含）</span>
            <input v-model="draft.customFrom" type="datetime-local" />
          </label>
          <label class="filter-field">
            <span>结束时间（不包含）</span>
            <input v-model="draft.customTo" type="datetime-local" />
          </label>
        </template>
        <label class="filter-field">
          <span>运行状态</span>
          <el-select v-model="draft.status" clearable placeholder="全部状态" aria-label="运行状态">
            <el-option
              v-for="(label, value) in RUN_STATUS_LABELS"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </label>
        <label class="filter-field">
          <span>执行模式</span>
          <el-select
            v-model="draft.executionMode"
            clearable
            placeholder="全部模式"
            aria-label="执行模式"
          >
            <el-option
              v-for="(label, value) in EXECUTION_MODE_LABELS"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </label>
        <label class="filter-field">
          <span>模型名称</span>
          <el-input
            v-model="draft.model"
            clearable
            placeholder="精确匹配历史模型"
            :prefix-icon="Search"
            @keyup.enter="applyFilters"
          />
        </label>
        <label v-if="isAdmin" class="filter-field">
          <span>所属用户</span>
          <el-select
            v-model="draft.userId"
            clearable
            filterable
            remote
            reserve-keyword
            placeholder="全部用户"
            :remote-method="searchUsers"
            :loading="usersLoading"
            aria-label="所属用户"
          >
            <el-option
              v-for="user in userOptions"
              :key="user.id"
              :value="user.id"
              :label="`${user.displayName} · ${user.username}`"
            />
          </el-select>
        </label>
        <div class="filter-submit">
          <el-button type="primary" @click="applyFilters">应用筛选</el-button>
        </div>
      </div>
      <p v-if="validationError" class="validation-error" role="alert">{{ validationError }}</p>
    </section>

    <section class="runs-panel" aria-labelledby="run-list-title">
      <div class="panel-heading">
        <div>
          <span class="panel-index">RUNS</span>
          <h2 id="run-list-title">每次问答请求</h2>
        </div>
        <span>{{ total }} 条记录</span>
      </div>

      <div v-if="error" class="error-state" role="alert">
        <el-icon><WarningFilled /></el-icon>
        <div>
          <strong>运行记录加载失败</strong><span>{{ error }}</span>
        </div>
        <el-button @click="refresh">重新加载</el-button>
      </div>

      <template v-else>
        <div v-if="loading && runs.length === 0" class="run-loading" aria-label="正在加载运行记录">
          <span v-for="index in 5" :key="index"><i></i><i></i><i></i><i></i></span>
        </div>

        <template v-else>
          <div class="desktop-run-table" :class="{ 'is-loading': loading }">
            <el-table :data="runs" table-layout="fixed" row-key="id">
              <el-table-column label="开始时间" width="154">
                <template #default="{ row }">
                  <span class="data-text">{{ formatDateTime(row.startedAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <span class="run-status-cell">
                    <TraceStatusBadge :status="row.status" />
                    <small v-if="row.degraded">含降级</small>
                  </span>
                </template>
              </el-table-column>
              <el-table-column v-if="isAdmin" label="用户" min-width="130">
                <template #default="{ row }">
                  <span class="owner-cell"
                    ><strong>{{ row.displayName ?? '—' }}</strong
                    ><small>{{ row.username }}</small></span
                  >
                </template>
              </el-table-column>
              <el-table-column label="路径 / 模型" min-width="190">
                <template #default="{ row }">
                  <span class="model-cell">
                    <strong>{{ executionModeLabel(row.executionMode) }}</strong>
                    <small>{{
                      row.model ? `${row.provider ?? 'model'} / ${row.model}` : '未调用回答模型'
                    }}</small>
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="候选 → 证据" width="118" align="center">
                <template #default="{ row }"
                  ><span class="data-text"
                    >{{ row.candidateCount }} → {{ row.evidenceCount }}</span
                  ></template
                >
              </el-table-column>
              <el-table-column label="端到端 TTFT" width="130" align="right">
                <template #default="{ row }"
                  ><span class="data-text">{{ formatDuration(row.endToEndTtftMs) }}</span></template
                >
              </el-table-column>
              <el-table-column label="总耗时" width="110" align="right">
                <template #default="{ row }"
                  ><span class="data-text">{{ formatDuration(row.totalMs) }}</span></template
                >
              </el-table-column>
              <el-table-column label="" width="112" align="right">
                <template #default="{ row }">
                  <RouterLink class="detail-link" :to="detailLink(row.id)"
                    >查看链路 <el-icon><ArrowRight /></el-icon
                  ></RouterLink>
                </template>
              </el-table-column>
              <template #empty>
                <el-empty description="当前筛选范围内没有问答运行" :image-size="72" />
              </template>
            </el-table>
          </div>

          <div class="mobile-run-list" :class="{ 'is-loading': loading }">
            <article v-for="run in runs" :key="run.id" class="run-card">
              <div class="run-card-head">
                <span class="run-status-cell">
                  <TraceStatusBadge :status="run.status" />
                  <small v-if="run.degraded">含降级</small>
                </span>
                <time>{{ formatDateTime(run.startedAt) }}</time>
              </div>
              <strong>{{ executionModeLabel(run.executionMode) }}</strong>
              <span class="mobile-model">{{ run.model ?? '未调用回答模型' }}</span>
              <div class="run-card-metrics">
                <span><small>总耗时</small>{{ formatDuration(run.totalMs) }}</span>
                <span><small>端到端 TTFT</small>{{ formatDuration(run.endToEndTtftMs) }}</span>
                <span
                  ><small>候选 → 证据</small>{{ run.candidateCount }} →
                  {{ run.evidenceCount }}</span
                >
              </div>
              <div v-if="isAdmin" class="mobile-owner">
                {{ run.displayName }} · {{ run.username }}
              </div>
              <RouterLink class="detail-link" :to="detailLink(run.id)"
                >查看链路 <el-icon><ArrowRight /></el-icon
              ></RouterLink>
            </article>
            <el-empty v-if="runs.length === 0" description="当前筛选范围内没有问答运行" />
          </div>
        </template>

        <footer v-if="total > 0" class="pagination-footer">
          <el-pagination
            :current-page="page"
            :page-size="pageSize"
            :page-sizes="[10, 20, 50]"
            :total="total"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @update:current-page="changePage"
            @update:page-size="changePageSize"
          />
        </footer>
      </template>
    </section>
  </div>
</template>

<style scoped src="../components/observability/observability-list.css"></style>
