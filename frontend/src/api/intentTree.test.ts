import { describe, expect, it, vi } from 'vitest'
import { createIntentNode, deleteIntentNode, listIntentNodes, updateIntentNode } from './intentTree'
import { request } from './http'

vi.mock('./http', () => ({ request: vi.fn().mockResolvedValue(undefined) }))

describe('intent tree admin API', () => {
  it('uses the shared admin endpoints for tree edits', async () => {
    const input = {
      parentId: null,
      name: '报销',
      description: '报销制度',
      examples: ['怎么报销'],
      kind: 'KB' as const,
      toolName: null,
      knowledgeBaseIds: ['kb-1'],
      enabled: true,
      sortOrder: 1,
    }
    await listIntentNodes()
    expect(request).toHaveBeenLastCalledWith({ url: '/admin/intent-nodes' })
    await createIntentNode(input)
    expect(request).toHaveBeenLastCalledWith({
      url: '/admin/intent-nodes',
      method: 'post',
      data: input,
    })
    await updateIntentNode('node-1', input)
    expect(request).toHaveBeenLastCalledWith({
      url: '/admin/intent-nodes/node-1',
      method: 'put',
      data: input,
    })
    await deleteIntentNode('node-1')
    expect(request).toHaveBeenLastCalledWith({
      url: '/admin/intent-nodes/node-1',
      method: 'delete',
    })
  })
})
