import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'

import {
  getErrorMessage,
  listRagRuns,
  summarizeRagRuns,
  type RagExecutionMode,
  type RagRunAggregate,
  type RagRunFilters,
  type RagRunStatus,
  type RagRunSummary,
} from '../../api'
import { useAuthStore } from '../../store/auth'

/** 列表页支持的快捷时间范围。 */
export type TraceRange = '1h' | '24h' | '7d' | 'custom'

interface FilterState {
  range: TraceRange
  customFrom: string
  customTo: string
  status: RagRunStatus | ''
  model: string
  executionMode: RagExecutionMode | ''
  userId: string
}

const RUN_STATUSES: RagRunStatus[] = ['RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED']
const EXECUTION_MODES: RagExecutionMode[] = ['FULL_PIPELINE', 'SYSTEM_CHAT', 'FAST_PATH']
const RANGE_VALUES: TraceRange[] = ['1h', '24h', '7d', 'custom']

function queryString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function localInputValue(value: Date): string {
  const offset = value.getTimezoneOffset() * 60_000
  return new Date(value.getTime() - offset).toISOString().slice(0, 16)
}

function safeLocalInputValue(rawValue: unknown, fallback: Date): string {
  const parsed = new Date(queryString(rawValue))
  return localInputValue(Number.isFinite(parsed.getTime()) ? parsed : fallback)
}

function parseFilterState(query: Record<string, unknown>): FilterState {
  const now = new Date()
  const from = new Date(now.getTime() - 24 * 60 * 60 * 1000)
  const rawRange = queryString(query.range) as TraceRange
  const rawStatus = queryString(query.status) as RagRunStatus
  const rawMode = queryString(query.executionMode) as RagExecutionMode

  return {
    range: RANGE_VALUES.includes(rawRange) ? rawRange : '24h',
    customFrom: query.from ? safeLocalInputValue(query.from, from) : localInputValue(from),
    customTo: query.to ? safeLocalInputValue(query.to, now) : localInputValue(now),
    status: RUN_STATUSES.includes(rawStatus) ? rawStatus : '',
    model: queryString(query.model),
    executionMode: EXECUTION_MODES.includes(rawMode) ? rawMode : '',
    userId: queryString(query.userId),
  }
}

function copyFilters(target: FilterState, source: FilterState) {
  Object.assign(target, source)
}

function timeWindow(state: FilterState): { from: string; to: string } | null {
  const now = new Date()
  if (state.range === 'custom') {
    const from = new Date(state.customFrom)
    const to = new Date(state.customTo)
    if (!Number.isFinite(from.getTime()) || !Number.isFinite(to.getTime()) || from >= to)
      return null
    return { from: from.toISOString(), to: to.toISOString() }
  }

  const hours = state.range === '1h' ? 1 : state.range === '7d' ? 7 * 24 : 24
  return {
    from: new Date(now.getTime() - hours * 60 * 60 * 1000).toISOString(),
    to: now.toISOString(),
  }
}

/**
 * 管理运行列表筛选、汇总和分页。
 *
 * 普通用户的请求不会携带 userId，所有权仍由服务端强制约束。
 */
export function useRagRunObservability() {
  const route = useRoute()
  const router = useRouter()
  const auth = useAuthStore()
  const initial = parseFilterState(route.query)
  const draft = reactive<FilterState>({ ...initial })
  const applied = reactive<FilterState>({ ...initial })
  const runs = ref<RagRunSummary[]>([])
  const aggregate = ref<RagRunAggregate | null>(null)
  const total = ref(0)
  const page = ref(Math.max(1, Number.parseInt(queryString(route.query.page), 10) || 1))
  const pageSize = ref(
    [10, 20, 50].includes(Number(route.query.pageSize)) ? Number(route.query.pageSize) : 20,
  )
  const loading = ref(false)
  const error = ref('')
  const validationError = ref('')
  const isAdmin = computed(() => auth.user?.role === 'ADMIN')
  let requestSequence = 0
  let disposed = false

  function requestFilters(): RagRunFilters | null {
    const window = timeWindow(applied)
    if (!window) return null
    return {
      ...window,
      ...(applied.status ? { status: applied.status } : {}),
      ...(applied.model.trim() ? { model: applied.model.trim() } : {}),
      ...(applied.executionMode ? { executionMode: applied.executionMode } : {}),
      ...(isAdmin.value && applied.userId ? { userId: applied.userId } : {}),
    }
  }

  async function load() {
    const filters = requestFilters()
    if (!filters) {
      validationError.value = '结束时间必须晚于开始时间'
      return
    }

    const sequence = ++requestSequence
    loading.value = true
    error.value = ''
    validationError.value = ''
    try {
      const [pageResult, summary] = await Promise.all([
        listRagRuns(filters, page.value, pageSize.value),
        summarizeRagRuns(filters),
      ])
      // 用户快速切换筛选时，较早请求即使后返回也不能覆盖新结果。
      if (disposed || sequence !== requestSequence) return
      runs.value = pageResult.items
      total.value = pageResult.total
      aggregate.value = summary
    } catch (cause) {
      if (disposed || sequence !== requestSequence) return
      error.value = getErrorMessage(cause)
      runs.value = []
      total.value = 0
      aggregate.value = null
    } finally {
      if (!disposed && sequence === requestSequence) loading.value = false
    }
  }

  function routeQuery(): LocationQueryRaw {
    return {
      range: applied.range,
      ...(applied.range === 'custom'
        ? {
            from: new Date(applied.customFrom).toISOString(),
            to: new Date(applied.customTo).toISOString(),
          }
        : {}),
      ...(applied.status ? { status: applied.status } : {}),
      ...(applied.model.trim() ? { model: applied.model.trim() } : {}),
      ...(applied.executionMode ? { executionMode: applied.executionMode } : {}),
      ...(isAdmin.value && applied.userId ? { userId: applied.userId } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {}),
      ...(pageSize.value !== 20 ? { pageSize: String(pageSize.value) } : {}),
    }
  }

  async function syncRouteAndLoad() {
    const previousPath = route.fullPath
    await router.replace({ query: routeQuery() })
    if (route.fullPath === previousPath) await load()
  }

  /** 应用草稿筛选并回到第一页。 */
  async function applyFilters() {
    if (!timeWindow(draft)) {
      validationError.value = '结束时间必须晚于开始时间'
      return
    }
    copyFilters(applied, draft)
    page.value = 1
    await syncRouteAndLoad()
  }

  /** 恢复默认最近 24 小时筛选。 */
  async function resetFilters() {
    const defaults = parseFilterState({ range: '24h' })
    copyFilters(draft, defaults)
    copyFilters(applied, defaults)
    page.value = 1
    pageSize.value = 20
    await syncRouteAndLoad()
  }

  /** 手动刷新当前已应用的筛选范围。 */
  async function refresh() {
    await load()
  }

  /** 切换列表页码。 */
  async function changePage(value: number) {
    page.value = value
    await syncRouteAndLoad()
  }

  /** 切换分页大小并回到第一页。 */
  async function changePageSize(value: number) {
    pageSize.value = value
    page.value = 1
    await syncRouteAndLoad()
  }

  watch(
    () => route.fullPath,
    () => {
      const restored = parseFilterState(route.query)
      copyFilters(draft, restored)
      copyFilters(applied, restored)
      page.value = Math.max(1, Number.parseInt(queryString(route.query.page), 10) || 1)
      pageSize.value = [10, 20, 50].includes(Number(route.query.pageSize))
        ? Number(route.query.pageSize)
        : 20
      void load()
    },
    { immediate: true },
  )

  onBeforeUnmount(() => {
    disposed = true
    requestSequence += 1
  })

  return {
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
  }
}
