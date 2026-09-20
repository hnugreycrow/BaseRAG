import DOMPurify from 'dompurify'
import { marked } from 'marked'

export function renderMarkdown(content: string) {
  const parsed = marked.parse(content, { async: false }) as string
  const sanitized = DOMPurify.sanitize(parsed, { USE_PROFILES: { html: true } })
  const template = document.createElement('template')
  template.innerHTML = sanitized
  template.content.querySelectorAll('a[href]').forEach((link) => {
    link.setAttribute('target', '_blank')
    link.setAttribute('rel', 'noopener noreferrer')
  })
  return template.innerHTML
}

export function markdownSummary(content: string, maxLength = 120) {
  const container = document.createElement('div')
  container.innerHTML = renderMarkdown(content)
  container
    .querySelectorAll('p, h1, h2, h3, h4, h5, h6, li, blockquote, pre, tr')
    .forEach((node) => node.append(' '))
  const plainText = (container.textContent ?? '').replace(/\s+/g, ' ').trim()
  return plainText.length > maxLength
    ? plainText.slice(0, maxLength).trimEnd() + '…'
    : plainText || '暂无内容'
}
