package com.hnu.backend.question.application;

import com.hnu.backend.ai.embedding.EmbeddingClient;
import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.question.domain.SearchHit;
import com.hnu.backend.question.infrastructure.persistence.RetrievalMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

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
