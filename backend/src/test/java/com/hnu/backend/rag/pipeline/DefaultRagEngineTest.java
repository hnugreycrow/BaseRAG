package com.hnu.backend.rag.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.api.*;
import com.hnu.backend.rag.generation.*;
import com.hnu.backend.rag.memory.RagMemory;
import com.hnu.backend.rag.retrieval.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 在不启动任何外部服务的情况下验证引擎编排与阶段替换。 */
class DefaultRagEngineTest {
  private final RagContextPreparation context = mock(RagContextPreparation.class);
  private final QueryExecution execution = mock(QueryExecution.class);
  private final EvidenceDeduplicator deduplication = mock(EvidenceDeduplicator.class);
  private final EvidenceReranker reranker = mock(EvidenceReranker.class);
  private final PromptAssembler prompts = mock(PromptAssembler.class);
  private final AnswerGeneration answers = mock(AnswerGeneration.class);
  private final EvidenceRetriever retrieval = mock(EvidenceRetriever.class);
  private final RagObserver observer = mock(RagObserver.class);
  private final TestControl control = new TestControl();
  private final DefaultRagEngine engine =
      new DefaultRagEngine(
          context,
          execution,
          deduplication,
          reranker,
          prompts,
          answers,
          retrieval,
          new ContextBuilder());
  private final RagRequest request =
      new RagRequest(
          UUID.randomUUID(),
          "问题",
          UUID.randomUUID(),
          1,
          List.of(UUID.randomUUID()),
          false,
          RagRequest.Mode.CONVERSATION);
  private final AssembledPrompt prompt = mock(AssembledPrompt.class);
  private final QueryPlan plan = QueryPlan.fallback("问题");
  private final RoutingPlan routing = mock(RoutingPlan.class);

  @BeforeEach
  void prepareContext() {
    lenient()
        .when(context.prepare(eq(request), any(RagRunTrace.class), any()))
        .thenReturn(
            new RagContextPreparation.PreparedContext(mock(RagMemory.class), plan, routing));
    lenient().when(prompt.sources()).thenReturn(List.of());
    lenient()
        .when(answers.execute(eq(prompt), any(RagObserver.class), same(control), eq(false)))
        .thenReturn(new AnswerResult("答案", List.of(), List.of(), List.of(), null));
  }

  @Test
  void clarificationStopsEveryExecutionAndAnswerStage() {
    var clarification = mock(com.hnu.backend.rag.clarification.ClarificationDecisionStage.class);
    var option =
        new com.hnu.backend.rag.api.ClarificationContext.Option(UUID.randomUUID(), "OA > 权限");
    var state =
        new com.hnu.backend.rag.api.ClarificationContext(
            null,
            plan,
            java.util.Map.of(),
            List.of(
                new com.hnu.backend.rag.api.ClarificationContext.Ambiguity("Q1", List.of(option))),
            List.of());
    when(clarification.execute(any(), isNull(), any()))
        .thenReturn(
            new com.hnu.backend.rag.clarification.ClarificationDecisionStage.Decision(
                routing, state, false));
    engine.setClarification(clarification);
    var result = engine.execute(request, observer, control, RagRunTrace.noop());
    assertEquals("CLARIFICATION_REQUIRED", result.outcome());
    assertTrue(result.sources().isEmpty());
    assertTrue(result.citations().isEmpty());
    assertNull(result.generation());
    verifyNoInteractions(execution, retrieval, deduplication, reranker, prompts, answers);
  }

  @Test
  void completePipelineUsesOnlyReplaceableStageContracts() {
    ExecutionResult recalled = mock(ExecutionResult.class);
    ExecutionResult merged = mock(ExecutionResult.class);
    DeduplicationResult deduplicated = mock(DeduplicationResult.class);
    RerankResult reranked = mock(RerankResult.class);
    when(execution.executeRetrieval(
            eq(request.ownerId()),
            eq(plan),
            eq(routing),
            eq(request.knowledgeBaseIds()),
            any(),
            any()))
        .thenReturn(recalled);
    when(execution.merge(eq(recalled), any())).thenReturn(merged);
    when(deduplication.execute(anyList(), any())).thenReturn(deduplicated);
    when(reranker.execute(
            eq(plan),
            eq(merged),
            anyList(),
            any(),
            any(com.hnu.backend.observability.trace.TraceContext.class)))
        .thenReturn(reranked);
    when(prompts.assemblePipeline(any(), eq("问题"), eq(plan), eq(routing), eq(merged), anyList()))
        .thenReturn(prompt);

    assertEquals("答案", engine.execute(request, observer, control, RagRunTrace.noop()).content());

    var order = inOrder(context, execution, deduplication, reranker, prompts, observer, answers);
    order.verify(context).prepare(eq(request), any(RagRunTrace.class), any());
    order
        .verify(execution)
        .executeRetrieval(
            eq(request.ownerId()),
            eq(plan),
            eq(routing),
            eq(request.knowledgeBaseIds()),
            any(),
            any());
    order.verify(execution).merge(eq(recalled), any());
    order.verify(deduplication).execute(anyList(), any());
    order
        .verify(reranker)
        .execute(
            eq(plan),
            eq(merged),
            anyList(),
            any(),
            any(com.hnu.backend.observability.trace.TraceContext.class));
    order
        .verify(prompts)
        .assemblePipeline(any(), eq("问题"), eq(plan), eq(routing), eq(merged), anyList());
    order.verify(observer).prepared("问题", List.of());
    order.verify(answers).execute(eq(prompt), any(RagObserver.class), same(control), eq(false));
  }

  @Test
  void systemChatSkipsAllEvidenceStages() {
    when(routing.systemChatOnly()).thenReturn(true);
    when(prompts.assembleSystemChat(any(), eq("问题"), eq(plan), eq(routing))).thenReturn(prompt);
    engine.execute(request, observer, control, RagRunTrace.noop());
    verifyNoInteractions(execution, deduplication, reranker, retrieval);
    verify(observer).prepared(null, List.of());
  }

  @Test
  void legacyModeNeverRunsPlanningOrRouting() {
    var legacy =
        new RagRequest(request.ownerId(), "问题", null, 0, null, false, RagRequest.Mode.LEGACY);
    when(retrieval.retrieve(legacy.ownerId(), "问题")).thenReturn(List.of());
    when(prompts.assembleLegacy(eq("问题"), any())).thenReturn(prompt);
    when(answers.execute(eq(prompt), same(observer), same(control)))
        .thenReturn(new AnswerResult("旧回答", List.of(), List.of(), List.of(), null));
    assertEquals("旧回答", engine.execute(legacy, observer, control, RagRunTrace.noop()).content());
    verifyNoInteractions(context, execution, deduplication, reranker);
  }

  @Test
  void cancelledRequestDoesNotStartAnyStage() {
    control.close();
    assertThrows(
        ApiException.class, () -> engine.execute(request, observer, control, RagRunTrace.noop()));
    verifyNoInteractions(context, execution, prompts, answers);
  }

  @Test
  void persistenceCallbackFailureStopsGeneration() {
    when(routing.systemChatOnly()).thenReturn(true);
    var failure = new IllegalStateException("checkpoint failed");
    doThrow(failure).when(observer).prepared(null, List.of());
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> engine.execute(request, observer, control, RagRunTrace.noop())));
    verifyNoInteractions(prompts, answers);
  }

  private static final class TestControl implements RagExecutionControl {
    private boolean cancelled;

    @Override
    public boolean cancelled() {
      return cancelled;
    }

    @Override
    public void close() {
      cancelled = true;
    }
  }
}
