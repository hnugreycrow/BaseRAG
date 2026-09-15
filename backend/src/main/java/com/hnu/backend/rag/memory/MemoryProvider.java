package com.hnu.backend.rag.memory;

import com.hnu.backend.observability.trace.RagRunTrace;
import java.util.UUID;

/** 为 RAG 流程加载指定会话的分层记忆。 */
public interface MemoryProvider {
  /**
   * 加载目标轮次之前的摘要和历史消息。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param beforeTurn 当前问题所在轮次，返回内容不包含该轮
   * @return 会话记忆
   */
  RagMemory load(UUID ownerId, UUID conversationId, int beforeTurn);

  /**
   * 加载会话记忆并允许实现记录内部摘要阶段。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param beforeTurn 当前问题所在轮次
   * @param trace 当前问答 Trace
   * @return 会话记忆
   */
  default RagMemory load(UUID ownerId, UUID conversationId, int beforeTurn, RagRunTrace trace) {
    return load(ownerId, conversationId, beforeTurn);
  }
}
