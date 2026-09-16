import type { DocumentChunk } from '../../api'

/**
 * 按原文件的位置单位生成分块来源标签。
 *
 * @param chunk 带有来源位置的分块
 * @returns 用于界面展示的页、段落或行号标签
 */
export function sourceLabel(chunk: DocumentChunk): string {
  // 历史 Markdown 分块可能只有旧行号字段。
  const start = chunk.sourceStart ?? chunk.lineStart
  const end = chunk.sourceEnd ?? chunk.lineEnd
  if (start == null || end == null) return '原文位置未知'
  if (chunk.sourceUnit === 'PAGE') return start === end ? `第 ${start} 页` : `第 ${start}–${end} 页`
  if (chunk.sourceUnit === 'PARAGRAPH')
    return start === end ? `第 ${start} 段` : `第 ${start}–${end} 段`
  return `第 ${start}–${end} 行`
}
