import { expect, it } from 'vitest'
import type { DocumentChunk } from '../../../api'
import { sourceLabel } from '../../../components/knowledge-base/sourceLabel'

const base: DocumentChunk = {
  id: 'chunk',
  chunkIndex: 0,
  heading: '',
  lineStart: 2,
  lineEnd: 4,
  characterCount: 10,
  preview: 'text',
}

it('labels Markdown lines, PDF pages and DOCX paragraphs from source fields', () => {
  expect(sourceLabel(base)).toBe('第 2–4 行')
  expect(
    sourceLabel({
      ...base,
      lineStart: null,
      lineEnd: null,
      sourceUnit: 'PAGE',
      sourceStart: 3,
      sourceEnd: 3,
    }),
  ).toBe('第 3 页')
  expect(
    sourceLabel({
      ...base,
      lineStart: null,
      lineEnd: null,
      sourceUnit: 'PARAGRAPH',
      sourceStart: 5,
      sourceEnd: 7,
    }),
  ).toBe('第 5–7 段')
})
