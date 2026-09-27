package com.hnu.backend.rag.memory;

import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.observability.trace.TraceContext;
import java.util.UUID;

/** 加载指定轮次之前的会话记忆，不依赖会话持久化实现。 */
public interface MemoryLoader {
  /**
   * 加载当前轮次之前的会话记忆。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param beforeTurn 当前问题所在轮次
   * @return 已校验的会话记忆
   */
  RagMemory execute(UUID ownerId, UUID conversationId, int beforeTurn);

  /**
   * 加载当前轮次之前的会话记忆并记录耗时。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param beforeTurn 当前问题所在轮次
   * @param trace 当前问答 Trace
   * @return 已校验的会话记忆
   */
  RagMemory execute(UUID ownerId, UUID conversationId, int beforeTurn, TraceContext trace);

  /** 兼容根 Trace 入口；内部显式传递父节点上下文。 */
  RagMemory execute(UUID ownerId, UUID conversationId, int beforeTurn, RagRunTrace trace);
}
