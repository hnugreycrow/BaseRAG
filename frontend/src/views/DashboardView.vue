<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Refresh, ArrowRight } from '@element-plus/icons-vue'
import { ElRadioButton, ElSkeleton } from 'element-plus'
import { getDashboardTrend, getErrorMessage, type DashboardTrend } from '../api'
import TrendChart from '../components/dashboard/TrendChart.vue'
import { comparison, dashboardRange, latencyValues } from '../components/dashboard/trend'

const period = ref(7)
const metric = ref<'ttft' | 'total'>('ttft')
const data = ref<DashboardTrend | null>(null)
const loading = ref(false)
const error = ref('')
const range = ref(dashboardRange(7))
let sequence = 0
async function load() {
  const current = ++sequence
  range.value = dashboardRange(period.value)
  loading.value = true
  error.value = ''
  data.value = null
  try {
    const result = await getDashboardTrend(range.value.from, range.value.to)
    if (current === sequence) {
      data.value = result
    }
  } catch (cause) {
    if (current === sequence) {
      error.value = getErrorMessage(cause)
    }
  } finally {
    if (current === sequence) {
      loading.value = false
    }
  }
}
watch(period, load, { immediate: true })
onBeforeUnmount(() => {
  sequence++
})
const rows = computed(() => data.value?.days ?? [])
const dates = computed(() => rows.value.map((row) => row.date))
const requests = computed(() => rows.value.reduce((sum, row) => sum + row.requestCount, 0))
const failures = computed(() => rows.value.reduce((sum, row) => sum + row.failureCount, 0))
const failureRate = computed(() =>
  requests.value ? `${((failures.value / requests.value) * 100).toFixed(1)}%` : '—',
)
const cards = computed(() => [
  {
    key: 'requests',
    title: '问答请求量',
    value: requests.value,
    previous: data.value?.previousRequestCount ?? null,
    color: '#3478f6',
    series: [
      { name: '问答请求量', values: rows.value.map((row) => row.requestCount), color: '#3478f6' },
    ],
  },
  {
    key: 'failures',
    title: '失败请求量',
    value: failures.value,
    previous: data.value?.previousFailureCount ?? null,
    color: '#e8794c',
    series: [
      { name: '失败请求量', values: rows.value.map((row) => row.failureCount), color: '#e8794c' },
    ],
  },
])
const latencySeries = computed(() => [
  {
    name: 'P50 · 中位数',
    values: latencyValues(rows.value, metric.value, 'P50'),
    color: '#3478f6',
  },
  {
    name: 'P95 · 慢请求',
    values: latencyValues(rows.value, metric.value, 'P95'),
    color: '#8b7bd8',
  },
])
const sampleCount = computed(() =>
  rows.value.reduce(
    (sum, row) => sum + row[metric.value === 'ttft' ? 'ttftSampleCount' : 'totalSampleCount'],
    0,
  ),
)
function seconds(value: number | null) {
  return value == null ? '—' : `${(value / 1000).toFixed(2)} 秒`
}
</script>

<template>
  <div class="dashboard-page">
    <header class="page-heading">
      <div>
        <h1>Dashboard</h1>
      </div>
      <div class="toolbar">
        <span class="date-range">{{ range.from }} — {{ range.to }}</span>
        <el-select v-model="period" aria-label="统计时间范围" class="period-select"
          ><el-option label="最近 7 天" :value="7" /><el-option label="最近 30 天" :value="30"
        /></el-select>
        <el-button :icon="Refresh" :loading="loading" aria-label="刷新统计" @click="load"
          >刷新</el-button
        >
      </div>
    </header>
    <div v-if="error" class="state-panel" role="alert">
      <h2>统计暂时无法加载</h2>
      <p>{{ error }}</p>
      <el-button @click="load">重新加载</el-button>
    </div>
    <div v-else-if="loading" class="state-panel" role="status">
      <el-skeleton :rows="8" animated />
      <p>正在加载运行趋势…</p>
    </div>
    <template v-else-if="data">
      <div class="count-grid">
        <section v-for="card in cards" :key="card.key" class="chart-card">
          <div class="card-heading">
            <h2>{{ card.title }}</h2>
            <span class="series-dot" :style="{ background: card.color }" aria-hidden="true" />
          </div>
          <div class="count-content">
            <div class="metric-summary">
              <strong class="metric-value">{{ card.value.toLocaleString() }}</strong>
              <div v-if="card.previous !== null" class="comparison">
                <span :class="{ warning: card.key === 'failures' && card.value > card.previous }">{{
                  comparison(card.value, card.previous)
                }}</span
                ><small>较上期</small>
              </div>
              <p v-if="card.previous !== null" class="previous">
                上期 {{ card.previous.toLocaleString() }} 次
              </p>
              <p v-else class="previous">上期超出保留期，暂无环比</p>
              <p v-if="card.key === 'failures'" class="rate">
                失败率 <b>{{ failureRate }}</b>
              </p>
              <p v-else class="rate">全部请求状态</p>
            </div>
            <TrendChart
              :dates="dates"
              :series="card.series"
              unit="次"
              :label="`${card.title}每日趋势`"
            />
          </div>
          <p class="card-note">
            {{
              card.key === 'requests'
                ? '提问与重新生成各计一次，内部重试不重复计数。'
                : '只计算失败请求，不包含主动取消和重启中断。'
            }}
          </p>
        </section>
      </div>
      <section class="chart-card latency-card">
        <div class="card-heading">
          <div>
            <h2>响应耗时</h2>
            <p class="subtitle">了解典型体验，也关注慢请求。</p>
          </div>
          <el-radio-group v-model="metric" aria-label="耗时指标"
            ><el-radio-button value="ttft">首字耗时</el-radio-button
            ><el-radio-button value="total">总耗时</el-radio-button></el-radio-group
          >
        </div>
        <div class="latency-meta">
          <span
            ><b>{{ sampleCount.toLocaleString() }}</b> 个有效样本</span
          ><span>仅统计成功请求 · 单位：秒</span>
        </div>
        <TrendChart
          v-if="sampleCount"
          :dates="dates"
          :series="latencySeries"
          :samples="
            rows.map((row) => (metric === 'ttft' ? row.ttftSampleCount : row.totalSampleCount))
          "
          unit="秒"
          :label="`${metric === 'ttft' ? '首字' : '总'}耗时每日 P50 和 P95 趋势`"
        />
        <div v-else class="empty-chart">
          <span class="empty-line" />
          <p>暂无成功请求的耗时样本</p>
          <small>完成问答后，趋势会显示在这里。</small>
        </div>
        <div class="latency-footer">
          <p class="card-note">
            P50 为中位数，95% 的有效样本耗时不超过 P95。首字包含首次思考或正文；无样本日期留空。
          </p>
          <RouterLink to="/admin/observability"
            >查看链路 <el-icon><ArrowRight /></el-icon
          ></RouterLink>
        </div>
      </section>
      <details class="data-details">
        <summary>查看每日数据</summary>
        <div class="table-scroll">
          <table>
            <caption>
              每日统计，耗时仅含成功请求
            </caption>
            <thead>
              <tr>
                <th>日期</th>
                <th>问答量</th>
                <th>失败量</th>
                <th>首字样本</th>
                <th>首字 P50</th>
                <th>首字 P95</th>
                <th>总耗时样本</th>
                <th>总耗时 P50</th>
                <th>总耗时 P95</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.date">
                <th scope="row">{{ row.date }}</th>
                <td>{{ row.requestCount }}</td>
                <td>{{ row.failureCount }}</td>
                <td>{{ row.ttftSampleCount }}</td>
                <td>{{ seconds(row.ttftP50Ms) }}</td>
                <td>{{ seconds(row.ttftP95Ms) }}</td>
                <td>{{ row.totalSampleCount }}</td>
                <td>{{ seconds(row.totalP50Ms) }}</td>
                <td>{{ seconds(row.totalP95Ms) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>
      <p class="page-note">
        按自然日统计，包含今天；今日数据尚未完整。环比对照上一等长自然日周期，历史数据受链路保留期限影响。
      </p>
    </template>
  </div>
</template>

<style scoped>
.dashboard-page {
  max-width: 1600px;
  margin: 0 auto;
  padding: 30px;
  color: #253147;
}
.page-heading,
.toolbar,
.card-heading,
.latency-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.page-heading {
  margin-bottom: 26px;
  flex-wrap: wrap;
}
h1 {
  margin: 0;
  font-size: 28px;
  font-weight: 650;
  letter-spacing: -0.8px;
}
h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.subtitle {
  margin: 8px 0 0;
  color: #64748b;
  font-size: 12px;
}
.toolbar {
  gap: 10px;
  flex-wrap: wrap;
}
.date-range {
  font-size: 12px;
  color: #64748b;
  font-variant-numeric: tabular-nums;
}
.period-select {
  width: 125px;
}
.count-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}
.chart-card {
  min-width: 0;
  background: #fff;
  border: 1px solid #e8edf3;
  border-radius: 18px;
  padding: 24px;
  box-shadow: 0 2px 5px #24365303;
}
.series-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.count-content {
  display: grid;
  grid-template-columns: minmax(105px, 0.65fr) minmax(0, 2fr);
  align-items: center;
  margin-top: 14px;
}
.metric-value {
  display: block;
  font-size: clamp(26px, 2.5vw, 38px);
  letter-spacing: -1px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.comparison {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  font-size: 11px;
}
.comparison > span {
  background: #edf3ff;
  color: #376bba;
  padding: 4px 6px;
  border-radius: 5px;
}
.comparison > .warning {
  background: #fff0e8;
  color: #b95730;
}
.comparison small,
.previous {
  color: #64748b;
  font-size: 11px;
}
.previous {
  margin-top: 8px;
}
.rate {
  color: #64748b;
  font-size: 11px;
  margin-top: 20px;
}
.rate b {
  color: #253147;
  margin-left: 4px;
}
.card-note {
  margin: 0;
  color: #64748b;
  font-size: 11px;
  line-height: 1.8;
}
.latency-card {
  margin-top: 20px;
}
.latency-meta {
  display: flex;
  gap: 18px;
  margin: 22px 0 6px;
  color: #64748b;
  font-size: 12px;
}
.latency-meta b {
  color: #253147;
  font-size: 16px;
  font-weight: 600;
}
.latency-card :deep(.trend-chart) {
  height: 280px;
}
.latency-footer {
  border-top: 1px solid #f0f2f6;
  padding-top: 16px;
  margin-top: 16px;
}
.latency-footer a {
  display: flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
  font-size: 12px;
  color: #3478f6;
  text-decoration: none;
}
.page-note {
  color: #64748b;
  font-size: 11px;
  line-height: 1.8;
}
.state-panel {
  padding: 40px;
  background: white;
  border-radius: 18px;
  color: #64748b;
}
.empty-chart {
  height: 280px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #64748b;
  font-size: 13px;
}
.empty-line {
  width: 60px;
  border-top: 2px dashed #ced9e8;
}
.data-details {
  margin-top: 20px;
  color: #64748b;
  font-size: 12px;
}
summary {
  cursor: pointer;
  width: fit-content;
  padding: 8px 0;
}
.table-scroll {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  background: white;
  white-space: nowrap;
}
caption {
  text-align: left;
  padding: 12px;
}
th,
td {
  text-align: right;
  padding: 12px;
  border-bottom: 1px solid #edf0f4;
  font-variant-numeric: tabular-nums;
}
@media (max-width: 1200px) {
  .count-content {
    grid-template-columns: 1fr;
  }
  .metric-summary {
    padding: 20px 0 0;
  }
  .rate {
    margin-top: 10px;
  }
}
@media (max-width: 700px) {
  .dashboard-page {
    padding: 20px 16px;
  }
  .count-grid {
    grid-template-columns: 1fr;
  }
  .chart-card {
    padding: 20px 16px;
  }
  .card-heading,
  .latency-footer {
    flex-wrap: wrap;
  }
  .date-range {
    width: 100%;
  }
  .latency-meta {
    flex-wrap: wrap;
    gap: 8px;
  }
}
</style>
