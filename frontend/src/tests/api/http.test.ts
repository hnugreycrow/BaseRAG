import type { InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { askConversation } from '../../api/conversation'
import { http, request, setCsrfToken } from '../../api/http'

describe('authenticated transports', () => {
  afterEach(() => {
    setCsrfToken(null)
    vi.restoreAllMocks()
  })

  it('sends cookies and CSRF nonce for Axios write requests', async () => {
    let captured: InternalAxiosRequestConfig | undefined
    http.defaults.adapter = async (config) => {
      captured = config
      return {
        data: { code: 'SUCCESS', message: 'ok', data: null, requestId: 'request-1' },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    setCsrfToken('nonce-1')

    await request<void>({ url: '/write', method: 'post' })

    expect(captured?.withCredentials).toBe(true)
    expect(captured?.headers.get('X-CSRF-Token')).toBe('nonce-1')
  })

  it('sends cookies and CSRF nonce and parses POST SSE frames', async () => {
    setCsrfToken('nonce-sse')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('event: started\ndata: {"generationId":"g1"}\n\n', {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      }),
    )
    const events: unknown[] = []

    await askConversation('c1', 'm1', '问题', (event) => events.push(event))

    const init = fetchMock.mock.calls[0]?.[1]
    expect(init?.credentials).toBe('include')
    expect(new Headers(init?.headers).get('X-CSRF-Token')).toBe('nonce-sse')
    expect(events).toEqual([{ type: 'started', data: { generationId: 'g1' } }])
  })
})
