package com.hnu.backend.rag.rerank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.rag.execution.ExecutionResult;
import com.hnu.backend.rag.execution.RagBudgetSnapshot;
import com.hnu.backend.rag.execution.SubQuestionExecution;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.rag.planning.SubQuestion;
import com.hnu.backend.rag.retrieval.CandidateMerge;
import com.hnu.backend.rag.retrieval.CandidateMergeTest;
import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import com.hnu.backend.rag.routing.IntentType;
import com.hnu.backend.shared.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RerankStageTest {
  private final List<RerankStage> stages = new ArrayList<>();

  @AfterEach
  void closeStages() {
    stages.forEach(RerankStage::close);
  }

  @Test
  void ranksByModelScoreAndProtectsSubQuestionCoverage() {
    CandidateReranker reranker = mock(CandidateReranker.class);
    RagProperties rag = new RagProperties();
    rag.getPipeline().getRerank().setSelectedEvidence(2);
    EvidenceCandidate q1Best = candidate(1, "Q1", .10);
    EvidenceCandidate q1Second = candidate(2, "Q1", .09);
    EvidenceCandidate q2 = candidate(3, "Q2", .01);
    List<EvidenceCandidate> candidates = List.of(q1Best, q1Second, q2);
    when(reranker.rerank("组合问题", candidates))
        .thenReturn(output(List.of(score(q1Best, .9), score(q1Second, .8), score(q2, .1))));

    RerankResult result =
        stage(reranker).execute(plan(), execution(rag, candidates), candidates, () -> false);

    assertEquals(RerankResult.Status.SUCCESS, result.status());
    assertEquals(
        List.of(q1Best.candidateId(), q2.candidateId()),
        result.selectedCandidates().stream().map(EvidenceCandidate::candidateId).toList());
    assertEquals(
        List.of(.9, .8, .1),
        result.decisions().stream().map(RerankDecision::relevanceScore).toList());
  }

  @Test
  void submitsAtMostFortyCandidatesInOneCall() {
    CandidateReranker reranker = mock(CandidateReranker.class);
    RagProperties rag = new RagProperties();
    List<EvidenceCandidate> candidates = new ArrayList<>();
    for (int index = 1; index <= 45; index++) candidates.add(candidate(index, "Q1", 1.0 / index));
    when(reranker.rerank(eq("组合问题"), anyList()))
        .thenAnswer(
            invocation -> {
              List<EvidenceCandidate> input = invocation.getArgument(1);
              return output(input.stream().map(candidate -> score(candidate, .5)).toList());
            });

    RerankResult result =
        stage(reranker).execute(plan(), execution(rag, candidates), candidates, () -> false);

    verify(reranker)
        .rerank(eq("组合问题"), org.mockito.ArgumentMatchers.argThat(list -> list.size() == 40));
    assertEquals(40, result.decisions().size());
    assertEquals(8, result.selectedCandidates().size());
  }

  @Test
  void noopUsesDeterministicFusionOrderAndMarksDegraded() {
    CandidateReranker reranker = mock(CandidateReranker.class);
    RagProperties rag = new RagProperties();
    rag.getPipeline().getRerank().setSelectedEvidence(2);
    EvidenceCandidate q1 = candidate(1, "Q1", .10);
    EvidenceCandidate q1Other = candidate(2, "Q1", .09);
    EvidenceCandidate q2 = candidate(3, "Q2", .01);
    List<EvidenceCandidate> candidates = List.of(q1, q1Other, q2);
    when(reranker.rerank("组合问题", candidates))
        .thenReturn(
            new CandidateReranker.Output(List.of(), "rerank-noop", "noop", "noop", null, 0, true));

    RerankResult result =
        stage(reranker).execute(plan(), execution(rag, candidates), candidates, () -> false);

    assertEquals(RerankResult.Status.DEGRADED, result.status());
    assertEquals("RERANK_NOOP", result.reasonCode());
    assertEquals(
        List.of(q1.candidateId(), q2.candidateId()),
        result.selectedCandidates().stream().map(EvidenceCandidate::candidateId).toList());
    assertTrue(result.decisions().stream().allMatch(decision -> decision.relevanceScore() == -1));
  }

  @Test
  void disabledRerankDoesNotCallModel() {
    CandidateReranker reranker = mock(CandidateReranker.class);
    RagProperties rag = new RagProperties();
    rag.getPipeline().getRerank().setEnabled(false);
    EvidenceCandidate candidate = candidate(1, "Q1", .10);

    RerankResult result =
        stage(reranker)
            .execute(plan(), execution(rag, List.of(candidate)), List.of(candidate), () -> false);

    assertEquals(RerankResult.Status.DISABLED, result.status());
    verify(reranker, never()).rerank(eq("组合问题"), anyList());
  }

  @Test
  void invalidMappingDegradesInsteadOfDroppingEvidence() {
    CandidateReranker reranker = mock(CandidateReranker.class);
    RagProperties rag = new RagProperties();
    EvidenceCandidate candidate = candidate(1, "Q1", .10);
    when(reranker.rerank("组合问题", List.of(candidate))).thenReturn(output(List.of()));

    RerankResult result =
        stage(reranker)
            .execute(plan(), execution(rag, List.of(candidate)), List.of(candidate), () -> false);

    assertEquals(RerankResult.Status.DEGRADED, result.status());
    assertEquals(List.of(candidate), result.selectedCandidates());
  }

  @Test
  void modelTimeoutDegradesToDeterministicEvidence() {
    CandidateReranker reranker = mock(CandidateReranker.class);
    RagProperties rag = new RagProperties();
    EvidenceCandidate candidate = candidate(1, "Q1", .10);
    when(reranker.rerank("组合问题", List.of(candidate)))
        .thenAnswer(
            ignored -> {
              Thread.sleep(5_000);
              return output(List.of(score(candidate, .5)));
            });
    AiProperties ai = new AiProperties();
    ai.getRerank().setTimeoutMs(20);

    RerankResult result =
        stage(reranker, ai)
            .execute(plan(), execution(rag, List.of(candidate)), List.of(candidate), () -> false);

    assertEquals(RerankResult.Status.DEGRADED, result.status());
    assertEquals("RERANK_TIMEOUT", result.reasonCode());
    assertEquals(List.of(candidate), result.selectedCandidates());
  }

  @Test
  void cancellationInterruptsRunningModelAndPropagates() throws Exception {
    CandidateReranker reranker = mock(CandidateReranker.class);
    RagProperties rag = new RagProperties();
    EvidenceCandidate candidate = candidate(1, "Q1", .10);
    AtomicBoolean cancelled = new AtomicBoolean();
    when(reranker.rerank("组合问题", List.of(candidate)))
        .thenAnswer(
            ignored -> {
              Thread.sleep(5_000);
              return output(List.of(score(candidate, .5)));
            });
    RerankStage stage = stage(reranker);

    var future =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () ->
                stage.execute(
                    plan(),
                    execution(rag, List.of(candidate)),
                    List.of(candidate),
                    cancelled::get));
    Thread.sleep(30);
    cancelled.set(true);

    CompletionException error = assertThrows(CompletionException.class, future::join);
    assertEquals(ApiException.class, error.getCause().getClass());
    assertEquals("GENERATION_CANCELLED", ((ApiException) error.getCause()).code());
  }

  private RerankStage stage(CandidateReranker reranker) {
    return stage(reranker, new AiProperties());
  }

  private RerankStage stage(CandidateReranker reranker, AiProperties ai) {
    RerankStage stage = new RerankStage(reranker, new CandidateMerge(), ai);
    stages.add(stage);
    return stage;
  }

  private QueryPlan plan() {
    return new QueryPlan(
        "组合问题", List.of(new SubQuestion("Q1", "问题一"), new SubQuestion("Q2", "问题二")));
  }

  private ExecutionResult execution(RagProperties rag, List<EvidenceCandidate> candidates) {
    return new ExecutionResult(
        candidates, List.of(execution("Q1"), execution("Q2")), RagBudgetSnapshot.from(rag));
  }

  private SubQuestionExecution execution(String id) {
    return new SubQuestionExecution(
        id,
        IntentType.KNOWLEDGE_RETRIEVAL,
        SubQuestionExecution.Status.SUCCESS,
        List.of(),
        null,
        "RETRIEVAL_COMPLETED",
        1);
  }

  private CandidateReranker.Output output(List<CandidateReranker.Score> scores) {
    return new CandidateReranker.Output(
        scores, "qwen3-rerank", "bailian", "qwen3-rerank", "request", 10, false);
  }

  private CandidateReranker.Score score(EvidenceCandidate candidate, double score) {
    return new CandidateReranker.Score(candidate.candidateId(), score);
  }

  private EvidenceCandidate candidate(long id, String questionId, double contribution) {
    return CandidateMergeTest.candidate(
        CandidateMergeTest.id(id), questionId, "embedding", .8, 1, contribution);
  }
}
