package com.hnu.backend.rag.retrieval;

import java.util.Objects;

/**
 * 一次候选命中的完整检索归因。同一分块可拥有多个归因，不能只保留融合后的单一分数。
 *
 * @param subQuestionId 触发本次命中的子问题 ID
 * @param embeddingModel 生成查询向量的 Embedding 模型，用于限定原始相似度的可比范围
 * @param rawSimilarity 数据库返回的原始相似度，只用于同模型审计和排序
 * @param retrievalRank 候选在该子问题、该 Embedding 模型结果中的一基名次
 * @param retrievalChannel 产生本次命中的检索通道
 * @param fusionContribution 本次命中按通道权重和 RRF 名次计算出的融合分贡献
 */
public record RetrievalAttribution(
    String subQuestionId,
    String embeddingModel,
    double rawSimilarity,
    int retrievalRank,
    RetrievalChannel retrievalChannel,
    double fusionContribution) {
  public RetrievalAttribution {
    subQuestionId = Objects.requireNonNull(subQuestionId, "subQuestionId");
    embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
    retrievalChannel = Objects.requireNonNull(retrievalChannel, "retrievalChannel");
    if (subQuestionId.isBlank()
        || embeddingModel.isBlank()
        || !Double.isFinite(rawSimilarity)
        || retrievalRank < 1
        || !Double.isFinite(fusionContribution)
        || fusionContribution <= 0) {
      throw new IllegalArgumentException("Invalid retrieval attribution");
    }
  }
}
