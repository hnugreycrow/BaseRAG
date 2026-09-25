import { flushPromises, mount } from '@vue/test-utils'
import { vi } from 'vitest'
import { nextTick } from 'vue'
import {
  getModelSettings,
  getRetrievalSettings,
  type ModelSettings,
  type RetrievalSettings,
} from '../../api/settings'
import SystemSettingsView from '../../views/SystemSettingsView.vue'

vi.mock('../../api/settings', () => ({ getModelSettings: vi.fn(), getRetrievalSettings: vi.fn() }))
const model = {
  id: 'primary',
  provider: 'example',
  model: 'chat-example',
  defaultModel: true,
  timeoutMs: 30000,
  dimensions: 0,
  supportsThinking: true,
  credentialConfigured: true,
  localFallback: false,
}
const fixture: ModelSettings = {
  chatTier: 'standard',
  chat: [model],
  embedding: [{ ...model, id: 'vector', dimensions: 1024 }],
  rerank: [{ ...model, id: 'noop', localFallback: true }],
  maxRetries: 2,
  failureThreshold: 2,
  openDurationMs: 30000,
  embeddingBatchSize: 16,
}
const retrievalFixture: RetrievalSettings = {
  recallBudget: 20,
  vectorEnabled: false,
  channelTimeoutMs: 15000,
  fusionStrategy: 'rrf',
  rrfK: 20,
  vectorWeight: 1,
  deduplicationOverlapThreshold: 0.85,
  rerankEnabled: false,
  rerankInputLimit: 40,
  selectedEvidenceLimit: 8,
  maxSubQuestions: 4,
  planningRecentTurns: 4,
  routingConfidenceThreshold: 0.7,
  routingTimeoutMs: 5000,
  maxQuestionChars: 2000,
  recentTurns: 8,
  summaryBatchTurns: 4,
  summaryMaxChars: 400,
}
function render() {
  return mount(SystemSettingsView, {
    global: {
      stubs: {
        'el-button': {
          template: '<button :disabled="loading || disabled"><slot /></button>',
          props: ['loading', 'disabled', 'icon'],
        },
        'el-tag': { template: '<span><slot /></span>' },
        'el-icon': { template: '<span><slot /></span>' },
      },
    },
  })
}
describe('SystemSettingsView', () => {
  beforeEach(() => vi.resetAllMocks())
  it('shows real configuration without implying service health', async () => {
    vi.mocked(getModelSettings).mockResolvedValue(fixture)
    const view = render()
    await nextTick()
    expect(view.text()).toContain('正在读取系统配置')
    await flushPromises()
    expect(view.text()).toContain('系统配置')
    expect(view.text()).toContain('chat-example')
    expect(view.text()).toContain('1024 维')
    expect(view.text()).toContain('跳过重排')
    expect(view.text()).toContain('连接未检测')
    expect(view.text()).toContain('已有知识库保持绑定')
    expect(view.findAll('.model-section')).toHaveLength(3)
  })
  it('recovers from an initial failure and preserves a stale snapshot on refresh failure', async () => {
    vi.mocked(getModelSettings)
      .mockRejectedValueOnce(new Error('网络不可用'))
      .mockResolvedValueOnce(fixture)
      .mockRejectedValueOnce(new Error('刷新失败'))
    const view = render()
    await flushPromises()
    expect(view.get('[role="alert"]').text()).toContain('网络不可用')
    await view.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(view.find('[role="alert"]').exists()).toBe(false)
    await view.get('.page-header button').trigger('click')
    await flushPromises()
    expect(view.get('[role="alert"]').text()).toContain('可能已过期')
    expect(view.text()).toContain('chat-example')
  })

  it('loads retrieval lazily, supports keyboard tabs and preserves model state', async () => {
    vi.mocked(getModelSettings).mockResolvedValue(fixture)
    vi.mocked(getRetrievalSettings).mockResolvedValue(retrievalFixture)
    const view = render()
    await flushPromises()
    expect(getRetrievalSettings).not.toHaveBeenCalled()
    await view.get('#settings-tab-models').trigger('keydown', { key: 'ArrowRight' })
    await flushPromises()
    expect(view.get('#settings-tab-retrieval').attributes('aria-selected')).toBe('true')
    const panel = view.get('#settings-panel-retrieval')
    expect(panel.text()).toContain('20 条')
    expect(panel.text()).toContain('已关闭')
    expect(panel.text()).toContain('本地降级选择证据')
    expect(panel.text()).toContain('已有回答与文档分块不会因此重算')
    await view.get('#settings-tab-retrieval').trigger('keydown', { key: 'Home' })
    await view.get('#settings-tab-retrieval').trigger('click')
    await flushPromises()
    expect(getRetrievalSettings).toHaveBeenCalledTimes(1)
    expect(getModelSettings).toHaveBeenCalledTimes(1)
  })
  it('retries retrieval independently and refreshes only the active tab', async () => {
    vi.mocked(getModelSettings).mockResolvedValue(fixture)
    vi.mocked(getRetrievalSettings)
      .mockRejectedValueOnce(new Error('检索接口不可用'))
      .mockResolvedValueOnce(retrievalFixture)
      .mockRejectedValueOnce(new Error('刷新失败'))
    const view = render()
    await flushPromises()
    await view.get('#settings-tab-retrieval').trigger('click')
    await flushPromises()
    const panel = view.get('#settings-panel-retrieval')
    expect(panel.get('[role="alert"]').text()).toContain('检索接口不可用')
    await panel.get('[role="alert"] button').trigger('click')
    await flushPromises()
    await view.get('.page-header button').trigger('click')
    await flushPromises()
    expect(panel.get('[role="alert"]').text()).toContain('可能已过期')
    expect(panel.text()).toContain('20 条')
    expect(getModelSettings).toHaveBeenCalledTimes(1)
  })
})
