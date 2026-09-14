package com.hnu.backend.rag.retrieval;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.model.client.EmbeddingClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 负责跨向量模型检索知识库分块，并统一归并、排序结果。 */
@Service
public class RetrievalService {
  private final EmbeddingClient embedding;
  private final RetrievalMapper retrieval;
  private final RagProperties config;

  public RetrievalService(
      EmbeddingClient embedding, RetrievalMapper retrieval, RagProperties config) {
    this.embedding = embedding;
    this.retrieval = retrieval;
    this.config = config;
  }

  /**
   * 使用各知识库当前绑定的模型分别生成查询向量，再取全局相似度最高的结果。
   *
   * @param question 已规范化的检索问题
   * @return 按相似度降序排列且不超过配置上限的候选分块
   */
  public List<SearchHit> retrieve(String question) {
    return retrieve(question, null);
  }

  /**
   * 在指定知识库集合中检索；未指定集合时保持全库检索行为。
   *
   * @param question 已规范化的检索问题
   * @param knowledgeBaseIds 允许检索的知识库；null 表示全部知识库
   * @return 按相似度降序排列且不超过配置上限的候选分块
   */
  public List<SearchHit> retrieve(String question, List<UUID> knowledgeBaseIds) {
    List<UUID> scope =
        knowledgeBaseIds == null ? null : knowledgeBaseIds.stream().distinct().toList();
    if (scope != null && scope.isEmpty()) return List.of();
    List<SearchHit> candidates = new ArrayList<>();
    var bindings =
        scope == null ? retrieval.activeModelBindings() : retrieval.activeModelBindingsIn(scope);
    for (var binding : bindings) {
      float[] vector =
          embedding.embed(binding.model(), binding.dimensions(), List.of(question)).getFirst();
      if (scope == null) {
        candidates.addAll(
            retrieval.searchAll(
                EmbeddingClient.literal(vector),
                binding.model(),
                binding.dimensions(),
                config.getTopK()));
      } else {
        candidates.addAll(
            retrieval.searchIn(
                scope,
                EmbeddingClient.literal(vector),
                binding.model(),
                binding.dimensions(),
                config.getTopK()));
      }
    }
    return candidates.stream()
        .sorted(
            Comparator.comparingDouble(SearchHit::getSimilarity)
                .reversed()
                .thenComparing(hit -> hit.getChunkId().toString()))
        .limit(config.getTopK())
        .toList();
  }
}
