package com.hnu.backend.rag.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.model.client.EmbeddingClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetrievalServiceTest {
  private final UUID ownerId = UUID.randomUUID();

  @Test
  void embedsPerModelAndMergesByRankInsteadOfRawSimilarity() {
    EmbeddingClient embedding = mock(EmbeddingClient.class);
    RetrievalMapper mapper = mock(RetrievalMapper.class);
    RagProperties config = new RagProperties();
    config.getSearch().setDefaultTopK(2);
    config.getSearch().setRecallBudget(2);
    var first = new EmbeddingBinding("model-a", 2);
    var second = new EmbeddingBinding("model-b", 3);
    when(mapper.activeModelBindings(ownerId)).thenReturn(List.of(first, second));
    when(embedding.embed("model-a", 2, List.of("问题"))).thenReturn(List.of(new float[] {1, 0}));
    when(embedding.embed("model-b", 3, List.of("问题"))).thenReturn(List.of(new float[] {0, 1, 0}));
    SearchHit modelAFirst = hit(id(1), .60);
    SearchHit modelASecond = hit(id(4), .59);
    SearchHit modelBFirst = hit(id(2), .99);
    SearchHit modelBSecond = hit(id(3), .98);
    when(mapper.searchAll(ownerId, "[1.0, 0.0]", "model-a", 2, 2))
        .thenReturn(List.of(modelAFirst, modelASecond));
    when(mapper.searchAll(ownerId, "[0.0, 1.0, 0.0]", "model-b", 3, 2))
        .thenReturn(List.of(modelBFirst, modelBSecond));

    var result =
        new RetrievalService(embedding, mapper, config, new CandidateMerge())
            .retrieve(ownerId, "问题");

    assertEquals(List.of(id(1), id(2)), result.stream().map(SearchHit::getChunkId).toList());
    verify(embedding).embed("model-a", 2, List.of("问题"));
    verify(embedding).embed("model-b", 3, List.of("问题"));
  }

  @Test
  void limitsBindingsAndCandidatesToRequestedKnowledgeBases() {
    EmbeddingClient embedding = mock(EmbeddingClient.class);
    RetrievalMapper mapper = mock(RetrievalMapper.class);
    RagProperties config = new RagProperties();
    config.getSearch().setDefaultTopK(2);
    config.getSearch().setRecallBudget(2);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    List<UUID> scope = List.of(first, second);
    var binding = new EmbeddingBinding("model-a", 2);
    when(mapper.activeModelBindingsIn(ownerId, scope)).thenReturn(List.of(binding));
    when(embedding.embed("model-a", 2, List.of("问题"))).thenReturn(List.of(new float[] {1, 0}));
    when(mapper.searchIn(ownerId, scope, "[1.0, 0.0]", "model-a", 2, 2))
        .thenReturn(List.of(hit(id(1), .91)));

    var result =
        new RetrievalService(embedding, mapper, config, new CandidateMerge())
            .retrieve(ownerId, "问题", scope);

    assertEquals(List.of(.91), result.stream().map(SearchHit::getSimilarity).toList());
    verify(mapper).activeModelBindingsIn(ownerId, scope);
    verify(mapper).searchIn(ownerId, scope, "[1.0, 0.0]", "model-a", 2, 2);
    verify(mapper, never()).activeModelBindings(any());
    verify(mapper, never()).searchAll(any(), anyString(), anyString(), anyInt(), anyInt());
  }

  @Test
  void doesNotSearchWhenRequestedScopeIsEmpty() {
    EmbeddingClient embedding = mock(EmbeddingClient.class);
    RetrievalMapper mapper = mock(RetrievalMapper.class);

    var result =
        new RetrievalService(embedding, mapper, new RagProperties(), new CandidateMerge())
            .retrieve(ownerId, "问题", List.of());

    assertTrue(result.isEmpty());
    verifyNoInteractions(embedding, mapper);
  }

  @Test
  void skipsAllVectorWorkWhenChannelIsDisabled() {
    EmbeddingClient embedding = mock(EmbeddingClient.class);
    RetrievalMapper mapper = mock(RetrievalMapper.class);
    RagProperties config = new RagProperties();
    config.getSearch().getChannels().getVector().setEnabled(false);

    var result =
        new RetrievalService(embedding, mapper, config, new CandidateMerge())
            .retrieve(ownerId, "问题");

    assertTrue(result.isEmpty());
    verifyNoInteractions(embedding, mapper);
  }

  private SearchHit hit(UUID chunkId, double similarity) {
    SearchHit hit = new SearchHit();
    hit.setChunkId(chunkId);
    hit.setKnowledgeBaseId(UUID.randomUUID());
    hit.setKnowledgeBaseName("知识库");
    hit.setDocumentId(UUID.randomUUID());
    hit.setVersionId(UUID.randomUUID());
    hit.setDocumentName("文档.md");
    hit.setChunkIndex(0);
    hit.setContent("正文");
    hit.setHeading("标题");
    hit.setLineStart(1);
    hit.setLineEnd(2);
    hit.setSimilarity(similarity);
    return hit;
  }

  private UUID id(long value) {
    return new UUID(0, value);
  }
}
