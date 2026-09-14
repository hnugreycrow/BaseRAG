package com.hnu.backend.rag.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.configuration.RagProperties;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExecutionStageTest {
  private final List<ExecutionStage> stages = new ArrayList<>();

  @AfterEach
  void tearDown() {
    stages.forEach(ExecutionStage::close);
  }

  @Test
  void executesKnowledgeToolAndSystemRoutesIndependently() {
    RetrievalService retrieval = mock(RetrievalService.class);
    McpToolExecutor tools = mock(McpToolExecutor.class);
    ExecutionStage stage = stage(retrieval, tools, new RagProperties());
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
    when(retrieval.retrieveCandidates(eq("Q1"), eq("年假制度"), isNull(), any(), any()))
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

    ExecutionResult result = stage.execute(plan, routing);

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
  void isolatesFailureAndTimeoutFromOtherSubQuestions() {
    RetrievalService retrieval = mock(RetrievalService.class);
    McpToolExecutor tools = mock(McpToolExecutor.class);
    RagProperties config = new RagProperties();
    config.getSearch().getChannels().setTimeoutMs(20);
    ExecutionStage stage = stage(retrieval, tools, config);
    QueryPlan plan =
        new QueryPlan("两个问题", List.of(new SubQuestion("Q1", "慢问题"), new SubQuestion("Q2", "失败问题")));
    RoutingPlan routing = new RoutingPlan(List.of(knowledge("Q1"), knowledge("Q2")));
    when(retrieval.retrieveCandidates(eq("Q1"), anyString(), isNull(), any(), any()))
        .thenAnswer(
            ignored -> {
              Thread.sleep(1000);
              return List.of();
            });
    when(retrieval.retrieveCandidates(eq("Q2"), anyString(), isNull(), any(), any()))
        .thenThrow(new IllegalStateException("boom"));

    ExecutionResult result = stage.execute(plan, routing);

    assertEquals(SubQuestionExecution.Status.TIMEOUT, result.subQuestions().get(0).status());
    assertEquals(SubQuestionExecution.Status.FAILED, result.subQuestions().get(1).status());
  }

  @Test
  void cancellationStopsRunningSubQuestions() throws Exception {
    RetrievalService retrieval = mock(RetrievalService.class);
    McpToolExecutor tools = mock(McpToolExecutor.class);
    RagProperties config = new RagProperties();
    config.getPipeline().setMaxSubQuestions(1);
    ExecutionStage stage = stage(retrieval, tools, config);
    QueryPlan plan = new QueryPlan("问题", List.of(new SubQuestion("Q1", "运行中")));
    RoutingPlan routing = new RoutingPlan(List.of(knowledge("Q1")));
    AtomicBoolean cancelled = new AtomicBoolean();
    when(retrieval.retrieveCandidates(eq("Q1"), anyString(), isNull(), any(), any()))
        .thenAnswer(
            ignored -> {
              Thread.sleep(1000);
              return List.of();
            });

    var future =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> stage.execute(plan, routing, null, cancelled::get));
    Thread.sleep(30);
    cancelled.set(true);

    CompletionException error = assertThrows(CompletionException.class, future::join);
    assertEquals(ApiException.class, error.getCause().getClass());
    verify(retrieval, timeout(1000))
        .retrieveCandidates(eq("Q1"), anyString(), isNull(), any(), any());
  }

  private ExecutionStage stage(
      RetrievalService retrieval, McpToolExecutor tools, RagProperties config) {
    ExecutionStage stage = new ExecutionStage(retrieval, tools, new CandidateMerge(), config);
    stages.add(stage);
    return stage;
  }

  private IntentRoute knowledge(String id) {
    return IntentRoute.knowledgeFallback(id, 1, RoutingReasonCode.KNOWLEDGE_SOURCE_REQUIRED);
  }
}
