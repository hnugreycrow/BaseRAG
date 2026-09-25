import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as api from '../../../api'
import { useAuthStore } from '../../../store/auth'
import { useRagRunObservability } from '../../../components/observability/useRagRunObservability'

vi.mock('../../../api', async () => {
  const actual = await vi.importActual<typeof import('../../../api')>('../../../api')
  return {
    ...actual,
    listRagRuns: vi.fn(),
    summarizeRagRuns: vi.fn(),
  }
})

const emptyPage = { items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 }
const emptySummary = {
  requestCount: 0,
  successRate: 0,
  degradedRate: 0,
  totalMs: { p50Ms: null, p95Ms: null },
  endToEndTtftMs: { p50Ms: null, p95Ms: null },
  modelTtftMs: { p50Ms: null, p95Ms: null },
}

describe('useRagRunObservability', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-09-15T08:00:00.000Z'))
    vi.mocked(api.listRagRuns).mockResolvedValue(emptyPage)
    vi.mocked(api.summarizeRagRuns).mockResolvedValue(emptySummary)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('defaults to all retained runs and never forwards userId for an ordinary user', async () => {
    const pinia = createPinia()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/admin/observability', component: { template: '<div />' } }],
    })
    await router.push('/admin/observability?userId=another-user')
    await router.isReady()
    useAuthStore(pinia).applySession({
      user: {
        id: 'user-1',
        username: 'reader',
        displayName: '读者',
        role: 'USER',
        enabled: true,
        lastLoginAt: null,
        createdAt: '2026-09-01T00:00:00Z',
        updatedAt: '2026-09-01T00:00:00Z',
      },
      csrfToken: 'nonce',
    })

    let state!: ReturnType<typeof useRagRunObservability>
    const Harness = defineComponent({
      setup() {
        state = useRagRunObservability()
        return () => h('div')
      },
    })
    const wrapper = mount(Harness, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(api.listRagRuns).toHaveBeenCalledWith({}, 1, 10)
    expect(state.draft.range).toBe('all')
    wrapper.unmount()
  })

  it('applies filters, resets paging and stores them in the route', async () => {
    const pinia = createPinia()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/admin/observability', component: { template: '<div />' } }],
    })
    await router.push('/admin/observability?page=3')
    await router.isReady()
    useAuthStore(pinia).applySession({
      user: {
        id: 'admin-1',
        username: 'admin',
        displayName: '管理员',
        role: 'ADMIN',
        enabled: true,
        lastLoginAt: null,
        createdAt: '2026-09-01T00:00:00Z',
        updatedAt: '2026-09-01T00:00:00Z',
      },
      csrfToken: 'nonce',
    })

    let state!: ReturnType<typeof useRagRunObservability>
    const Harness = defineComponent({
      setup() {
        state = useRagRunObservability()
        return () => h('div')
      },
    })
    const wrapper = mount(Harness, { global: { plugins: [pinia, router] } })
    await flushPromises()

    state.draft.status = 'FAILED'
    state.draft.userId = 'user-2'
    await state.applyFilters()
    await flushPromises()

    expect(state.page.value).toBe(1)
    expect(router.currentRoute.value.query).toMatchObject({
      range: 'all',
      status: 'FAILED',
      userId: 'user-2',
    })
    expect(router.currentRoute.value.query.page).toBeUndefined()

    await state.resetFilters()
    await flushPromises()

    expect(state.draft.range).toBe('all')
    expect(router.currentRoute.value.query).toEqual({ range: 'all' })
    expect(api.listRagRuns).toHaveBeenLastCalledWith({}, 1, 10)
    wrapper.unmount()
  })

  it('keeps an explicit 24 hour range from an existing link', async () => {
    const pinia = createPinia()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/admin/observability', component: { template: '<div />' } }],
    })
    await router.push('/admin/observability?range=24h')
    await router.isReady()
    useAuthStore(pinia).applySession({
      user: {
        id: 'admin-1',
        username: 'admin',
        displayName: '管理员',
        role: 'ADMIN',
        enabled: true,
        lastLoginAt: null,
        createdAt: '2026-09-01T00:00:00Z',
        updatedAt: '2026-09-01T00:00:00Z',
      },
      csrfToken: 'nonce',
    })

    let state!: ReturnType<typeof useRagRunObservability>
    const Harness = defineComponent({
      setup() {
        state = useRagRunObservability()
        return () => h('div')
      },
    })
    const wrapper = mount(Harness, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(state.draft.range).toBe('24h')
    expect(api.listRagRuns).toHaveBeenCalledWith(
      {
        from: '2026-09-14T08:00:00.000Z',
        to: '2026-09-15T08:00:00.000Z',
      },
      1,
      10,
    )
    wrapper.unmount()
  })
})
