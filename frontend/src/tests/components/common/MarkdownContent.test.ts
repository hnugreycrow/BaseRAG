import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import MarkdownContent from '../../../components/common/MarkdownContent.vue'

describe('safe markdown content', () => {
  it('renders common blocks, opens links separately, and removes active content', () => {
    const content =
      '# 标题\n\n- 第一项\n- 第二项\n\n| 名称 | 值 |\n| --- | --- |\n| A | B |\n\n' +
      '~~~ts\nconst answer = 1\n~~~\n\n' +
      '<script>alert(1)</script><img src=x onerror="alert(1)">\n\n' +
      '[安全链接](https://example.com) [危险链接](javascript:alert(1))'

    const wrapper = mount(MarkdownContent, { props: { content } })

    expect(wrapper.get('h1').text()).toBe('标题')
    expect(wrapper.findAll('li')).toHaveLength(2)
    expect(wrapper.get('table').text()).toContain('A')
    expect(wrapper.get('pre code').text()).toContain('const answer = 1')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('[onerror]').exists()).toBe(false)
    expect(wrapper.find('a[href^="javascript:"]').exists()).toBe(false)
    const link = wrapper.get('a[href="https://example.com"]')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
  })
})
