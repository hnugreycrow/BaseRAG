import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, vi } from 'vitest'

import { createIntentNode, listIntentNodes, type IntentNode } from '../../api/intentTree'
import IntentTreeView from '../../views/IntentTreeView.vue'

vi.mock('../../api/intentTree', () => ({
  listIntentNodes: vi.fn().mockResolvedValue([]),
  listIntentTools: vi.fn().mockResolvedValue([]),
  createIntentNode: vi.fn(),
  updateIntentNode: vi.fn(),
  deleteIntentNode: vi.fn(),
}))

vi.mock('../../api/knowledgeBase', () => ({
  listKnowledgeBases: vi.fn().mockResolvedValue({ items: [], total: 0 }),
}))

const node: IntentNode = {
  id: '00000000-0000-0000-0000-000000000001',
  parentId: null,
  name: '人事制度',
  description: '',
  examples: [],
  kind: null,
  toolName: null,
  knowledgeBaseIds: [],
  enabled: true,
  sortOrder: 0,
}

function mountView() {
  return shallowMount(IntentTreeView, {
    global: {
      stubs: {
        ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
        ElForm: {
          template: '<form><slot /></form>',
          methods: {
            clearValidate() {},
            validate() {
              return Promise.resolve(true)
            },
          },
        },
        ElTree: {
          props: ['data'],
          template:
            '<div><slot /><template v-for="item in data" :key="item.id"><slot :data="item" /></template></div>',
        },
        ElFormItem: { template: '<div><slot /></div>' },
        ElButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
        ElInput: {
          props: ['modelValue'],
          template:
            '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
      },
    },
  })
}

describe('IntentTreeView', () => {
  beforeEach(() => {
    vi.mocked(listIntentNodes).mockReset()
    vi.mocked(listIntentNodes).mockResolvedValue([])
    vi.mocked(createIntentNode).mockReset()
    vi.mocked(createIntentNode).mockResolvedValue(node)
  })

  it('requires a node purpose before creating', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('.intent-empty h2').text()).toBe('还没有意图节点')
    const buttons = () => wrapper.findAll('button')
    await buttons()
      .find((button) => button.text() === '新增节点')
      ?.trigger('click')
    await buttons()
      .find((button) => button.text() === '创建节点')
      ?.trigger('click')

    expect(wrapper.text()).toContain('请选择节点用途')
    expect(createIntentNode).not.toHaveBeenCalled()
  })

  it('renders a saved node even if the tree invokes its slot without arguments', async () => {
    vi.mocked(listIntentNodes).mockResolvedValueOnce([node])
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.get('.intent-node-heading strong').text()).toBe('人事制度')
  })

  it('creates a classification node after entering its required fields', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text() === '新增节点')
      ?.trigger('click')
    await wrapper.get('input[placeholder="例如：人事制度"]').setValue('人事制度')
    expect(wrapper.findAll('[role="radio"]')).toHaveLength(4)
    await wrapper.get('[role="radio"]').trigger('click')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '创建节点')
      ?.trigger('click')
    await flushPromises()

    expect(createIntentNode).toHaveBeenCalledWith(
      expect.objectContaining({ name: '人事制度', kind: null, parentId: null }),
    )
  })
})
