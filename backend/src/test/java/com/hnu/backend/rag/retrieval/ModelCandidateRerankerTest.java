package com.hnu.backend.rag.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hnu.backend.model.client.RerankClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelCandidateRerankerTest {
  @Test
  void mapsProviderDocumentIndexesBackToStableCandidateIds() {
    RerankClient client = mock(RerankClient.class);
    EvidenceCandidate first =
        CandidateMergeTest.candidate(CandidateMergeTest.id(1), "Q1", "embedding", .8, 1, .1);
    EvidenceCandidate second =
        CandidateMergeTest.candidate(CandidateMergeTest.id(2), "Q1", "embedding", .7, 2, .09);
    when(client.rerank("问题", List.of("正文", "正文")))
        .thenReturn(
            new RerankClient.Generation(
                List.of(new RerankClient.Rank(1, .9), new RerankClient.Rank(0, .2)),
                "rerank",
                "test",
                "model",
                "request",
                12,
                false));

    CandidateReranker.Output result =
        new ModelCandidateReranker(client).rerank("问题", List.of(first, second));

    assertEquals(
        List.of(second.candidateId(), first.candidateId()),
        result.scores().stream().map(CandidateReranker.Score::candidateId).toList());
    assertEquals(12, result.totalTokens());
  }
}
