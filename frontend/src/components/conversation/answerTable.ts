import { marked, type Tokens } from 'marked'

export function answerTable(lines: string[], index: number): Tokens.Table | undefined {
  // Only invoke the Markdown lexer when a table delimiter is present.
  if (!lines[index]?.includes('|') || !/^\s*\|?\s*:?-+/.test(lines[index + 1] ?? '')) {
    return undefined
  }
  const token = marked.lexer(lines.slice(index).join('\n'), { gfm: true })[0]
  return token?.type === 'table' ? (token as Tokens.Table) : undefined
}
