package com.hnu.backend.rag.execution;

import java.util.Objects;

/**
 * 单个子问题实际使用的阶段预算。
 *
 * @param subQuestionId 预算所属的子问题 ID，用于日志和超时归因
 * @param recallBudget 该子问题在整个向量通道中最多保留的候选数
 * @param timeoutMs 该子问题向量通道的数据库召回预算，不包含 Embedding 耗时
 * @param rrfK 跨 Embedding 模型按名次归一化时使用的 RRF 常数
 * @param vectorWeight 向量检索归因对融合分的贡献权重
 * @param vectorEnabled 向量通道是否参与本次执行
 */
public record StageBudget(
    String subQuestionId,
    int recallBudget,
    int timeoutMs,
    int rrfK,
    double vectorWeight,
    boolean vectorEnabled) {
  public StageBudget {
    subQuestionId = Objects.requireNonNull(subQuestionId, "subQuestionId");
    if (subQuestionId.isBlank()
        || recallBudget < 1
        || timeoutMs < 1
        || rrfK < 1
        || !Double.isFinite(vectorWeight)
        || vectorWeight <= 0) {
      throw new IllegalArgumentException("Invalid stage budget");
    }
  }
}
