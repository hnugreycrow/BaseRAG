package com.hnu.backend.rag.memory;

import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.trace.RagRunTrace;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** RAG 流程的记忆加载阶段，负责统一校验阶段输入和提供者输出。 */
@Component
public final class MemoryStage {
  private final MemoryProvider provider;

  /**
   * 创建记忆加载阶段。
   *
   * @param provider 会话记忆提供者
   */
  public MemoryStage(MemoryProvider provider) {
    this.provider = provider;
  }

  /**
   * 加载当前轮次之前的会话记忆。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param beforeTurn 当前问题所在轮次
   * @return 已校验的会话记忆
   */
  public RagMemory execute(UUID ownerId, UUID conversationId, int beforeTurn) {
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(conversationId, "conversationId");
    if (beforeTurn < 1) throw new IllegalArgumentException("beforeTurn must be positive");
    return Objects.requireNonNull(
        provider.load(ownerId, conversationId, beforeTurn), "MemoryProvider returned null");
  }

  /**
   * 加载当前轮次之前的会话记忆并记录耗时。
   *
   * @param ownerId 所属用户标识
   * @param conversationId 会话标识
   * @param beforeTurn 当前问题所在轮次
   * @param trace 当前问答 Trace
   * @return 已校验的会话记忆
   */
  public RagMemory execute(UUID ownerId, UUID conversationId, int beforeTurn, RagRunTrace trace) {
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(conversationId, "conversationId");
    if (beforeTurn < 1) throw new IllegalArgumentException("beforeTurn must be positive");
    RagRunTrace.Span span = trace.start(RagStageName.MEMORY_LOAD, null, beforeTurn - 1);
    try {
      RagMemory memory =
          Objects.requireNonNull(
              provider.load(ownerId, conversationId, beforeTurn, trace),
              "MemoryProvider returned null");
      span.success(memory.unsummarizedTurns().size() + memory.recentTurns().size());
      return memory;
    } catch (RuntimeException error) {
      span.failed(errorCode(error, "MEMORY_LOAD_FAILED"));
      throw error;
    }
  }

  /** 返回异常携带的稳定业务码或指定兜底码。 */
  private String errorCode(RuntimeException error, String fallback) {
    return error instanceof com.hnu.backend.shared.error.ApiException api ? api.code() : fallback;
  }
}
