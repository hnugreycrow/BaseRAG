import { describe, expect, it } from 'vitest'

import type { RagStageRun } from '../../../api'
import {
  buildWaterfall,
  formatDuration,
  buildTraceTree,
  initiallyExpanded,
  visibleTraceRows,
  reasonKind,
  reasonLabel,
} from '../../../components/observability/tracePresentation'

function stage(overrides: Partial<RagStageRun>): RagStageRun {
  return {
    id: 'stage',
    stageName: 'QUERY_PLANNING',
    sequenceNo: 1,
    status: 'SUCCESS',
    startedAt: '2026-09-15T00:00:00.000Z',
    completedAt: '2026-09-15T00:00:00.100Z',
    elapsedMs: 100,
    ...overrides,
  }
}

describe('trace presentation', () => {
  it('uses server labels and tolerates missing legacy labels without inferring severity', () => {
    expect(reasonLabel({ reasonLabel: '服务端原因说明' })).toBe('服务端原因说明')
    expect(reasonLabel({ reasonLabel: null })).toBe('暂无说明')
    expect(reasonLabel({})).toBe('暂无说明')
    expect(reasonKind({ status: 'DEGRADED', reasonCode: 'NEW_CODE' })).toBe('降级原因')
  })
  it('anchors the axis to request start and does not sum nested or parallel durations', () => {
    const layout = buildWaterfall(
      [stage({ id: 'parent' }), stage({ id: 'child', parentStageId: 'parent', sequenceNo: 2 })],
      { startedAt: '2026-09-14T23:59:59.900Z', totalMs: 500 },
    )
    expect(layout.durationMs).toBe(500)
    expect(layout.stages[0].offsetMs).toBe(100)
    expect(layout.stages[0].leftPercent).toBe(20)
  })

  it('collapses normal branches and expands only ancestors of exceptional nodes', () => {
    const tree = buildTraceTree(
      buildWaterfall([
        stage({ id: 'root', sequenceNo: 1 }),
        stage({ id: 'child', parentStageId: 'root', sequenceNo: 2 }),
        stage({ id: 'failed', parentStageId: 'child', sequenceNo: 3, status: 'FAILED' }),
        stage({ id: 'normal', sequenceNo: 4 }),
        stage({ id: 'skipped', parentStageId: 'normal', sequenceNo: 5, status: 'SKIPPED' }),
      ]).stages,
    )
    expect([...initiallyExpanded(tree)].sort()).toEqual(['child', 'root'])
    expect(visibleTraceRows(tree, new Set()).map((s) => s.id)).toEqual(['root', 'normal'])
    expect(visibleTraceRows(tree, initiallyExpanded(tree)).map((s) => s.id)).toEqual([
      'root',
      'child',
      'failed',
      'normal',
    ])
  })

  it('retains legacy, orphaned and cyclic nodes without recursion or duplicates', () => {
    const tree = buildTraceTree(
      buildWaterfall([
        stage({ id: 'legacy' }),
        stage({ id: 'orphan', parentStageId: 'missing', sequenceNo: 2 }),
        stage({ id: 'a', parentStageId: 'b', sequenceNo: 3 }),
        stage({ id: 'b', parentStageId: 'a', sequenceNo: 4 }),
        stage({ id: 'self', parentStageId: 'self', sequenceNo: 5 }),
      ]).stages,
    )
    const rows = visibleTraceRows(tree, new Set(['legacy', 'orphan', 'a', 'b', 'self']))
    expect(rows).toHaveLength(5)
    expect(new Set(rows.map((s) => s.id)).size).toBe(5)
    expect(reasonKind({ status: 'SUCCESS', reasonCode: 'PRIMARY' })).toBe('调用策略')
    expect(reasonKind({ status: 'SKIPPED', reasonCode: 'SUMMARY_NOT_DUE' })).toBe('跳过原因')
  })
  it('preserves concurrent stage positions on one wall-clock axis', () => {
    const layout = buildWaterfall([
      stage({ id: 'second', sequenceNo: 2, startedAt: '2026-09-15T00:00:00.020Z', elapsedMs: 50 }),
      stage({ id: 'first', sequenceNo: 1 }),
    ])

    expect(layout.durationMs).toBe(100)
    expect(layout.stages.map((item) => item.id)).toEqual(['first', 'second'])
    expect(layout.stages[1]).toMatchObject({ leftPercent: 20, widthPercent: 50, offsetMs: 20 })
  })

  it('keeps zero-duration stages visible without changing their displayed duration', () => {
    const layout = buildWaterfall([
      stage({ completedAt: '2026-09-15T00:00:00.000Z', elapsedMs: 0 }),
    ])

    expect(layout.durationMs).toBe(0)
    expect(layout.stages[0].widthPercent).toBe(0.8)
    expect(formatDuration(layout.stages[0].elapsedMs)).toBe('0 ms')
    expect(formatDuration(null)).toBe('—')
  })
})
