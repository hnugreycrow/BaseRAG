import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import UploadDocumentDialog from './UploadDocumentDialog.vue'

function uploadFile(name: string, uid: number, bytes = '# content') {
  const raw = Object.assign(new File([bytes], name, { type: 'text/markdown' }), { uid })
  return { uid, name, raw, status: 'ready' as const }
}

describe('upload document dialog', () => {
  it('accepts multiple Markdown extensions, rejects invalid files and limits the selection', async () => {
    const wrapper = mount(UploadDocumentDialog, {
      props: { modelValue: true, loading: false, result: null },
      global: { plugins: [ElementPlus], stubs: { teleport: true } },
      attachTo: document.body,
    })
    try {
      await flushPromises()
      const upload = wrapper.findComponent({ name: 'ElUpload' })
      const onChange = upload.props('onChange') as (file: unknown, files: unknown[]) => void
      const invalid = uploadFile('wrong.txt', 1)
      onChange(invalid, [invalid])
      await flushPromises()
      expect(wrapper.text()).toContain('请选择 .md 或 .markdown')
      const first = uploadFile('one.md', 2)
      const second = uploadFile('two.markdown', 3)
      onChange(first, [first])
      onChange(second, [first, second])
      const oversized = uploadFile('huge.md', 4, 'x'.repeat(5 * 1024 * 1024 + 1))
      onChange(oversized, [first, second, oversized])
      await flushPromises()
      expect(wrapper.text()).toContain('单个文件不能超过 5 MiB')
      expect(upload.props('multiple')).toBe(true)
      expect(upload.props('limit')).toBe(10)
      const submit = wrapper
        .findAllComponents({ name: 'ElButton' })
        .find((button) => button.text().includes('上传文档'))!
      await submit.trigger('click')
      expect(wrapper.emitted('submit')?.[0]).toEqual([[first.raw, second.raw]])
      const onExceed = upload.props('onExceed') as () => void
      onExceed()
      await flushPromises()
      expect(wrapper.text()).toContain('每次最多选择 10 个文件')
    } finally {
      wrapper.unmount()
    }
  })

  it('keeps only failed files after partial success and retries that file', async () => {
    const wrapper = mount(UploadDocumentDialog, {
      props: { modelValue: true, loading: false, result: null },
      global: { plugins: [ElementPlus], stubs: { teleport: true } },
      attachTo: document.body,
    })
    try {
      await flushPromises()
      const upload = wrapper.findComponent({ name: 'ElUpload' })
      const onChange = upload.props('onChange') as (file: unknown, files: unknown[]) => void
      const first = uploadFile('one.md', 1)
      const second = uploadFile('two.md', 2)
      onChange(first, [first])
      onChange(second, [first, second])
      await flushPromises()
      const submit = wrapper
        .findAllComponents({ name: 'ElButton' })
        .find((button) => button.text().includes('上传文档'))!
      await submit.trigger('click')
      await wrapper.setProps({
        result: {
          results: [
            {
              index: 0,
              fileName: 'one.md',
              status: 'UPLOADED',
              documentId: 'doc',
              errorCode: null,
              message: null,
            },
            {
              index: 1,
              fileName: 'two.md',
              status: 'FAILED',
              documentId: null,
              errorCode: 'IMPORT_FAILED',
              message: '入库失败',
            },
          ],
        },
      })
      await flushPromises()
      expect(wrapper.text()).toContain('two.md：入库失败')
      await submit.trigger('click')
      expect(wrapper.emitted('submit')?.[1]).toEqual([[second.raw]])
    } finally {
      wrapper.unmount()
    }
  })
})
