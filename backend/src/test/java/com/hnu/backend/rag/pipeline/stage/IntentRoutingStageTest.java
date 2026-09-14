package com.hnu.backend.rag.pipeline.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.rag.model.IntentType;
import com.hnu.backend.rag.model.McpToolDefinition;
import com.hnu.backend.rag.model.QueryPlan;
import com.hnu.backend.rag.model.RoutingReasonCode;
import com.hnu.backend.rag.model.SubQuestion;
import com.hnu.backend.rag.port.IntentClassifier;
import com.hnu.backend.rag.port.McpToolGateway;
import com.hnu.backend.rag.service.McpToolRegistry;
import com.hnu.backend.rag.support.McpInputSchemaValidator;
import com.hnu.backend.shared.error.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class IntentRoutingStageTest {
  private final IntentClassifier classifier = mock(IntentClassifier.class);
  private final RagProperties config = new RagProperties();
  private final JsonMapper json = JsonMapper.builder().build();
  private IntentRoutingStage stage;

  @AfterEach
  void tearDown() {
    if (stage != null) stage.close();
  }

  @Test
  void acceptsThreeIntentsAndPreservesSubQuestionOrder() {
    config.getPipeline().getMcp().setEnabled(true);
    config.getPipeline().getMcp().setAllowList(List.of("calendar.read"));
    stage = stageWith(tool("calendar.read", true));
    QueryPlan plan =
        new QueryPlan(
            "组合问题",
            List.of(
                new SubQuestion("Q1", "年假制度是什么？"),
                new SubQuestion("Q2", "今天有哪些日程？"),
                new SubQuestion("Q3", "你好")));
    stub(
        routes(
            route("Q1", "KNOWLEDGE_RETRIEVAL", 0.95, null, Map.of(), "KNOWLEDGE_SOURCE_REQUIRED"),
            route(
                "Q2",
                "MCP_TOOL",
                0.91,
                "calendar.read",
                Map.of("date", "2026-09-14"),
                "EXTERNAL_SOURCE_REQUIRED"),
            route("Q3", "SYSTEM_CHAT", 0.99, null, Map.of(), "GENERAL_CHAT")));

    var result = stage.execute(plan);

    assertEquals(
        List.of(IntentType.KNOWLEDGE_RETRIEVAL, IntentType.MCP_TOOL, IntentType.SYSTEM_CHAT),
        result.routes().stream().map(route -> route.intent()).toList());
    assertEquals("calendar.read", result.routes().get(1).toolHint());
  }

  @Test
  void appliesLowConfidenceAndMcpSafetyFallbacks() {
    stage = stageWith(tool("calendar.read", true));
    QueryPlan plan =
        new QueryPlan("两个问题", List.of(new SubQuestion("Q1", "你好"), new SubQuestion("Q2", "今天日程")));
    stub(
        routes(
            route("Q1", "SYSTEM_CHAT", 0.69, null, Map.of(), "GENERAL_CHAT"),
            route(
                "Q2",
                "MCP_TOOL",
                0.95,
                "calendar.read",
                Map.of("date", "2026-09-14"),
                "EXTERNAL_SOURCE_REQUIRED")));

    var result = stage.execute(plan);

    assertEquals(IntentType.KNOWLEDGE_RETRIEVAL, result.routes().get(0).intent());
    assertEquals(RoutingReasonCode.LOW_CONFIDENCE_FALLBACK, result.routes().get(0).reasonCode());
    assertEquals(IntentType.KNOWLEDGE_RETRIEVAL, result.routes().get(1).intent());
    assertEquals(RoutingReasonCode.MCP_DISABLED_FALLBACK, result.routes().get(1).reasonCode());
  }

  @Test
  void rejectsUnknownWriteAndInvalidArgumentTools() {
    config.getPipeline().getMcp().setEnabled(true);
    config.getPipeline().getMcp().setAllowList(List.of("calendar.write", "calendar.read"));
    QueryPlan plan = QueryPlan.fallback("外部操作");

    stage = stageWith(tool("calendar.write", false));
    stub(
        routes(
            route(
                "Q1",
                "MCP_TOOL",
                0.9,
                "calendar.write",
                Map.of("date", "2026-09-14"),
                "EXTERNAL_SOURCE_REQUIRED")));
    assertEquals(
        RoutingReasonCode.TOOL_NOT_READ_ONLY_FALLBACK,
        stage.execute(plan).routes().getFirst().reasonCode());
    stage.close();

    stage = stageWith(tool("calendar.read", true));
    stub(
        routes(
            route(
                "Q1",
                "MCP_TOOL",
                0.9,
                "missing.read",
                Map.of("date", "2026-09-14"),
                "EXTERNAL_SOURCE_REQUIRED")));
    assertEquals(
        RoutingReasonCode.TOOL_NOT_ALLOWED_FALLBACK,
        stage.execute(plan).routes().getFirst().reasonCode());

    stub(
        routes(
            route(
                "Q1",
                "MCP_TOOL",
                0.9,
                "calendar.read",
                Map.of("unexpected", true),
                "EXTERNAL_SOURCE_REQUIRED")));
    assertEquals(
        RoutingReasonCode.INVALID_TOOL_ARGUMENTS_FALLBACK,
        stage.execute(plan).routes().getFirst().reasonCode());
  }

  @Test
  void degradesMalformedSchemasEnumsIdsAndModelFailures() {
    stage = stageWith();
    QueryPlan plan = QueryPlan.fallback("问题");
    List<String> invalid =
        List.of(
            "not-json",
            "{}",
            "{\"routes\":[]}",
            routes(route("Q2", "SYSTEM_CHAT", 0.9, null, Map.of(), "GENERAL_CHAT")),
            routes(route("Q1", "UNKNOWN", 0.9, null, Map.of(), "GENERAL_CHAT")),
            routes(route("Q1", "SYSTEM_CHAT", 1.1, null, Map.of(), "GENERAL_CHAT")),
            routes(route("Q1", "SYSTEM_CHAT", 0.9, "tool", Map.of(), "GENERAL_CHAT")),
            routes(route("Q1", "SYSTEM_CHAT", 0.9, null, Map.of(), "EXTERNAL_SOURCE_REQUIRED")));
    for (String candidate : invalid) {
      stub(candidate);
      assertEquals(
          RoutingReasonCode.CLASSIFIER_DEGRADED,
          stage.execute(plan).routes().getFirst().reasonCode());
    }

    when(classifier.classify(eq(plan), any())).thenThrow(new RuntimeException("unavailable"));
    assertEquals(
        RoutingReasonCode.CLASSIFIER_DEGRADED,
        stage.execute(plan).routes().getFirst().reasonCode());
    when(classifier.classify(eq(plan), any()))
        .thenThrow(ApiException.upstream("MODEL_TIMEOUT", "timeout"));
    assertEquals(
        RoutingReasonCode.CLASSIFIER_DEGRADED,
        stage.execute(plan).routes().getFirst().reasonCode());
  }

  @Test
  void enforcesRoutingTimeout() {
    config.getPipeline().getRouting().setTimeoutMs(10);
    stage = stageWith();
    QueryPlan plan = QueryPlan.fallback("问题");
    when(classifier.classify(eq(plan), any()))
        .thenAnswer(
            ignored -> {
              Thread.sleep(1000);
              return output(routes(route("Q1", "SYSTEM_CHAT", 1, null, Map.of(), "GENERAL_CHAT")));
            });

    var result = stage.execute(plan);

    assertEquals(RoutingReasonCode.CLASSIFIER_DEGRADED, result.routes().getFirst().reasonCode());
  }

  private IntentRoutingStage stageWith(McpToolDefinition... definitions) {
    McpToolGateway gateway = mock(McpToolGateway.class);
    when(gateway.tools()).thenReturn(List.of(definitions));
    McpToolRegistry registry =
        new McpToolRegistry(List.of(gateway), config, new McpInputSchemaValidator());
    return new IntentRoutingStage(classifier, registry, config);
  }

  private McpToolDefinition tool(String name, boolean readOnly) {
    return new McpToolDefinition(
        name,
        "读取日历",
        readOnly,
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("date", Map.of("type", "string", "minLength", 1)),
            "required",
            List.of("date"),
            "additionalProperties",
            false),
        Set.of());
  }

  private void stub(String content) {
    when(classifier.classify(any(QueryPlan.class), any())).thenReturn(output(content));
  }

  private IntentClassifier.ClassificationOutput output(String content) {
    return new IntentClassifier.ClassificationOutput(content, "router", "test", "test-model");
  }

  @SafeVarargs
  private final String routes(Map<String, Object>... routes) {
    return json.writeValueAsString(Map.of("routes", List.of(routes)));
  }

  private Map<String, Object> route(
      String id,
      String intent,
      double confidence,
      String toolHint,
      Map<String, Object> arguments,
      String reasonCode) {
    Map<String, Object> route = new LinkedHashMap<>();
    route.put("subQuestionId", id);
    route.put("intent", intent);
    route.put("confidence", confidence);
    route.put("toolHint", toolHint);
    route.put("toolArguments", arguments);
    route.put("reasonCode", reasonCode);
    return route;
  }
}
