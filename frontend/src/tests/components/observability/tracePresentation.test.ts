import { describe, expect, it } from 'vitest'

import type { RagStageRun } from '../../../api'
import { buildWaterfall, formatDuration } from '../../../components/observability/tracePresentation'

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
