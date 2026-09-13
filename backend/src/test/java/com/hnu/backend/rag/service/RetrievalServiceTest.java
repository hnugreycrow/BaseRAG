package com.hnu.backend.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.model.client.EmbeddingClient;
import com.hnu.backend.rag.mapper.RetrievalMapper;
import com.hnu.backend.rag.model.EmbeddingBinding;
import com.hnu.backend.rag.model.SearchHit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetrievalServiceTest {
  @Test
  void embedsPerModelAndMergesGlobalTopK() {
    EmbeddingClient embedding = mock(EmbeddingClient.class);
    RetrievalMapper mapper = mock(RetrievalMapper.class);
    RagProperties config = new RagProperties();
    config.setTopK(2);
    var first = new EmbeddingBinding("model-a", 2);
    var second = new EmbeddingBinding("model-b", 3);
    when(mapper.activeModelBindings()).thenReturn(List.of(first, second));
    when(embedding.embed("model-a", 2, List.of("问题"))).thenReturn(List.of(new float[] {1, 0}));
    when(embedding.embed("model-b", 3, List.of("问题"))).thenReturn(List.of(new float[] {0, 1, 0}));
    when(mapper.searchAll("[1.0, 0.0]", "model-a", 2, 2)).thenReturn(List.of(hit(.91), hit(.40)));
    when(mapper.searchAll("[0.0, 1.0, 0.0]", "model-b", 3, 2))
        .thenReturn(List.of(hit(.95), hit(.80)));

    var result = new RetrievalService(embedding, mapper, config).retrieve("问题");

    assertEquals(List.of(.95, .91), result.stream().map(SearchHit::getSimilarity).toList());
    verify(embedding).embed("model-a", 2, List.of("问题"));
    verify(embedding).embed("model-b", 3, List.of("问题"));
  }

  private SearchHit hit(double similarity) {
    SearchHit hit = new SearchHit();
    hit.setChunkId(UUID.randomUUID());
    hit.setSimilarity(similarity);
    return hit;
  }
}
