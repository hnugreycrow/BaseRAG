import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import DocumentPreviewView from '../../views/DocumentPreviewView.vue'

const mocks = vi.hoisted(() => ({
  getDocumentPreview: vi.fn(),
}))

vi.mock('../../api', async () => ({
  ...(await vi.importActual<typeof import('../../api')>('../../api')),
  getDocumentPreview: mocks.getDocumentPreview,
  documentContentUrl: (knowledgeBaseId: string, documentId: string) =>
    '/api/knowledge-bases/' + knowledgeBaseId + '/documents/' + documentId + '/content',
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { knowledgeBaseId: 'kb', documentId: 'doc' } }),
}))

function mountPreview() {
  return mount(DocumentPreviewView, {
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

describe('document preview view', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders safe Markdown content and file metadata', async () => {
    mocks.getDocumentPreview.mockResolvedValue({
      name: 'guide.md',
      format: 'MARKDOWN',
      mediaType: 'text/markdown; charset=utf-8',
      fileSizeBytes: 2048,
      content: '# 使用指南\n\n**正文**',
      blocks: [],
    })

    const wrapper = mountPreview()
    try {
      await flushPromises()
      expect(mocks.getDocumentPreview).toHaveBeenCalledWith('kb', 'doc')
      expect(wrapper.get('.file-identity h1').text()).toBe('guide.md')
      expect(wrapper.get('.file-identity p').text()).toContain('2.0 KB')
      expect(wrapper.get('.markdown-content h1').text()).toBe('使用指南')
      expect(wrapper.get('.markdown-content strong').text()).toBe('正文')
    } finally {
      wrapper.unmount()
    }
  })

  it('uses the current content endpoint for PDF and renders DOCX blocks', async () => {
    mocks.getDocumentPreview.mockResolvedValueOnce({
      name: 'guide.pdf',
      format: 'PDF',
      mediaType: 'application/pdf',
      fileSizeBytes: 100,
      content: null,
      blocks: [],
    })
    const pdf = mountPreview()
    await flushPromises()
    expect(pdf.get('object').attributes('data')).toBe(
      '/api/knowledge-bases/kb/documents/doc/content',
    )
    pdf.unmount()

    mocks.getDocumentPreview.mockResolvedValueOnce({
      name: 'guide.docx',
      format: 'DOCX',
      mediaType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      fileSizeBytes: 100,
      content: null,
      blocks: [
        {
          kind: 'HEADING',
          content: '制度标题',
          level: 1,
          sourceUnit: 'PARAGRAPH',
          sourceStart: 1,
          sourceEnd: 1,
        },
        {
          kind: 'LIST',
          content: '第一项',
          level: null,
          sourceUnit: 'PARAGRAPH',
          sourceStart: 2,
          sourceEnd: 2,
        },
        {
          kind: 'LIST',
          content: '第二项',
          level: null,
          sourceUnit: 'PARAGRAPH',
          sourceStart: 3,
          sourceEnd: 3,
        },
        {
          kind: 'TABLE',
          content: '名称 | 值\nA | B',
          level: null,
          sourceUnit: 'PARAGRAPH',
          sourceStart: 4,
          sourceEnd: 4,
        },
      ],
    })
    const docx = mountPreview()
    try {
      await flushPromises()
      expect(docx.get('.docx-sheet h1').text()).toBe('制度标题')
      expect(docx.findAll('.docx-sheet li').map((item) => item.text())).toEqual([
        '第一项',
        '第二项',
      ])
      expect(docx.get('.docx-table').text()).toContain('A | B')
      expect(docx.get('.source-position').text()).toBe('第 1 段')
    } finally {
      docx.unmount()
    }
  })

  it('shows a retry action after loading fails', async () => {
    mocks.getDocumentPreview
      .mockRejectedValueOnce(new Error('对象存储不可用'))
      .mockResolvedValueOnce({
        name: 'guide.md',
        format: 'MARKDOWN',
        mediaType: 'text/markdown',
        fileSizeBytes: 8,
        content: '恢复成功',
        blocks: [],
      })
    const wrapper = mountPreview()
    try {
      await flushPromises()
      expect(wrapper.get('[role="alert"]').text()).toContain('对象存储不可用')
      await wrapper.get('.preview-error .el-button').trigger('click')
      await flushPromises()
      expect(wrapper.get('.markdown-content').text()).toContain('恢复成功')
      expect(mocks.getDocumentPreview).toHaveBeenCalledTimes(2)
    } finally {
      wrapper.unmount()
    }
  })
})
