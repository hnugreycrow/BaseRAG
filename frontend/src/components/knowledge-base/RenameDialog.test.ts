import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput } from 'element-plus'

import RenameDialog from './RenameDialog.vue'

async function mountDialog() {
  const wrapper = mount(RenameDialog, {
    props: {
      modelValue: true,
      title: '重命名知识库',
      currentName: '原知识库',
      loading: false,
      maxLength: 200,
    },
    global: {
      plugins: [ElButton, ElDialog, ElForm, ElFormItem, ElInput],
      stubs: { teleport: true },
    },
  })
  await flushPromises()
  return wrapper
}

describe('RenameDialog', () => {
  it('fills the current name on the first open and on reopening', async () => {
    const wrapper = await mountDialog()
    expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('原知识库')

    await wrapper.findComponent(ElInput).setValue('临时名称')
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    expect(wrapper.findComponent(ElInput).props('modelValue')).toBe('原知识库')

    wrapper.unmount()
  })

  it('submits once when Enter submits the form', async () => {
    const wrapper = await mountDialog()
    await wrapper.findComponent(ElInput).setValue('新知识库')
    await wrapper.find('input').trigger('keyup', { key: 'Enter' })
    await wrapper.find('form').trigger('submit')

    expect(wrapper.emitted('submit')).toEqual([['新知识库']])
    wrapper.unmount()
  })

  it('submits once when the save button is clicked', async () => {
    const wrapper = await mountDialog()
    await wrapper.findComponent(ElInput).setValue('新知识库')
    const save = wrapper
      .findAllComponents(ElButton)
      .find((button) => button.text().includes('保存名称'))!
    await save.trigger('click')

    expect(wrapper.emitted('submit')).toEqual([['新知识库']])
    wrapper.unmount()
  })
})
