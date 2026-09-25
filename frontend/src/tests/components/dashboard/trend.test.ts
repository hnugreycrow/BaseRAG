import { describe, expect, it } from 'vitest'
import { comparison, dashboardRange, latencyValues } from '../../../components/dashboard/trend'
import type { DashboardDay } from '../../../api'

describe('dashboard statistics', () => {
  it('uses Beijing midnight and crosses months independently of browser timezone', () => {
    expect(dashboardRange(7, new Date('2026-09-30T16:00:00Z'))).toEqual({
      from: '2026-09-25',
      to: '2026-10-01',
    })
    expect(dashboardRange(30, new Date('2026-03-01T00:00:00Z'))).toEqual({
      from: '2026-01-31',
      to: '2026-03-01',
    })
  })
  it('does not fabricate a percentage when the previous period has no requests', () => {
    expect(comparison(8, 0)).toBe('上期为 0')
    expect(comparison(0, 0)).toBe('持平')
    expect(comparison(0, 8)).toBe('-100.0%')
  })
  it('keeps missing samples as gaps while preserving actual zero latency', () => {
    const rows = [null, 0, 1250].map((ttftP50Ms) => ({ ttftP50Ms }) as DashboardDay)
    expect(latencyValues(rows, 'ttft', 'P50')).toEqual([null, 0, 1.25])
  })
})
