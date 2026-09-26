package com.hnu.backend.rag.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.RagStageStatus;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.mcp.McpToolExecutor;
import com.hnu.backend.rag.mcp.ToolObservation;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.rag.planning.SubQuestion;
import com.hnu.backend.rag.retrieval.CandidateMerge;
import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import com.hnu.backend.rag.retrieval.RetrievalService;
import com.hnu.backend.rag.routing.IntentRoute;
import com.hnu.backend.rag.routing.IntentType;
import com.hnu.backend.rag.routing.RoutingPlan;
import com.hnu.backend.rag.routing.RoutingReasonCode;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExecutionStageTest {
  private final UUID ownerId = UUID.randomUUID();
  private final List<ExecutionStage> stages = new ArrayList<>();

  @Test
  void cancellationBeforeWorkerStartRecordsNoExecutionAndDoesNotCallRetrieval() throws Exception {
    RetrievalService retrieval = mock(RetrievalService.class);
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    var occupied = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    executor.submit(
        () -> {
          occupied.countDown();
          try {
            release.await();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(occupied.await(2, java.util.concurrent.TimeUnit.SECONDS));
    var stage =
        new ExecutionStage(
            retrieval,
            mock(McpToolExecutor.class),
            new CandidateMerge(),
            new RagProperties(),
            executor);
    stages.add(stage);
    var trace = new RagRunTrace(UUID.randomUUID(), OffsetDateTime.now(), System.nanoTime());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    // 前两个检查分别在检索入口与提交后等待处，取消在任务排队后生效。
    CancellationToken cancelled = () -> calls.incrementAndGet() >= 2;
    try {
      assertThrows(
          ApiException.class,
          () ->
              stage.execute(
                  ownerId,
                  new QueryPlan("问题", List.of(new SubQuestion("Q1", "问题"))),
                  new RoutingPlan(List.of(knowledge("Q1"))),
                  null,
                  cancelled,
                  trace));
      var snapshot = trace.finish(RagRunStatus.CANCELLED, "GENERATION_CANCELLED");
      var task =
          snapshot.stages().stream()
              .filter(s -> s.name() == RagStageName.SUBQUESTION_EXECUTION)
              .findFirst()
              .orElseThrow();
      assertEquals(RagStageStatus.CANCELLED, task.status());
      assertEquals(0, task.elapsedMs());
      assertTrue(task.queueMs() >= 0);
      verifyNoInteractions(retrieval);
    } finally {
      release.countDown();
    }
  }

  @Test
  void recordsWorkerCompletionInsteadOfFutureCollectionAndKeepsParents() throws Exception {
    RetrievalService retrieval = mock(RetrievalService.class);
    var stage = stage(retrieval, mock(McpToolExecutor.class), new RagProperties());
    var fastReturned = new java.util.concurrent.CountDownLatch(1);
    var trace = new RagRunTrace(UUID.randomUUID(), OffsetDateTime.now(), System.nanoTime());
    when(retrieval.retrieveCandidates(
            eq(ownerId),
            anyString(),
            anyString(),
            isNull(),
            any(),
            any(),
            any(com.hnu.backend.observability.trace.TraceContext.class)))
        .thenAnswer(
            call -> {
              String id = call.getArgument(1);
              com.hnu.backend.observability.trace.TraceContext context = call.getArgument(6);
              return context.execute(
                  RagStageName.DATABASE_RETRIEVAL,
                  id,
                  1,
                  span -> {
                    if (id.equals("Q1")) {
                      try {
                        assertTrue(fastReturned.await(2, java.util.concurrent.TimeUnit.SECONDS));
                        // 留出明确的执行差异，以验证 Q2 不包含等待 Q1 被收取的时间。
                        Thread.sleep(150);
                      } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw ApiException.cancelled();
                      }
                    } else {
                      fastReturned.countDown();
                    }
                    return List.of();
                  });
            });
    var plan =
        new QueryPlan(
            "并行问题", List.of(new SubQuestion("Q1", "slow"), new SubQuestion("Q2", "fast")));
    stage.execute(
        ownerId,
        plan,
        new RoutingPlan(List.of(knowledge("Q1"), knowledge("Q2"))),
        null,
        () -> false,
        trace);
    var snapshot = trace.finish(RagRunStatus.COMPLETED, null);
    var children =
        snapshot.stages().stream()
            .filter(s -> s.name() == RagStageName.SUBQUESTION_EXECUTION)
            .toList();
    var slow =
        children.stream().filter(s -> s.subQuestionId().equals("Q1")).findFirst().orElseThrow();
    var fast =
        children.stream().filter(s -> s.subQuestionId().equals("Q2")).findFirst().orElseThrow();
    assertTrue(fast.completedAt().isBefore(slow.completedAt()));
    assertTrue(slow.elapsedMs() >= fast.elapsedMs() + 100);
    assertEquals(slow.parentStageId(), fast.parentStageId());
    assertTrue(fast.queueMs() >= 0);
    for (var child : children) {
      assertTrue(
          snapshot.stages().stream()
              .anyMatch(
                  s ->
                      s.name() == RagStageName.DATABASE_RETRIEVAL
                          && child.id().equals(s.parentStageId())
                          && child.subQuestionId().equals(s.subQuestionId())));
    }
  }

  @AfterEach
  void tearDown() {
    stages.forEach(ExecutionStage::close);
  }

  @Test
  void executesKnowledgeToolAndSystemRoutesIndependently() {
    RetrievalService retrievalService = mock(RetrievalService.class);
    McpToolExecutor tools = mock(McpToolExecutor.class);
    ExecutionStage stage = stage(retrievalService, tools, new RagProperties());
    QueryPlan plan =
        new QueryPlan(
            "组合问题",
            List.of(
                new SubQuestion("Q1", "年假制度"),
                new SubQuestion("Q2", "今日排班"),
                new SubQuestion("Q3", "你好")));
    RoutingPlan routing =
        new RoutingPlan(
            List.of(
                knowledge("Q1"),
                new IntentRoute(
                    "Q2",
                    IntentType.MCP_TOOL,
                    1,
                    "calendar.read",
                    Map.of("date", "2026-09-14"),
                    RoutingReasonCode.EXTERNAL_SOURCE_REQUIRED),
                new IntentRoute(
                    "Q3",
                    IntentType.SYSTEM_CHAT,
                    1,
                    null,
                    Map.of(),
                    RoutingReasonCode.GENERAL_CHAT)));
    EvidenceCandidate candidate =
        com.hnu.backend.rag.retrieval.CandidateMergeTest.candidate(
            com.hnu.backend.rag.retrieval.CandidateMergeTest.id(1), "Q1", "model-a", .9, 1, .05);
    when(retrievalService.retrieveCandidates(
            eq(ownerId), eq("Q1"), eq("年假制度"), isNull(), any(), any()))
        .thenReturn(List.of(candidate));
    when(tools.execute(any()))
        .thenReturn(
            new ToolObservation(
                "calendar.read",
                "{\"date\":\"2026-09-14\"}",
                "test#calendar.read",
                ToolObservation.Status.SUCCESS,
                "TOOL_COMPLETED",
                "{}",
                false,
                1));

    ExecutionResult result = stage.execute(ownerId, plan, routing);

    assertEquals(1, result.candidates().size());
    assertEquals(
        List.of(
            SubQuestionExecution.Status.SUCCESS,
            SubQuestionExecution.Status.SUCCESS,
            SubQuestionExecution.Status.SKIPPED),
        result.subQuestions().stream().map(SubQuestionExecution::status).toList());
    assertEquals("calendar.read", result.subQuestions().get(1).toolObservation().toolName());
  }

  @Test
  void isolatesChannelTimeoutFromOtherSubQuestions() {
    RetrievalService retrievalService = mock(RetrievalService.class);
    McpToolExecutor tools = mock(McpToolExecutor.class);
    RagProperties config = new RagProperties();
    ExecutionStage stage = stage(retrievalService, tools, config);
    QueryPlan plan =
        new QueryPlan("两个问题", List.of(new SubQuestion("Q1", "慢问题"), new SubQuestion("Q2", "失败问题")));
    RoutingPlan routing = new RoutingPlan(List.of(knowledge("Q1"), knowledge("Q2")));
    when(retrievalService.retrieveCandidates(
            eq(ownerId), eq("Q1"), anyString(), isNull(), any(), any()))
        .thenThrow(ApiException.upstream(ErrorCode.SUBQUESTION_TIMEOUT, "向量检索通道超时"));
    when(retrievalService.retrieveCandidates(
            eq(ownerId), eq("Q2"), anyString(), isNull(), any(), any()))
        .thenThrow(new IllegalStateException("boom"));

    ExecutionResult result = stage.execute(ownerId, plan, routing);

    assertEquals(SubQuestionExecution.Status.TIMEOUT, result.subQuestions().get(0).status());
    assertEquals(SubQuestionExecution.Status.FAILED, result.subQuestions().get(1).status());
  }

  @Test
  void cancellationStopsRunningSubQuestions() throws Exception {
    RetrievalService retrievalService = mock(RetrievalService.class);
    McpToolExecutor tools = mock(McpToolExecutor.class);
    RagProperties config = new RagProperties();
    config.getPipeline().setMaxSubQuestions(1);
    ExecutionStage stage = stage(retrievalService, tools, config);
    QueryPlan plan = new QueryPlan("问题", List.of(new SubQuestion("Q1", "运行中")));
    RoutingPlan routing = new RoutingPlan(List.of(knowledge("Q1")));
    AtomicBoolean cancelled = new AtomicBoolean();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    when(retrievalService.retrieveCandidates(
            eq(ownerId), eq("Q1"), anyString(), isNull(), any(), any()))
        .thenAnswer(
            ignored -> {
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                  throw new AssertionError("测试未释放模型任务");
                }
              } catch (InterruptedException expected) {
                interrupted.countDown();
                throw expected;
              }
              return List.of();
            });

    var future =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> stage.execute(ownerId, plan, routing, null, cancelled::get));
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS), "取消前必须已进入工作任务");
      cancelled.set(true);

      ExecutionException error =
          assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
      assertEquals(ApiException.class, error.getCause().getClass());
      verify(retrievalService, timeout(1000))
          .retrieveCandidates(eq(ownerId), eq("Q1"), anyString(), isNull(), any(), any());
      assertTrue(interrupted.await(5, TimeUnit.SECONDS), "取消必须中断正在运行的任务");
    } finally {
      cancelled.set(true);
      release.countDown();
      future.cancel(true);
    }
  }

  @Test
  void recordsChannelTimeoutOnOuterSubQuestionSpan() {
    RetrievalService retrievalService = mock(RetrievalService.class);
    McpToolExecutor tools = mock(McpToolExecutor.class);
    RagProperties config = new RagProperties();
    ExecutionStage stage = stage(retrievalService, tools, config);
    QueryPlan plan = new QueryPlan("问题", List.of(new SubQuestion("Q1", "慢问题")));
    RoutingPlan routing = new RoutingPlan(List.of(knowledge("Q1")));
    RagRunTrace trace =
        new RagRunTrace(UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC), System.nanoTime());
    when(retrievalService.retrieveCandidates(
            eq(ownerId),
            eq("Q1"),
            anyString(),
            isNull(),
            any(),
            any(),
            any(com.hnu.backend.observability.trace.TraceContext.class)))
        .thenThrow(ApiException.upstream(ErrorCode.SUBQUESTION_TIMEOUT, "向量检索通道超时"));

    ExecutionResult result = stage.execute(ownerId, plan, routing, null, () -> false, trace);
    var snapshot = trace.finish(RagRunStatus.COMPLETED, null);
    var subQuestion =
        snapshot.stages().stream()
            .filter(value -> value.name() == RagStageName.SUBQUESTION_EXECUTION)
            .findFirst()
            .orElseThrow();

    assertEquals(SubQuestionExecution.Status.TIMEOUT, result.subQuestions().getFirst().status());
    assertEquals(RagStageStatus.DEGRADED, subQuestion.status());
    assertEquals("SUBQUESTION_TIMEOUT", subQuestion.reasonCode());
  }

  private ExecutionStage stage(
      RetrievalService retrievalService, McpToolExecutor tools, RagProperties config) {
    ExecutionStage stage =
        new ExecutionStage(retrievalService, tools, new CandidateMerge(), config);
    stages.add(stage);
    return stage;
  }

  private IntentRoute knowledge(String id) {
    return IntentRoute.knowledgeFallback(id, 1, RoutingReasonCode.KNOWLEDGE_SOURCE_REQUIRED);
  }
}
