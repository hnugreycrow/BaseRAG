package com.hnu.backend.rag.retrieval;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.knowledgebase.entity.KnowledgeBase;
import com.hnu.backend.knowledgebase.mapper.KnowledgeBaseMapper;
import com.hnu.backend.model.client.EmbeddingClient;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.execution.CancellationToken;
import com.hnu.backend.rag.execution.StageBudget;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DirectedRetrievalTest {
  @Test
  void sharesEmbeddingAndReservesQuarterOfRecallForOtherPublicBases() {
    UUID owner = UUID.randomUUID();
    UUID selected = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    EmbeddingClient embedding = mock(EmbeddingClient.class);
    RetrievalMapper mapper = mock(RetrievalMapper.class);
    KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
    KnowledgeBase selectedBase = new KnowledgeBase();
    selectedBase.setId(selected);
    KnowledgeBase otherBase = new KnowledgeBase();
    otherBase.setId(other);
    when(knowledgeBases.selectWithDocumentCount(null, Integer.MAX_VALUE, 0))
        .thenReturn(List.of(selectedBase, otherBase));
    EmbeddingBinding binding = new EmbeddingBinding("model-id", "provider", "model", 2);
    when(mapper.activeModelBindings(owner)).thenReturn(List.of(binding));
    when(embedding.embed("model-id", "provider", "model", 2, List.of("问题")))
        .thenReturn(List.of(new float[] {1, 0}));
    when(mapper.searchIn(
            owner, List.of(selected), "[1.0, 0.0]", "model-id", "provider", "model", 2, 3))
        .thenReturn(List.of());
    when(mapper.searchIn(
            owner, List.of(other), "[1.0, 0.0]", "model-id", "provider", "model", 2, 1))
        .thenReturn(List.of());
    RetrievalService service =
        new RetrievalService(
            embedding, mapper, knowledgeBases, new RagProperties(), new CandidateMerge());

    service.retrieveDirectedCandidates(
        owner,
        "Q1",
        "问题",
        List.of(selected),
        new StageBudget("Q1", 4, 1000, 60, 1, true),
        CancellationToken.NONE,
        RagRunTrace.noop());

    verify(embedding, times(1)).embed("model-id", "provider", "model", 2, List.of("问题"));
    verify(mapper)
        .searchIn(owner, List.of(selected), "[1.0, 0.0]", "model-id", "provider", "model", 2, 3);
    verify(mapper)
        .searchIn(owner, List.of(other), "[1.0, 0.0]", "model-id", "provider", "model", 2, 1);
  }
}
