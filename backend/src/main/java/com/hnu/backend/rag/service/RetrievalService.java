package com.hnu.backend.rag.service;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.model.client.EmbeddingClient;
import com.hnu.backend.rag.mapper.RetrievalMapper;
import com.hnu.backend.rag.model.SearchHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    List<SearchHit> candidates = new ArrayList<>();
    for (var binding : retrieval.activeModelBindings()) {
      float[] vector =
          embedding.embed(binding.model(), binding.dimensions(), List.of(question)).getFirst();
      candidates.addAll(
          retrieval.searchAll(
              EmbeddingClient.literal(vector),
              binding.model(),
              binding.dimensions(),
              config.getTopK()));
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
