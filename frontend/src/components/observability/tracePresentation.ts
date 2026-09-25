import type {
  RagExecutionMode,
  RagRunStatus,
  RagRunSummary,
  RagStageName,
  RagStageRun,
  RagStageStatus,
} from '../../api'

export const RUN_STATUS_LABELS: Record<RagRunStatus, string> = {
  RUNNING: '运行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  INTERRUPTED: '已中断',
}

export const STAGE_STATUS_LABELS: Record<RagStageStatus, string> = {
  SUCCESS: '成功',
  DEGRADED: '已降级',
  FAILED: '失败',
  CANCELLED: '已取消',
  SKIPPED: '已跳过',
}

export const EXECUTION_MODE_LABELS: Record<RagExecutionMode, string> = {
  FULL_PIPELINE: '完整链路',
  SYSTEM_CHAT: '系统闲聊',
  FAST_PATH: '快速路径',
}

export const STAGE_NAME_LABELS: Record<RagStageName, string> = {
  MEMORY: '会话记忆',
  PLANNING: '问题规划',
  RETRIEVAL: '检索',
  EVIDENCE: '证据整理',
  ANSWER: '回答生成',
  MEMORY_LOAD: '加载会话记忆',
  MEMORY_SUMMARY: '生成记忆摘要',
  QUERY_PLANNING: '问题改写与拆分',
  INTENT_ROUTING: '意图路由',
  SUBQUESTION_EXECUTION: '执行子问题',
  EMBEDDING: '生成向量',
  DATABASE_RETRIEVAL: '数据库检索',
  MCP_EXECUTION: '执行 MCP 工具',
  CANDIDATE_MERGE: '合并候选',
  DEDUPLICATION: '候选去重',
  RERANK: '候选重排',
  PROMPT_ASSEMBLY: '组装上下文',
  ANSWER_MODEL: '生成回答',
  CITATION_VALIDATION: '校验引用',
  RESULT_PERSISTENCE: '保存结果',
}

/** 格式化毫秒值；无样本时返回占位符。 */
export function formatDuration(value?: number | null): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  if (value < 1000) return `${Math.round(value)} ms`
  if (value < 60_000) return `${(value / 1000).toFixed(value < 10_000 ? 2 : 1)} s`
  return `${(value / 60_000).toFixed(1)} min`
}

/** 将 0 到 1 的比例格式化为百分比。 */
export function formatRate(value: number): string {
  return `${(Math.max(0, Math.min(1, value)) * 100).toFixed(1)}%`
}

/** 使用当前语言环境展示后端时间。 */
export function formatDateTime(value?: string): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)
}

/** 瀑布图中一个阶段的相对布局。 */
export interface WaterfallStage extends RagStageRun {
  offsetMs: number
  leftPercent: number
  widthPercent: number
}

/** 阶段瀑布的统一时间轴。 */
export interface WaterfallLayout {
  durationMs: number
  stages: WaterfallStage[]
}

/**
 * 计算阶段在统一运行时间轴上的位置。
 *
 * 极短阶段保留 0.8% 的可视宽度，但不改变旁边展示的真实耗时。
 */
export function buildWaterfall(
  stages: RagStageRun[],
  run?: Pick<RagRunSummary, 'startedAt' | 'totalMs'>,
): WaterfallLayout {
  if (stages.length === 0) return { durationMs: 0, stages: [] }

  const ordered = [...stages].sort((left, right) => left.sequenceNo - right.sequenceNo)
  const starts = ordered.map((stage) => Date.parse(stage.startedAt)).filter(Number.isFinite)
  const runStart = run ? Date.parse(run.startedAt) : NaN
  const base = Number.isFinite(runStart) ? runStart : starts.length > 0 ? Math.min(...starts) : 0
  const ends = ordered.map((stage) => {
    const completed = Date.parse(stage.completedAt)
    const started = Date.parse(stage.startedAt)
    if (Number.isFinite(completed)) return completed
    return Number.isFinite(started) ? started + Math.max(0, stage.elapsedMs) : base
  })
  const finish =
    run?.totalMs != null && Number.isFinite(run.totalMs) && Number.isFinite(runStart)
      ? base + Math.max(0, run.totalMs)
      : Math.max(base, ...ends)
  const durationMs = Math.max(1, finish - base)

  // 所有并发 span 都换算到同一墙钟区间，因此重叠关系不会被 sequenceNo 拉平。
  const positioned = ordered.map((stage) => {
    const started = Date.parse(stage.startedAt)
    const safeStarted = Number.isFinite(started) ? started : base
    const offsetMs = Math.max(0, safeStarted - base)
    const leftPercent = Math.min(100, (offsetMs / durationMs) * 100)
    const measuredWidth = (Math.max(0, stage.elapsedMs) / durationMs) * 100
    const widthPercent = Math.min(100 - leftPercent, Math.max(0.8, measuredWidth))
    return { ...stage, offsetMs, leftPercent, widthPercent }
  })

  return { durationMs: finish - base, stages: positioned }
}

/** 从 UUID 或请求标识中提取适合表格展示的短标识。 */
export function shortId(value?: string): string {
  if (!value) return '—'
  return value.length > 12 ? value.slice(0, 8) : value
}

export interface TraceTreeNode extends WaterfallStage {
  depth: number
  parentId: string | null
  children: TraceTreeNode[]
}

/** 历史数据保持平铺；孤儿与循环断开后，每个节点仍只出现一次。 */
export function buildTraceTree(stages: WaterfallStage[]): TraceTreeNode[] {
  const nodes = new Map<string, TraceTreeNode>()
  for (const stage of stages) {
    nodes.set(stage.id, { ...stage, depth: 0, parentId: stage.parentStageId ?? null, children: [] })
  }
  for (const node of nodes.values()) {
    const seen = new Set([node.id])
    let parentId = node.parentId
    while (parentId) {
      const parent = nodes.get(parentId)
      if (!parent || seen.has(parentId)) {
        node.parentId = null
        break
      }
      seen.add(parentId)
      parentId = parent.parentId
    }
  }
  const roots: TraceTreeNode[] = []
  for (const node of nodes.values()) {
    const parent = node.parentId ? nodes.get(node.parentId) : undefined
    if (parent) {
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  }
  const pending = roots.map((node) => ({ node, depth: 0 }))
  while (pending.length) {
    const { node, depth } = pending.pop()!
    node.depth = depth
    node.children.sort((a, b) => a.sequenceNo - b.sequenceNo)
    pending.push(...node.children.map((child) => ({ node: child, depth: depth + 1 })))
  }
  return roots.sort((a, b) => a.sequenceNo - b.sequenceNo)
}

/** 默认展开异常节点祖先，正常跳过与调用策略不触发展开。 */
export function initiallyExpanded(roots: TraceTreeNode[]): Set<string> {
  const expanded = new Set<string>()
  const pending = roots.map((node) => ({ node, ancestors: [] as string[] }))
  while (pending.length) {
    const { node, ancestors } = pending.pop()!
    if (['FAILED', 'DEGRADED', 'CANCELLED'].includes(node.status)) {
      ancestors.forEach((id) => expanded.add(id))
    }
    pending.push(
      ...node.children.map((child) => ({ node: child, ancestors: [...ancestors, node.id] })),
    )
  }
  return expanded
}

export function visibleTraceRows(roots: TraceTreeNode[], expanded: Set<string>): TraceTreeNode[] {
  const rows: TraceTreeNode[] = []
  const pending = [...roots].reverse()
  while (pending.length) {
    const node = pending.pop()!
    rows.push(node)
    if (expanded.has(node.id)) {
      pending.push(...[...node.children].reverse())
    }
  }
  return rows
}

const REASON_LABELS: Record<string, string> = {
  PRIMARY: '使用主模型',
  PROVIDER_FALLBACK: '切换至备用模型',
  CITATION_REPAIR: '重新生成以修复引用',
  SUMMARY_NOT_DUE: '尚未达到摘要生成条件',
  INTENT_TREE_INVALID_OUTPUT: '路由输出不符合结构要求，回退到公共知识库检索',
  INTENT_TREE_LOW_CONFIDENCE: '意图识别置信度不足，回退到公共知识库检索',
  INTENT_TREE_EMPTY: '未配置意图树，使用公共知识库检索',
  INTENT_TREE_TIMEOUT: '路由超时，回退到公共知识库检索',
  INTENT_TREE_CLASSIFICATION_FAILED: '路由分类失败，回退到公共知识库检索',
  INTENT_TREE_PARTIAL_FALLBACK: '部分子问题路由降级',
  SYSTEM_CHAT: '系统闲聊无需检索证据',
  SYSTEM_CHAT_ROUTED: '子问题路由为系统闲聊',
  RERANK_DISABLED: '未启用重排',
  NO_RERANK_INPUT: '无可重排的候选',
  NO_EVIDENCE: '证据不足，返回固定回答',
  VECTOR_DISABLED: '未启用向量检索',
  EMPTY_KNOWLEDGE_SCOPE: '知识库范围为空',
  NO_EMBEDDING_BINDINGS: '没有可用的向量模型绑定',
  SUBQUESTION_TIMEOUT: '子问题超过执行预算',
  INVALID_CITATIONS: '回答包含无效引用',
}

export function reasonLabel(code: string): string {
  return REASON_LABELS[code] ?? '未收录的原因'
}

export function reasonKind(stage: Pick<RagStageRun, 'status' | 'reasonCode'>): string {
  if (stage.status === 'SKIPPED') {
    return '跳过原因'
  }
  if (stage.status === 'DEGRADED') {
    return '降级原因'
  }
  if (stage.reasonCode === 'PROVIDER_FALLBACK' || stage.reasonCode === 'CITATION_REPAIR') {
    return '调用策略'
  }
  return stage.status === 'FAILED' || stage.status === 'CANCELLED' ? '状态原因' : '调用策略'
}
