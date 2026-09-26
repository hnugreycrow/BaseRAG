package com.hnu.backend.rag.retrieval;

import java.util.Objects;

/**
 * 一个候选在重排后的最终决策。
 *
 * @param candidate 完整证据候选，正文仍是不可信数据
 * @param relevanceScore 模型相关性分数；确定性降级时为 -1
 * @param rank 重排后的全局一基名次
 * @param selected 是否进入最终回答上下文
 */
public record RerankDecision(
    EvidenceCandidate candidate, double relevanceScore, int rank, boolean selected) {
  public RerankDecision {
    candidate = Objects.requireNonNull(candidate, "candidate");
    if ((!Double.isFinite(relevanceScore) || relevanceScore < 0 || relevanceScore > 1)
        && relevanceScore != -1) {
      throw new IllegalArgumentException("Invalid rerank relevance score");
    }
    if (rank < 1) {
      throw new IllegalArgumentException("Invalid rerank position");
    }
  }
}
