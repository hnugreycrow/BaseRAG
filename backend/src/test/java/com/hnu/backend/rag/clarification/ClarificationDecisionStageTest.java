package com.hnu.backend.rag.clarification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.intent.model.IntentNode;
import com.hnu.backend.intent.snapshot.*;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.api.ClarificationContext;
import com.hnu.backend.rag.config.RagProperties;
import com.hnu.backend.rag.memory.RagMemory;
import com.hnu.backend.rag.pipeline.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ClarificationDecisionStageTest {
  private final IntentTreeSnapshotProvider snapshots = mock(IntentTreeSnapshotProvider.class);
  private final ChatClient chat = mock(ChatClient.class);
  private final RagProperties config = new RagProperties();
  private final ClarificationDecisionStage stage =
      new ClarificationDecisionStage(snapshots, chat, config);
  private final IntentNode oa = node("OA > 权限申请", IntentNode.Kind.KB);
  private final IntentNode finance = node("财务 > 权限申请", IntentNode.Kind.KB);
  private final QueryPlan plan = QueryPlan.fallback("怎么申请权限？");

  @BeforeEach
  void snapshot() {
    var snapshot = IntentTreeSnapshot.from(List.of(oa, finance), List.of());
    when(snapshots.snapshot()).thenReturn(snapshot);
    when(snapshots.freshSnapshot()).thenReturn(snapshot);
  }

  @AfterEach
  void close() {
    stage.close();
  }

  @Test
  void inclusiveGapBoundaryPausesOnlyAfterSemanticConfirmation() {
    output("NEEDS_CHOICE", null);
    var result = stage.execute(prepared(.8, .7), null, RagRunTrace.noop());
    assertEquals(1, result.context().pending().size());
    assertEquals(
        List.of(oa.id(), finance.id()),
        result.context().pending().getFirst().options().stream()
            .map(ClarificationContext.Option::nodeId)
            .toList());
    verify(chat, times(1)).generate(anyString(), anyString());
  }

  @Test
  void distantOrLowConfidenceCandidatesNeverAskModel() {
    assertTrue(
        stage.execute(prepared(.81, .7), null, RagRunTrace.noop()).context().pending().isEmpty());
    assertTrue(
        stage.execute(prepared(.72, .69), null, RagRunTrace.noop()).context().pending().isEmpty());
    verifyNoInteractions(chat);
  }

  @Test
  void nonKbSecondCandidateDoesNotClarify() {
    var system = node("闲聊", IntentNode.Kind.SYSTEM);
    when(snapshots.snapshot()).thenReturn(IntentTreeSnapshot.from(List.of(oa, system), List.of()));
    assertTrue(
        stage.execute(prepared(.8, .79), null, RagRunTrace.noop()).context().pending().isEmpty());
    verifyNoInteractions(chat);
  }

  @Test
  void explicitSecondCandidateOverridesPrimaryRoute() {
    output("EXPLICIT", finance.id());
    var result = stage.execute(prepared(.8, .79), null, RagRunTrace.noop());
    assertTrue(result.context().pending().isEmpty());
    assertEquals(finance.id(), result.routing().routes().getFirst().intentNodeId());
    assertEquals(
        finance.knowledgeBaseIds(), result.routing().routes().getFirst().knowledgeBaseIds());
  }

  @Test
  void bothAndUnknownKeepOriginalRouting() {
    for (String kind : List.of("BOTH", "UNKNOWN")) {
      output(kind, null);
      var result = stage.execute(prepared(.8, .79), null, RagRunTrace.noop());
      assertTrue(result.context().pending().isEmpty());
      assertEquals(oa.id(), result.routing().routes().getFirst().intentNodeId());
    }
  }

  @Test
  void malformedAndUnknownSelectionsFallbackWithoutAsking() {
    when(chat.generate(anyString(), anyString()))
        .thenReturn(new ChatClient.Generation("not json", "test", "test", "test"));
    assertNull(stage.execute(prepared(.8, .79), null, RagRunTrace.noop()).context());
    output("EXPLICIT", UUID.randomUUID());
    assertNull(stage.execute(prepared(.8, .79), null, RagRunTrace.noop()).context());
  }

  @Test
  void timeoutFallsBackButCancellationPropagates() {
    config.getPipeline().getRouting().setTimeoutMs(10);
    when(chat.generate(anyString(), anyString()))
        .thenAnswer(
            call -> {
              Thread.sleep(1000);
              return null;
            });
    assertNull(stage.execute(prepared(.8, .79), null, RagRunTrace.noop()).context());
    reset(chat);
    when(chat.generate(anyString(), anyString())).thenThrow(ApiException.cancelled());
    assertThrows(
        ApiException.class, () -> stage.execute(prepared(.8, .79), null, RagRunTrace.noop()));
  }

  @Test
  void savedSelectionCannotBeOverriddenAndInvalidConfigStopsResume() {
    var resume =
        new ClarificationContext(
            UUID.randomUUID(), plan, Map.of("Q1", finance.id()), List.of(), List.of());
    var result = stage.execute(prepared(.99, .7), resume, RagRunTrace.noop());
    assertEquals(finance.id(), result.routing().routes().getFirst().intentNodeId());
    verifyNoInteractions(chat);
    when(snapshots.freshSnapshot()).thenReturn(IntentTreeSnapshot.from(List.of(oa), List.of()));
    assertTrue(stage.execute(prepared(.99, .7), resume, RagRunTrace.noop()).invalidated());
  }

  @Test
  void batchesMultipleQuestionsAndPreservesPlanOrder() {
    var prepared = prepared(.8, .79);
    var second =
        new IntentRoute(
            "Q2",
            IntentType.KNOWLEDGE_RETRIEVAL,
            .8,
            null,
            Map.of(),
            RoutingReasonCode.AMBIGUOUS,
            oa.id(),
            finance.id(),
            .79,
            oa.knowledgeBaseIds());
    var multi =
        new RagContextPreparation.PreparedContext(
            prepared.memory(),
            new QueryPlan(
                "两个问题", List.of(new SubQuestion("Q1", "权限"), new SubQuestion("Q2", "流程"))),
            new RoutingPlan(List.of(prepared.routingPlan().routes().getFirst(), second)));
    when(chat.generate(anyString(), anyString()))
        .thenReturn(
            new ChatClient.Generation(
                "{\"decisions\":[{\"subQuestionId\":\"Q2\",\"kind\":\"NEEDS_CHOICE\"},{\"subQuestionId\":\"Q1\",\"kind\":\"NEEDS_CHOICE\"}]}",
                "test",
                "test",
                "test"));
    var result = stage.execute(multi, null, RagRunTrace.noop());
    assertEquals(
        List.of("Q1", "Q2"),
        result.context().pending().stream()
            .map(ClarificationContext.Ambiguity::subQuestionId)
            .toList());
    assertEquals(2, result.context().totalSteps());
    assertEquals("申请权限", result.context().pending().getFirst().options().getFirst().description());
    var resume =
        new ClarificationContext(
            UUID.randomUUID(),
            multi.queryPlan(),
            Map.of("Q1", oa.id()),
            result.context().pending(),
            List.of(),
            result.context().totalSteps());
    var next = stage.execute(multi, resume, RagRunTrace.noop());
    assertEquals(2, next.context().totalSteps());
    assertEquals(1, next.context().pending().size());
    assertEquals("Q2", next.context().pending().getFirst().subQuestionId());
    verify(chat, times(1)).generate(anyString(), anyString());
  }

  @Test
  void legacyContextWithoutProgressOrDescriptionsStillLoads() {
    var json = com.hnu.backend.common.json.JsonCodecs.snapshots();
    String encoded =
        json.writeValueAsString(
            Map.of(
                "originalMessageId",
                UUID.randomUUID(),
                "plan",
                plan,
                "selections",
                Map.of(),
                "pending",
                List.of(
                    Map.of(
                        "subQuestionId",
                        "Q1",
                        "options",
                        List.of(Map.of("nodeId", oa.id(), "label", oa.name())))),
                "supplementIds",
                List.of()));
    var restored = json.readValue(encoded, ClarificationContext.class);
    assertEquals(1, restored.totalSteps());
    assertEquals("", restored.pending().getFirst().options().getFirst().description());
  }

  @Test
  void freeTextOnlyAcceptsAllowedNode() {
    var ambiguity =
        new ClarificationContext.Ambiguity(
            "Q1", List.of(new ClarificationContext.Option(oa.id(), oa.name())));
    assertEquals(oa.id(), stage.resolveText(oa.name(), ambiguity));
    when(chat.generate(anyString(), anyString()))
        .thenReturn(
            new ChatClient.Generation("{\"nodeId\":\"" + finance.id() + "\"}", "t", "t", "t"));
    assertNull(stage.resolveText("财务", ambiguity));
  }

  private void output(String kind, UUID id) {
    when(chat.generate(anyString(), anyString()))
        .thenReturn(
            new ChatClient.Generation(
                "{\"decisions\":[{\"subQuestionId\":\"Q1\",\"kind\":\""
                    + kind
                    + "\",\"nodeId\":"
                    + (id == null ? "null" : "\"" + id + "\"")
                    + "}]}",
                "test",
                "test",
                "test"));
  }

  private RagContextPreparation.PreparedContext prepared(double first, double second) {
    return new RagContextPreparation.PreparedContext(
        new RagMemory("", 0, List.of(), List.of(), 0),
        plan,
        new RoutingPlan(
            List.of(
                new IntentRoute(
                    "Q1",
                    IntentType.KNOWLEDGE_RETRIEVAL,
                    first,
                    null,
                    Map.of(),
                    RoutingReasonCode.AMBIGUOUS,
                    oa.id(),
                    finance.id(),
                    second,
                    oa.knowledgeBaseIds()))));
  }

  private static IntentNode node(String name, IntentNode.Kind kind) {
    return new IntentNode(
        UUID.randomUUID(),
        null,
        name,
        "申请权限",
        List.of(),
        kind,
        null,
        kind == IntentNode.Kind.KB ? List.of(UUID.randomUUID()) : List.of(),
        true,
        0);
  }
}
