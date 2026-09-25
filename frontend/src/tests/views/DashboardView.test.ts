import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardView from '../../views/DashboardView.vue'
import type { DashboardTrend } from '../../api'

const api = vi.hoisted(() => ({
  getDashboardTrend: vi.fn(),
  getErrorMessage: (e: Error) => e.message,
}))
vi.mock('../../api', () => api)
vi.mock('../../components/dashboard/TrendChart.vue', () => ({
  default: { props: ['series'], template: '<div class="chart-stub" />' },
}))
const result = (requestCount = 12): DashboardTrend => ({
  previousRequestCount: 6,
  previousFailureCount: 0,
  days: [
    {
      date: '2026-09-25',
      requestCount,
      failureCount: 2,
      ttftSampleCount: 3,
      totalSampleCount: 4,
      ttftP50Ms: 1000,
      ttftP95Ms: 2500,
      totalP50Ms: 5000,
      totalP95Ms: 8000,
    },
  ],
})
function render() {
  return mount(DashboardView, {
    global: {
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
        'el-select': {
          props: ['modelValue'],
          template:
            '<select :value="modelValue" @change="$emit(\'update:modelValue\', Number($event.target.value))"><option value="7">7</option><option value="30">30</option></select>',
        },
        'el-button': { template: '<button><slot /></button>' },
        'el-radio-group': { template: '<div><slot /></div>' },
        'el-radio-button': true,
        'el-icon': true,
      },
    },
  })
}
beforeEach(() => vi.clearAllMocks())
describe('Dashboard', () => {
  it('hides comparisons when the previous period is outside retention', async () => {
    api.getDashboardTrend.mockResolvedValue({
      ...result(),
      previousRequestCount: null,
      previousFailureCount: null,
    })
    const wrapper = render()
    await flushPromises()
    expect(wrapper.findAll('.comparison')).toHaveLength(0)
    expect(wrapper.text()).toContain('上期超出保留期，暂无环比')
    wrapper.unmount()
  })

  it('shows independent request and failure totals and an accessible daily table', async () => {
    api.getDashboardTrend.mockResolvedValue(result())
    const wrapper = render()
    await flushPromises()
    expect(wrapper.findAll('.metric-value').map((node) => node.text())).toEqual(['12', '2'])
    expect(wrapper.text()).toContain('16.7%')
    expect(wrapper.findAll('.chart-stub')).toHaveLength(3)
    expect(wrapper.get('tbody').text()).toContain('2.50 秒')
    wrapper.unmount()
  })
  it('offers retry on errors instead of displaying zero counts', async () => {
    api.getDashboardTrend
      .mockRejectedValueOnce(new Error('连接失败'))
      .mockResolvedValueOnce(result())
    const wrapper = render()
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('连接失败')
    expect(wrapper.find('.metric-value').exists()).toBe(false)
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.metric-value')).toHaveLength(2)
    wrapper.unmount()
  })
  it('ignores an older response after changing the date range', async () => {
    let finish!: (value: DashboardTrend) => void
    api.getDashboardTrend
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          }),
      )
      .mockResolvedValueOnce(result(30))
    const wrapper = render()
    await wrapper.get('select').setValue('30')
    await flushPromises()
    finish(result(7))
    await flushPromises()
    expect(wrapper.get('.metric-value').text()).toBe('30')
    wrapper.unmount()
  })
  it('shows an empty latency state when there are no successful samples', async () => {
    api.getDashboardTrend.mockResolvedValue({
      days: [],
      previousRequestCount: 0,
      previousFailureCount: 0,
    })
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('暂无成功请求的耗时样本')
    expect(wrapper.text()).not.toContain('NaN')
    wrapper.unmount()
  })
})
