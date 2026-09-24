import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import ReasoningPanel from '../../../components/conversation/ReasoningPanel.vue'

it('toggles reasoning with accessible expanded state and preserves its content', async () => {
  const wrapper = mount(ReasoningPanel, {
    props: { content: '推理内容' },
    global: { stubs: { ElIcon: { template: '<span><slot /></span>' } } },
  })
  const toggle = wrapper.get('button')
  expect(toggle.attributes('aria-expanded')).toBe('false')
  expect(wrapper.get('.reasoning-clip').attributes('aria-hidden')).toBe('true')
  expect(toggle.attributes('aria-controls')).toBe(wrapper.get('.reasoning-clip').attributes('id'))
  await toggle.trigger('click')
  expect(toggle.attributes('aria-expanded')).toBe('true')
  expect(wrapper.get('.reasoning-clip').attributes('aria-hidden')).toBe('false')
  await toggle.trigger('click')
  expect(toggle.attributes('aria-expanded')).toBe('false')
  expect(wrapper.get('.reasoning-body').text()).toBe('推理内容')
  wrapper.unmount()
})
