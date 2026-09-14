package com.hnu.backend.rag.memory;

import java.util.UUID;

/** 为 RAG 流程加载指定会话的分层记忆。 */
public interface MemoryProvider {
  /**
   * 加载目标轮次之前的摘要和历史消息。
   *
   * @param conversationId 会话标识
   * @param beforeTurn 当前问题所在轮次，返回内容不包含该轮
   * @return 会话记忆
   */
  RagMemory load(UUID conversationId, int beforeTurn);
}
