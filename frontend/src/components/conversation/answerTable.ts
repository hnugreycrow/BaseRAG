import { marked, type Tokens } from 'marked'

export function answerTable(lines: string[], index: number): Tokens.Table | undefined {
  // 仅在存在表格分隔符时调用 Markdown 词法分析器，避免普通段落承担解析开销。
  if (!lines[index]?.includes('|') || !/^\s*\|?\s*:?-+/.test(lines[index + 1] ?? '')) {
    return undefined
  }
  const token = marked.lexer(lines.slice(index).join('\n'), { gfm: true })[0]
  return token?.type === 'table' ? (token as Tokens.Table) : undefined
}
