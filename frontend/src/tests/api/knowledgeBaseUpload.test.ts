import { beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../../api/http'
import { getDocumentPreview, uploadDocuments } from '../../api/knowledgeBase'

vi.mock('../../api/http', () => ({ request: vi.fn() }))

describe('batch document upload request', () => {
  beforeEach(() => vi.clearAllMocks())

  it('sends ordered files as repeated multipart parts with a batch timeout', async () => {
    vi.mocked(request).mockResolvedValue({ results: [] })
    const first = new File(['# one'], 'one.md', { type: 'text/markdown' })
    const second = new File(['# two'], 'two.markdown', { type: 'text/markdown' })

    await uploadDocuments('kb', [first, second])

    expect(request).toHaveBeenCalledTimes(1)
    const config = vi.mocked(request).mock.calls[0][0]
    expect(config.url).toBe('/knowledge-bases/kb/documents/batch')
    expect(config.method).toBe('post')
    expect(config.timeout).toBe(120_000)
    expect(config.data).toBeInstanceOf(FormData)
    expect((config.data as FormData).getAll('files')).toEqual([first, second])
  })

  it('loads a current document preview without a version identifier', async () => {
    vi.mocked(request).mockResolvedValue({
      name: 'guide.md',
      format: 'MARKDOWN',
      mediaType: 'text/markdown',
      fileSizeBytes: 8,
      content: '# guide',
      blocks: [],
    })

    await getDocumentPreview('kb', 'doc')

    expect(request).toHaveBeenCalledWith({
      url: '/knowledge-bases/kb/documents/doc/preview',
    })
  })
})
