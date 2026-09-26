package com.hnu.backend.rag.retrieval;

import com.hnu.backend.model.client.RerankClient;
import java.util.List;
import org.springframework.stereotype.Component;

/** 使用中立模型客户端实现候选重排端口，并把供应商下标映射回稳定候选 ID。 */
@Component
public class ModelCandidateReranker implements CandidateReranker {
  private final RerankClient client;

  public ModelCandidateReranker(RerankClient client) {
    this.client = client;
  }

  @Override
  public Output rerank(String standaloneQuestion, List<EvidenceCandidate> candidates) {
    RerankClient.Generation generation =
        client.rerank(
            standaloneQuestion, candidates.stream().map(EvidenceCandidate::content).toList());
    List<Score> scores =
        generation.ranks().stream()
            .map(
                rank ->
                    new Score(candidates.get(rank.index()).candidateId(), rank.relevanceScore()))
            .toList();
    return new Output(
        scores,
        generation.modelId(),
        generation.provider(),
        generation.model(),
        generation.requestId(),
        generation.totalTokens(),
        generation.noop());
  }
}
