import { flushPromises, shallowMount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { vi } from 'vitest'

import { getRagRun } from '../../api'
import ObservabilityDetailView from '../../views/ObservabilityDetailView.vue'

vi.mock('../../api', async () => {
  const actual = await vi.importActual<typeof import('../../api')>('../../api')
  return { ...actual, getRagRun: vi.fn() }
})

describe('ObservabilityDetailView', () => {
  it('separates safe error messages and codes from degradation reasons', async () => {
    vi.mocked(getRagRun).mockResolvedValue({
      run: {
        id: '11111111-1111-1111-1111-111111111111',
        requestId: 'safe-request-id',
        status: 'FAILED',
        executionMode: 'FULL_PIPELINE',
        candidateCount: 0,
        evidenceCount: 0,
        degraded: true,
        errorCode: 'MODEL_TIMEOUT',
        errorMessage: '模型请求超时，请稍后重试',
        startedAt: '2026-01-01T00:00:00Z',
      },
      stages: [
        {
          id: 'stage-1',
          stageName: 'ANSWER_MODEL',
          sequenceNo: 1,
          status: 'FAILED',
          reasonCode: 'PROVIDER_FALLBACK',
          errorCode: 'MODEL_HTTP_ERROR',
          errorMessage: '模型服务请求失败',
          startedAt: '2026-01-01T00:00:00Z',
          completedAt: '2026-01-01T00:00:01Z',
          elapsedMs: 1000,
        },
      ],
      degradationReasons: ['PROVIDER_FALLBACK'],
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/observability/:runId', component: { template: '<div />' } }],
    })
    await router.push('/observability/11111111-1111-1111-1111-111111111111')
    await router.isReady()

    const wrapper = shallowMount(ObservabilityDetailView, {
      global: { plugins: [router] },
    })
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('模型请求超时，请稍后重试')
    expect(text).toContain('MODEL_TIMEOUT')
    expect(text).toContain('降级/决策原因')
    expect(text).toContain('PROVIDER_FALLBACK')
    expect(text).toContain('模型服务请求失败')
    expect(text).toContain('MODEL_HTTP_ERROR')
  })
})
