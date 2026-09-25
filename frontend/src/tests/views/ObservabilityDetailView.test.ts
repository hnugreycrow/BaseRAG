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
  it('supports keyboard-operable collapse controls and nullable stage details', async () => {
    const base = {
      startedAt: '2026-01-01T00:00:00Z',
      completedAt: '2026-01-01T00:00:01Z',
      elapsedMs: 1000,
    }
    vi.mocked(getRagRun).mockResolvedValue({
      run: {
        id: 'run',
        requestId: 'request',
        status: 'COMPLETED',
        executionMode: 'FULL_PIPELINE',
        candidateCount: 0,
        evidenceCount: 0,
        degraded: false,
        startedAt: base.startedAt,
        totalMs: 1000,
      },
      stages: [
        { ...base, id: 'parent', stageName: 'MEMORY', sequenceNo: 1, status: 'SUCCESS' },
        {
          ...base,
          id: 'child',
          parentStageId: 'parent',
          stageName: 'MEMORY_SUMMARY',
          sequenceNo: 2,
          status: 'SKIPPED',
          elapsedMs: 0,
          ttftMs: null,
          reasonCode: 'SUMMARY_NOT_DUE',
          reasonLabel: '尚未达到摘要生成条件',
        },
      ],
      degradationReasons: [],
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/observability/:runId', component: { template: '<div />' } }],
    })
    await router.push('/observability/run')
    const wrapper = shallowMount(ObservabilityDetailView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.findAll('.waterfall-row')).toHaveLength(1)
    const toggle = wrapper.get('.tree-toggle')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('.waterfall-row')).toHaveLength(2)
    expect(wrapper.text()).toContain('尚未达到摘要生成条件')
    expect(wrapper.text()).not.toContain('首内容 —')
    await wrapper.findAll('.stage-select')[1].trigger('click')
    expect(wrapper.get('.stage-inspector').text()).toContain('SUMMARY_NOT_DUE')
    const collapse = wrapper.findAll('.trace-controls button')[1]
    await collapse.trigger('click')
    expect(wrapper.findAll('.waterfall-row')).toHaveLength(1)
    await wrapper.findAll('.trace-controls button')[0].trigger('click')
    expect(wrapper.findAll('.waterfall-row')).toHaveLength(2)
  })

  it.each([true, false])(
    'renders reason details with new API metadata: %s',
    async (hasMetadata) => {
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
            reasonLabel: '切换至备用模型',
            errorCode: 'MODEL_HTTP_ERROR',
            errorMessage: '模型服务请求失败',
            startedAt: '2026-01-01T00:00:00Z',
            completedAt: '2026-01-01T00:00:01Z',
            elapsedMs: 1000,
          },
        ],
        degradationReasons: ['PROVIDER_FALLBACK'],
        degradationReasonDetails: hasMetadata
          ? [
              {
                stageName: 'ANSWER_MODEL',
                reasonCode: 'PROVIDER_FALLBACK',
                reasonLabel: '切换至备用模型',
              },
            ]
          : undefined,
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
      expect(text).toContain('调用策略')
      expect(wrapper.get('.degradation-panel').text()).toContain(
        hasMetadata ? '切换至备用模型' : '暂无说明',
      )
      expect(text).toContain('PROVIDER_FALLBACK')
      expect(text).toContain('模型服务请求失败')
      expect(text).toContain('MODEL_HTTP_ERROR')
    },
  )
})
