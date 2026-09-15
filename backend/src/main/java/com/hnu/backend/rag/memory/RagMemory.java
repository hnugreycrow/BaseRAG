package com.hnu.backend.rag.memory;

import java.util.List;
import java.util.Objects;

/**
 * 为一次 RAG 请求加载的分层会话记忆。
 *
 * @param summary 已压缩的早期会话话题摘要文本
 * @param summaryRevision 摘要的乐观锁版本号
 * @param unsummarizedTurns 尚未纳入摘要的历史轮次
 * @param recentTurns 直接注入提示词的最近轮次
 * @param loadedThroughTurn 本次加载覆盖到的最大轮次
 */
public record RagMemory(
    String summary,
    int summaryRevision,
    List<MemoryTurn> unsummarizedTurns,
    List<MemoryTurn> recentTurns,
    int loadedThroughTurn) {
  /** 校验记忆边界并对轮次列表进行防御性复制。 */
  public RagMemory {
    summary = Objects.requireNonNull(summary, "summary");
    if (summaryRevision < 0)
      throw new IllegalArgumentException("summaryRevision must not be negative");
    unsummarizedTurns = List.copyOf(unsummarizedTurns);
    recentTurns = List.copyOf(recentTurns);
    if (loadedThroughTurn < 0) {
      throw new IllegalArgumentException("loadedThroughTurn must not be negative");
    }
  }
}
