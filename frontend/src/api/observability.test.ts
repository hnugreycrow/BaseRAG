import { beforeEach, describe, expect, it, vi } from 'vitest'

import { request } from './http'
import { getRagRun, listRagRuns, summarizeRagRuns } from './observability'

vi.mock('./http', () => ({ request: vi.fn() }))

describe('observability api', () => {
  beforeEach(() => {
    vi.mocked(request).mockReset()
  })

  it('serializes list filters and one-based paging', async () => {
    vi.mocked(request).mockResolvedValueOnce({
      items: [],
      total: 0,
      page: 2,
      pageSize: 50,
      totalPages: 0,
    })

    await listRagRuns(
      {
        from: '2026-09-14T00:00:00.000Z',
        to: '2026-09-15T00:00:00.000Z',
        status: 'FAILED',
        executionMode: 'FULL_PIPELINE',
        model: 'qwen3',
        userId: 'user-1',
      },
      2,
      50,
    )

    expect(request).toHaveBeenCalledWith({
      url: '/observability/rag-runs',
      params: {
        from: '2026-09-14T00:00:00.000Z',
        to: '2026-09-15T00:00:00.000Z',
        status: 'FAILED',
        executionMode: 'FULL_PIPELINE',
        model: 'qwen3',
        userId: 'user-1',
        page: 2,
        pageSize: 50,
      },
    })
  })

  it('calls the detail and aggregate endpoints', async () => {
    vi.mocked(request).mockResolvedValue(undefined)

    await getRagRun('run-1')
    await summarizeRagRuns({ status: 'COMPLETED' })

    expect(request).toHaveBeenNthCalledWith(1, {
      url: '/observability/rag-runs/run-1',
    })
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/observability/rag-runs/summary',
      params: { status: 'COMPLETED' },
    })
  })
})
