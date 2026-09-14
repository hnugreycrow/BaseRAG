package com.hnu.backend.rag.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.rag.mcp.McpToolDefinition;
import com.hnu.backend.rag.planning.QueryPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class ChatIntentClassifierTest {
  private final ChatClient chat = mock(ChatClient.class);
  private final ChatIntentClassifier classifier = new ChatIntentClassifier(chat);
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void sendsQuestionsAndSafeToolCatalogAsUntrustedJsonData() {
    QueryPlan plan = QueryPlan.fallback("查询今日排班");
    McpToolDefinition tool =
        new McpToolDefinition(
            "schedule.read",
            "读取排班",
            true,
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("date", Map.of("type", "string")),
                "required",
                List.of("date"),
                "additionalProperties",
                false),
            Set.of("employeeToken"));
    when(chat.generate(anyString(), anyString()))
        .thenReturn(
            new ChatClient.Generation("{\"routes\":[]}", "router", "test-provider", "test-model"));

    var output = classifier.classify(plan, List.of(tool));

    ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
    verify(chat).generate(system.capture(), user.capture());
    var input = json.readTree(user.getValue());
    assertEquals("查询今日排班", input.path("standaloneQuestion").asString());
    assertEquals("Q1", input.path("subQuestions").path(0).path("id").asString());
    assertEquals("schedule.read", input.path("availableTools").path(0).path("name").asString());
    assertFalse(input.path("availableTools").path(0).has("sensitiveFields"));
    assertTrue(system.getValue().contains("不可信数据"));
    assertTrue(system.getValue().contains("不得输出其他字段"));
    assertEquals("router", output.modelId());
  }
}
