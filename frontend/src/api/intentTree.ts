import { request } from './http'

export type IntentKind = 'KB' | 'MCP' | 'SYSTEM'

export interface IntentNode {
  id: string
  parentId: string | null
  name: string
  description: string
  examples: string[]
  kind: IntentKind | null
  toolName: string | null
  knowledgeBaseIds: string[]
  enabled: boolean
  sortOrder: number
}

export type IntentNodeInput = Omit<IntentNode, 'id'>

export interface IntentToolOption {
  name: string
  description: string
}

export function listIntentNodes() {
  return request<IntentNode[]>({ url: '/admin/intent-nodes' })
}

export function listIntentTools() {
  return request<IntentToolOption[]>({ url: '/admin/intent-nodes/tools' })
}

export function createIntentNode(data: IntentNodeInput) {
  return request<IntentNode>({ url: '/admin/intent-nodes', method: 'post', data })
}

export function updateIntentNode(id: string, data: IntentNodeInput) {
  return request<IntentNode>({ url: `/admin/intent-nodes/${id}`, method: 'put', data })
}

export function deleteIntentNode(id: string) {
  return request<void>({ url: `/admin/intent-nodes/${id}`, method: 'delete' })
}
