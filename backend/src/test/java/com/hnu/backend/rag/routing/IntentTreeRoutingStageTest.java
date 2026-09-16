package com.hnu.backend.rag.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.intent.IntentNode;
import com.hnu.backend.intent.IntentTreeService;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.RagStageStatus;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.mcp.McpToolDefinition;
import com.hnu.backend.rag.mcp.McpToolRegistry;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.rag.planning.SubQuestion;
import com.hnu.backend.shared.error.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IntentTreeRoutingStageTest {
  private final IntentTreeService tree =
      mock(IntentTreeService.class, withSettings().mockMaker("mock-maker-subclass"));
  private final ChatClient chat = mock(ChatClient.class);
  private final McpToolRegistry tools = mock(McpToolRegistry.class);
  private final RagProperties config = new RagProperties();
  private final IntentTreeRoutingStage stage =
      new IntentTreeRoutingStage(tree, chat, tools, config);

  @AfterEach
  void tearDown() {
    stage.close();
  }

  @Test
  void classifiesAllThreeLeafTypesInOneCallAndRetainsSecondCandidate() {
    UUID kbId = UUID.randomUUID();
    IntentNode kb = node("制度", IntentNode.Kind.KB, List.of(kbId));
    IntentNode chatNode = node("闲聊", IntentNode.Kind.SYSTEM, List.of());
    IntentNode mcp = mcpNode();
    when(tree.activeLeaves()).thenReturn(List.of(kb, chatNode, mcp));
    when(tree.list()).thenReturn(List.of(kb, chatNode, mcp));
    when(tools.availableReadOnlyTools())
        .thenReturn(
            List.of(
                new McpToolDefinition(
                    "calendar.read", "查询日程", true, Map.of("type", "object"), Set.of())));
    when(tools.check("calendar.read", Map.of())).thenReturn(McpToolRegistry.RoutingCheck.ALLOWED);
    QueryPlan plan =
        new QueryPlan(
            "复合问题",
            List.of(
                new SubQuestion("Q1", "休假制度"),
                new SubQuestion("Q2", "你好"),
                new SubQuestion("Q3", "查询日程")));
    String response =
        "{\"routes\":["
            + "{\"subQuestionId\":\"Q1\",\"candidates\":[{\"nodeId\":\""
            + kb.id()
            + "\",\"score\":0.95},{\"nodeId\":\""
            + chatNode.id()
            + "\",\"score\":0.45}],\"reasonCode\":\"AMBIGUOUS\",\"toolArguments\":{}},"
            + "{\"subQuestionId\":\"Q2\",\"candidates\":[{\"nodeId\":\""
            + chatNode.id()
            + "\",\"score\":0.96}],\"reasonCode\":\"MATCHED\",\"toolArguments\":{}},"
            + "{\"subQuestionId\":\"Q3\",\"candidates\":[{\"nodeId\":\""
            + mcp.id()
            + "\",\"score\":0.97}],\"reasonCode\":\"MATCHED\",\"toolArguments\":{}}]}";
    when(chat.generate(any(), any()))
        .thenReturn(new ChatClient.Generation(response, "model-id", "provider", "model"));

    RoutingPlan result = stage.execute(plan, RagRunTrace.noop());

    assertEquals(
        List.of(IntentType.KNOWLEDGE_RETRIEVAL, IntentType.SYSTEM_CHAT, IntentType.MCP_TOOL),
        result.routes().stream().map(IntentRoute::intent).toList());
    assertEquals(List.of(kbId), result.routes().getFirst().knowledgeBaseIds());
    assertEquals(chatNode.id(), result.routes().getFirst().secondCandidateId());
    assertEquals(RoutingReasonCode.AMBIGUOUS, result.routes().getFirst().reasonCode());
    assertEquals(0.45, result.routes().getFirst().secondCandidateScore());
    verify(tools).check("calendar.read", Map.of());
    ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
    verify(chat, times(1)).generate(any(), input.capture());
    assertTrue(input.getValue().contains("休假制度"));
    assertTrue(input.getValue().contains("你好"));
    assertTrue(input.getValue().contains("查询日程"));
    assertTrue(input.getValue().contains(kb.id().toString()));
    assertTrue(input.getValue().contains(mcp.id().toString()));
  }

  @Test
  void executesConfiguredReadOnlyToolOnlyAfterRegistryCheck() {
    IntentNode mcp = mcpNode();
    active(mcp);
    when(tools.availableReadOnlyTools())
        .thenReturn(
            List.of(
                new McpToolDefinition(
                    "calendar.read", "查询日程", true, Map.of("type", "object"), Set.of())));
    when(tools.check("calendar.read", Map.of("date", "2026-09-16")))
        .thenReturn(McpToolRegistry.RoutingCheck.ALLOWED);
    when(chat.generate(any(), any()))
        .thenReturn(generation(response(mcp.id(), 0.97, "{\"date\":\"2026-09-16\"}")));

    IntentRoute result =
        stage.execute(QueryPlan.fallback("今天的日程"), RagRunTrace.noop()).routes().getFirst();

    assertEquals(IntentType.MCP_TOOL, result.intent());
    assertEquals("calendar.read", result.toolHint());
    assertEquals("2026-09-16", result.toolArguments().get("date"));
    verify(tools).check("calendar.read", Map.of("date", "2026-09-16"));
  }

  @Test
  void emptyTreeAndDisabledOrInvalidLeavesSkipClassification() {
    QueryPlan plan = twoQuestions();
    assertFallback(plan, "INTENT_TREE_EMPTY");
    IntentNode disabled =
        new IntentNode(
            UUID.randomUUID(),
            null,
            "停用",
            "",
            List.of(),
            IntentNode.Kind.SYSTEM,
            null,
            List.of(),
            false,
            0);
    when(tree.list()).thenReturn(List.of(disabled));
    assertFallback(plan, "INTENT_TREE_NO_VALID_LEAVES");
    verify(chat, never()).generate(any(), any());
  }

  @Test
  void lowConfidenceFallsBackWithFullPublicKnowledgeScope() {
    IntentNode kb = node("制度", IntentNode.Kind.KB, List.of(UUID.randomUUID()));
    active(kb);
    when(chat.generate(any(), any())).thenReturn(generation(response(kb.id(), 0.2, "{}")));
    assertFallback(QueryPlan.fallback("制度"), "INTENT_TREE_LOW_CONFIDENCE");
    verify(chat, times(1)).generate(any(), any());
  }

  @Test
  void invalidOutputFallsBackWithFullPublicKnowledgeScope() {
    IntentNode kb = node("制度", IntentNode.Kind.KB, List.of(UUID.randomUUID()));
    active(kb);
    when(chat.generate(any(), any())).thenReturn(generation("{\"routes\":[]}"));
    assertFallback(twoQuestions(), "INTENT_TREE_INVALID_OUTPUT");
    verify(chat, times(1)).generate(any(), any());
  }

  @Test
  void rejectedToolArgumentsFallBackWithoutExecutingTool() {
    IntentNode mcp = mcpNode();
    active(mcp);
    when(chat.generate(any(), any()))
        .thenReturn(generation(response(mcp.id(), 0.9, "{\"date\":\"2026-09-16\"}")));
    when(tools.check(any(), any())).thenReturn(McpToolRegistry.RoutingCheck.INVALID_ARGUMENTS);
    assertFallback(QueryPlan.fallback("日程"), "INTENT_TREE_INVALID_OUTPUT");
    verify(tools).check("calendar.read", Map.of("date", "2026-09-16"));
  }

  @Test
  void classifierTimeoutFallsBackAfterOneCall() {
    IntentNode kb = node("制度", IntentNode.Kind.KB, List.of(UUID.randomUUID()));
    active(kb);
    config.getPipeline().getRouting().setTimeoutMs(10);
    when(chat.generate(any(), any()))
        .thenAnswer(
            ignored -> {
              Thread.sleep(1000);
              return generation(response(kb.id(), 0.9, "{}"));
            });
    assertFallback(QueryPlan.fallback("制度"), "INTENT_TREE_TIMEOUT");
    verify(chat, times(1)).generate(any(), any());
  }

  @Test
  void modelFailureFallsBackAfterOneCall() {
    IntentNode kb = node("制度", IntentNode.Kind.KB, List.of(UUID.randomUUID()));
    active(kb);
    when(chat.generate(any(), any())).thenThrow(new IllegalStateException("model unavailable"));
    assertFallback(QueryPlan.fallback("制度"), "INTENT_TREE_CLASSIFICATION_FAILED");
    verify(chat, times(1)).generate(any(), any());
  }

  @Test
  void interruptedRequestDoesNotCallClassifierOrFallBack() {
    RagRunTrace trace = trace();
    Thread.currentThread().interrupt();
    try {
      ApiException error =
          assertThrows(ApiException.class, () -> stage.execute(QueryPlan.fallback("制度"), trace));
      assertEquals("GENERATION_CANCELLED", error.code());
      assertEquals(
          RagStageStatus.CANCELLED,
          trace.finish(RagRunStatus.CANCELLED, error.code()).stages().getFirst().status());
      verify(chat, never()).generate(any(), any());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void cancellationDoesNotBecomeKnowledgeFallback() {
    IntentNode kb = node("制度", IntentNode.Kind.KB, List.of(UUID.randomUUID()));
    active(kb);
    when(chat.generate(any(), any())).thenThrow(ApiException.cancelled());
    RagRunTrace trace = trace();
    ApiException error =
        assertThrows(ApiException.class, () -> stage.execute(QueryPlan.fallback("制度"), trace));
    assertEquals("GENERATION_CANCELLED", error.code());
    assertEquals(
        RagStageStatus.CANCELLED,
        trace.finish(RagRunStatus.CANCELLED, error.code()).stages().getFirst().status());
  }

  private void active(IntentNode node) {
    when(tree.activeLeaves()).thenReturn(List.of(node));
    when(tree.list()).thenReturn(List.of(node));
    when(tools.availableReadOnlyTools()).thenReturn(List.of());
  }

  private void assertFallback(QueryPlan plan, String expectedTraceReason) {
    RagRunTrace trace = trace();
    RoutingPlan result = stage.execute(plan, trace);
    assertEquals(plan.subQuestions().size(), result.routes().size());
    for (IntentRoute route : result.routes()) {
      assertEquals(IntentType.KNOWLEDGE_RETRIEVAL, route.intent());
      assertEquals(RoutingReasonCode.INTENT_TREE_FALLBACK, route.reasonCode());
      assertNull(route.knowledgeBaseIds());
      assertNull(route.toolHint());
    }
    var snapshot = trace.finish(RagRunStatus.COMPLETED, null);
    assertEquals(RagStageStatus.DEGRADED, snapshot.stages().getFirst().status());
    assertEquals(expectedTraceReason, snapshot.stages().getFirst().reasonCode());
  }

  private RagRunTrace trace() {
    return new RagRunTrace(UUID.randomUUID(), OffsetDateTime.now(), System.nanoTime());
  }

  private QueryPlan twoQuestions() {
    return new QueryPlan("复合问题", List.of(new SubQuestion("Q1", "制度"), new SubQuestion("Q2", "天气")));
  }

  private ChatClient.Generation generation(String content) {
    return new ChatClient.Generation(content, "id", "provider", "model");
  }

  private String response(UUID nodeId, double score, String arguments) {
    return "{\"routes\":[{\"subQuestionId\":\"Q1\",\"candidates\":[{\"nodeId\":\""
        + nodeId
        + "\",\"score\":"
        + score
        + "}],\"reasonCode\":\"MATCHED\",\"toolArguments\":"
        + arguments
        + "}]}";
  }

  private IntentNode mcpNode() {
    return new IntentNode(
        UUID.randomUUID(),
        null,
        "日程查询",
        "查询日程",
        List.of("今天有什么日程"),
        IntentNode.Kind.MCP,
        "calendar.read",
        List.of(),
        true,
        0);
  }

  private IntentNode node(String name, IntentNode.Kind kind, List<UUID> kbIds) {
    return new IntentNode(
        UUID.randomUUID(), null, name, name, List.of(name), kind, null, kbIds, true, 0);
  }
}
