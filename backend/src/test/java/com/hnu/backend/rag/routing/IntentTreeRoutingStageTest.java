package com.hnu.backend.rag.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.intent.model.IntentNode;
import com.hnu.backend.intent.snapshot.IntentTreeSnapshot;
import com.hnu.backend.intent.snapshot.IntentTreeSnapshotProvider;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.RagStageStatus;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.configuration.RagProperties;
import com.hnu.backend.rag.mcp.McpToolDefinition;
import com.hnu.backend.rag.mcp.McpToolRegistry;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.rag.planning.SubQuestion;
import com.hnu.backend.rag.prompt.IntentTreeRoutingPrompts;
import com.hnu.backend.shared.error.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class IntentTreeRoutingStageTest {
  private final IntentTreeSnapshotProvider snapshots = mock(IntentTreeSnapshotProvider.class);
  private final ChatClient chat = mock(ChatClient.class);
  private final McpToolRegistry tools = mock(McpToolRegistry.class);
  private final RagProperties config = new RagProperties();
  private final IntentTreeRoutingStage stage =
      new IntentTreeRoutingStage(snapshots, chat, tools, config);

  @AfterEach
  void tearDown() {
    stage.close();
  }

  @Test
  void classifiesAllThreeLeafTypesInOneCallAndRetainsSecondCandidate(CapturedOutput output) {
    UUID kbId = UUID.randomUUID();
    IntentNode kb = node("制度", IntentNode.Kind.KB, List.of(kbId));
    IntentNode chatNode = node("闲聊", IntentNode.Kind.SYSTEM, List.of());
    IntentNode mcp = mcpNode();
    McpToolDefinition calendar = calendarTool();
    when(snapshots.snapshot())
        .thenReturn(IntentTreeSnapshot.from(List.of(kb, chatNode, mcp), List.of(calendar)));
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

    RagRunTrace trace = trace();
    RoutingPlan result = stage.execute(plan, trace);

    assertTrue(output.getOut().contains("intent routing completed runId=" + trace.runId()));
    assertTrue(output.getOut().contains("\"subQuestionId\":\"Q1\""));
    assertTrue(output.getOut().contains("\"intentPath\":\"制度\""));
    assertTrue(output.getOut().contains("\"intent\":\"MCP_TOOL\""));
    assertEquals(
        List.of(IntentType.KNOWLEDGE_RETRIEVAL, IntentType.SYSTEM_CHAT, IntentType.MCP_TOOL),
        result.routes().stream().map(IntentRoute::intent).toList());
    assertEquals(List.of(kbId), result.routes().getFirst().knowledgeBaseIds());
    assertEquals(chatNode.id(), result.routes().getFirst().secondCandidateId());
    assertEquals(RoutingReasonCode.AMBIGUOUS, result.routes().getFirst().reasonCode());
    assertEquals(0.45, result.routes().getFirst().secondCandidateScore());
    verify(tools).check("calendar.read", Map.of());
    ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
    verify(chat, times(1)).generate(systemPrompt.capture(), input.capture());
    assertEquals(IntentTreeRoutingPrompts.system(), systemPrompt.getValue());
    assertTrue(systemPrompt.getValue().contains("reasonCode 只允许 MATCHED 或 AMBIGUOUS"));
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
  void loggingFailureDoesNotChangeClassifiedRoute() {
    IntentNode chatNode = node("general-chat", IntentNode.Kind.SYSTEM, List.of());
    active(chatNode);
    when(chat.generate(any(), any())).thenReturn(generation(response(chatNode.id(), 0.95, "{}")));
    RagRunTrace trace = spy(trace());
    doThrow(new IllegalStateException("log failed")).when(trace).runId();

    RoutingPlan result = stage.execute(QueryPlan.fallback("hello"), trace);

    assertEquals(IntentType.SYSTEM_CHAT, result.routes().getFirst().intent());
    assertEquals(
        RagStageStatus.SUCCESS,
        trace.finish(RagRunStatus.COMPLETED, null).stages().getFirst().status());
  }

  @Test
  void fallbackLogContainsRunIdAndReason(CapturedOutput output) {
    when(snapshots.snapshot()).thenReturn(snapshot(List.of()));
    RagRunTrace trace = trace();

    RoutingPlan result = stage.execute(QueryPlan.fallback("secret-question"), trace);

    assertEquals(IntentType.KNOWLEDGE_RETRIEVAL, result.routes().getFirst().intent());
    assertTrue(output.getOut().contains("intent routing fallback runId=" + trace.runId()));
    assertTrue(output.getOut().contains("reason=INTENT_TREE_EMPTY"));
    assertTrue(output.getOut().contains("\"reasonCode\":\"INTENT_TREE_FALLBACK\""));
    assertFalse(output.getOut().contains("secret-question"));
  }

  @Test
  void emptyTreeAndDisabledOrInvalidLeavesSkipClassification() {
    QueryPlan plan = twoQuestions();
    when(snapshots.snapshot()).thenReturn(snapshot(List.of()));
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
    when(snapshots.snapshot()).thenReturn(snapshot(List.of(disabled)));
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
  void lowConfidenceOnlyFallsBackForAffectedSubQuestion() {
    UUID kbId = UUID.randomUUID();
    IntentNode kb = node("制度", IntentNode.Kind.KB, List.of(kbId));
    IntentNode chatNode = node("闲聊", IntentNode.Kind.SYSTEM, List.of());
    when(snapshots.snapshot()).thenReturn(snapshot(List.of(kb, chatNode)));
    QueryPlan plan =
        new QueryPlan("复合问题", List.of(new SubQuestion("Q1", "你好"), new SubQuestion("Q2", "休假制度")));
    String response =
        "{\"routes\":["
            + "{\"subQuestionId\":\"Q1\",\"candidates\":[{\"nodeId\":\""
            + chatNode.id()
            + "\",\"score\":0.95}],\"reasonCode\":\"MATCHED\",\"toolArguments\":{}},"
            + "{\"subQuestionId\":\"Q2\",\"candidates\":[{\"nodeId\":\""
            + kb.id()
            + "\",\"score\":0.4}],\"reasonCode\":\"MATCHED\",\"toolArguments\":{}}]}";
    when(chat.generate(any(), any())).thenReturn(generation(response));
    RagRunTrace trace = trace();

    RoutingPlan result = stage.execute(plan, trace);

    assertEquals(IntentType.SYSTEM_CHAT, result.routes().get(0).intent());
    assertEquals(RoutingReasonCode.GENERAL_CHAT, result.routes().get(0).reasonCode());
    assertEquals(IntentType.KNOWLEDGE_RETRIEVAL, result.routes().get(1).intent());
    assertEquals(RoutingReasonCode.INTENT_TREE_FALLBACK, result.routes().get(1).reasonCode());
    assertNull(result.routes().get(1).knowledgeBaseIds());
    var snapshot = trace.finish(RagRunStatus.COMPLETED, null);
    assertEquals(RagStageStatus.DEGRADED, snapshot.stages().getFirst().status());
    assertEquals("INTENT_TREE_LOW_CONFIDENCE", snapshot.stages().getFirst().reasonCode());
    verify(chat, times(1)).generate(any(), any());
  }

  @Test
  void toolValidationOnlyFallsBackForAffectedSubQuestion() {
    UUID kbId = UUID.randomUUID();
    IntentNode kb = node("制度", IntentNode.Kind.KB, List.of(kbId));
    IntentNode mcp = mcpNode();
    when(snapshots.snapshot()).thenReturn(snapshot(List.of(kb, mcp)));
    when(tools.check("calendar.read", Map.of("date", "invalid")))
        .thenReturn(McpToolRegistry.RoutingCheck.INVALID_ARGUMENTS);
    QueryPlan plan =
        new QueryPlan(
            "复合问题", List.of(new SubQuestion("Q1", "休假制度"), new SubQuestion("Q2", "查询日程")));
    String response =
        "{\"routes\":["
            + "{\"subQuestionId\":\"Q1\",\"candidates\":[{\"nodeId\":\""
            + kb.id()
            + "\",\"score\":0.95}],\"reasonCode\":\"MATCHED\",\"toolArguments\":{}},"
            + "{\"subQuestionId\":\"Q2\",\"candidates\":[{\"nodeId\":\""
            + mcp.id()
            + "\",\"score\":0.92}],\"reasonCode\":\"MATCHED\","
            + "\"toolArguments\":{\"date\":\"invalid\"}}]}";
    when(chat.generate(any(), any())).thenReturn(generation(response));
    RagRunTrace trace = trace();

    RoutingPlan result = stage.execute(plan, trace);

    assertEquals(IntentType.KNOWLEDGE_RETRIEVAL, result.routes().get(0).intent());
    assertEquals(List.of(kbId), result.routes().get(0).knowledgeBaseIds());
    assertEquals(RoutingReasonCode.KNOWLEDGE_SOURCE_REQUIRED, result.routes().get(0).reasonCode());
    assertEquals(IntentType.KNOWLEDGE_RETRIEVAL, result.routes().get(1).intent());
    assertEquals(RoutingReasonCode.INTENT_TREE_FALLBACK, result.routes().get(1).reasonCode());
    assertNull(result.routes().get(1).knowledgeBaseIds());
    assertNull(result.routes().get(1).toolHint());
    var snapshot = trace.finish(RagRunStatus.COMPLETED, null);
    assertEquals(RagStageStatus.DEGRADED, snapshot.stages().getFirst().status());
    assertEquals("INTENT_TREE_INVALID_TOOL_ARGUMENTS", snapshot.stages().getFirst().reasonCode());
    verify(tools).check("calendar.read", Map.of("date", "invalid"));
    verify(chat, times(1)).generate(any(), any());
  }

  @Test
  void invalidCandidateOnlyFallsBackForAffectedSubQuestion() {
    UUID kbId = UUID.randomUUID();
    IntentNode kb = node("制度", IntentNode.Kind.KB, List.of(kbId));
    when(snapshots.snapshot()).thenReturn(snapshot(List.of(kb)));
    QueryPlan plan =
        new QueryPlan(
            "复合问题", List.of(new SubQuestion("Q1", "休假制度"), new SubQuestion("Q2", "未知问题")));
    String response =
        "{\"routes\":["
            + "{\"subQuestionId\":\"Q1\",\"candidates\":[{\"nodeId\":\""
            + kb.id()
            + "\",\"score\":0.95}],\"reasonCode\":\"MATCHED\",\"toolArguments\":{}},"
            + "{\"subQuestionId\":\"Q2\",\"candidates\":[{\"nodeId\":\""
            + UUID.randomUUID()
            + "\",\"score\":0.9}],\"reasonCode\":\"MATCHED\",\"toolArguments\":{}}]}";
    when(chat.generate(any(), any())).thenReturn(generation(response));
    RagRunTrace trace = trace();

    RoutingPlan result = stage.execute(plan, trace);

    assertEquals(List.of(kbId), result.routes().get(0).knowledgeBaseIds());
    assertEquals(RoutingReasonCode.KNOWLEDGE_SOURCE_REQUIRED, result.routes().get(0).reasonCode());
    assertNull(result.routes().get(1).knowledgeBaseIds());
    assertEquals(RoutingReasonCode.INTENT_TREE_FALLBACK, result.routes().get(1).reasonCode());
    var snapshot = trace.finish(RagRunStatus.COMPLETED, null);
    assertEquals(RagStageStatus.DEGRADED, snapshot.stages().getFirst().status());
    assertEquals("INTENT_TREE_INVALID_OUTPUT", snapshot.stages().getFirst().reasonCode());
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
  void unknownModelReasonFallsBack() {
    IntentNode kb = node("制度", IntentNode.Kind.KB, List.of(UUID.randomUUID()));
    active(kb);
    String invalidResponse = response(kb.id(), 0.9, "{}").replace("MATCHED", "UNKNOWN");
    when(chat.generate(any(), any())).thenReturn(generation(invalidResponse));

    assertFallback(QueryPlan.fallback("制度"), "INTENT_TREE_INVALID_OUTPUT");
  }

  @Test
  void rejectedToolArgumentsFallBackWithoutExecutingTool() {
    IntentNode mcp = mcpNode();
    active(mcp);
    when(chat.generate(any(), any()))
        .thenReturn(generation(response(mcp.id(), 0.9, "{\"date\":\"2026-09-16\"}")));
    when(tools.check(any(), any())).thenReturn(McpToolRegistry.RoutingCheck.INVALID_ARGUMENTS);
    assertFallback(QueryPlan.fallback("日程"), "INTENT_TREE_INVALID_TOOL_ARGUMENTS");
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
    when(snapshots.snapshot()).thenReturn(snapshot(List.of(node)));
  }

  private IntentTreeSnapshot snapshot(List<IntentNode> nodes) {
    List<McpToolDefinition> availableTools =
        nodes.stream()
            .filter(node -> node.kind() == IntentNode.Kind.MCP)
            .map(ignored -> calendarTool())
            .toList();
    return IntentTreeSnapshot.from(nodes, availableTools);
  }

  private McpToolDefinition calendarTool() {
    return new McpToolDefinition("calendar.read", "查询日程", true, Map.of("type", "object"), Set.of());
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
