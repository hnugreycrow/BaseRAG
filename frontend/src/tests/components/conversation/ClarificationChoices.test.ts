import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import ClarificationChoices from '../../../components/conversation/ClarificationChoices.vue'
import type { Clarification } from '../../../api'

const clarification: Clarification = {
  id: 'clarification-1',
  type: 'KB_INTENT',
  status: 'PENDING',
  question: '怎么申请权限？',
  currentStep: 1,
  totalSteps: 2,
  options: [
    { nodeId: 'oa', label: 'OA > 权限申请', description: '办公账号与协作权限' },
    { nodeId: 'finance', label: '财务 > 权限申请', description: '报销与财务审批权限' },
  ],
}
function mountChoices(busy = false) {
  return mount(ClarificationChoices, { props: { clarification, activeId: clarification.id, busy } })
}

describe('KB clarification choices', () => {
  it('requires confirmation after selecting an option and preserves supplementary conditions', async () => {
    const wrapper = mountChoices()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.findAll('input[type="radio"]')[1]!.setValue()
    expect(wrapper.emitted('confirm')).toBeUndefined()
    expect(wrapper.text()).toContain('报销与财务审批权限')
    expect(wrapper.text()).toContain('第 1 / 2 项')
    await wrapper.setProps({ supplement: '只需要查看权限' })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('confirm')).toEqual([
      [{ option: clarification.options[1], text: '只需要查看权限' }],
    ])
    await wrapper.get('button[type="button"]').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('accepts free text without inventing an option and rejects blank text', async () => {
    const wrapper = mountChoices()
    await wrapper.setProps({ supplement: '  ' })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('confirm')).toBeUndefined()
    await wrapper.setProps({ supplement: '  财务系统  ' })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('confirm')).toEqual([[{ option: null, text: '财务系统' }]])
  })

  it('locks busy and historical forms, including submit events', async () => {
    const wrapper = mountChoices()
    await wrapper.findAll('input')[0]!.setValue()
    await wrapper.setProps({ busy: true })
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    expect(wrapper.find('textarea').exists()).toBe(false)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('confirm')).toBeUndefined()
    await wrapper.setProps({ busy: false, activeId: 'new-clarification' })
    expect(wrapper.find('button[type="submit"]').exists()).toBe(false)
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
  })

  it('resets selection on a new clarification while the parent owns the shared draft', async () => {
    const wrapper = mountChoices()
    await wrapper.findAll('input')[0]!.setValue()
    await wrapper.setProps({ supplement: '保留草稿' })
    await wrapper.setProps({ busy: true })
    await wrapper.setProps({ busy: false })
    expect(wrapper.props('supplement')).toBe('保留草稿')
    await wrapper.setProps({
      clarification: { ...clarification, id: 'next', currentStep: 2 },
      activeId: 'next',
    })
    expect(wrapper.text()).toContain('第 2 / 2 项')
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(
      wrapper.findAll('input').some((input) => (input.element as HTMLInputElement).checked),
    ).toBe(false)
  })

  it('can display older saved messages without progress or descriptions', () => {
    const wrapper = mount(ClarificationChoices, {
      props: {
        clarification: {
          id: 'old',
          type: 'KB_INTENT',
          status: 'CANCELLED',
          options: [{ nodeId: 'old-node', label: '旧选项' }],
        },
        busy: false,
      },
    })
    expect(wrapper.text()).toContain('已取消澄清')
    expect(wrapper.find('.clarification-progress').exists()).toBe(false)
  })
})
