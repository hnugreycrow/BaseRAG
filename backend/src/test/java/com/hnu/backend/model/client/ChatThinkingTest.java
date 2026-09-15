package com.hnu.backend.model.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.model.http.ModelHttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ChatThinkingTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void deepSeekUsesItsThinkingObjectAndSeparatesReasoningFromAnswer() {
    verifyProvider("deepseek", "thinking", Map.of("type", "enabled"));
  }

  @Test
  void bailianUsesEnableThinkingBooleanAndSeparatesReasoningFromAnswer() {
    verifyProvider("bailian", "enable_thinking", true);
  }

  @SuppressWarnings("unchecked")
  private void verifyProvider(String providerId, String parameter, Object enabledValue) {
    AiProperties config = config(providerId);
    ModelHttpClient http = mock(ModelHttpClient.class);
    when(http.post(any(), any()))
        .thenReturn(
            json.readTree(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"答\"}}]}"));
    ChatClient client = new ChatClient(http, config);
    assertEquals("答", client.generate("系统", "问题").content());
    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(http).post(any(), payload.capture());
    assertEquals(
        "deepseek".equals(providerId) ? Map.of("type", "disabled") : false,
        payload.getValue().get(parameter));

    doAnswer(
        invocation -> {
          Consumer<JsonNode> events = invocation.getArgument(2);
          events.accept(
              json.readTree(
                  "{\"choices\":[{\"delta\":{\"reasoning_content\":\"思\",\"content\":\"\"}}]}"));
          events.accept(json.readTree("{\"choices\":[{\"delta\":{\"reasoning_content\":\"考\"}}]}"));
          events.accept(
              json.readTree(
                  "{\"choices\":[{\"delta\":{\"content\":\"答案\"},\"finish_reason\":\"stop\"}]}"));
          return null;
        })
        .when(http)
        .stream(any(), any(), any(), any());
    List<String> reasoning = new ArrayList<>();
    List<String> content = new ArrayList<>();
    ChatClient.Generation result =
        client.stream(
            new ChatGenerationRequest("系统", "问题", true),
            new ChatClient.StreamObserver() {
              @Override
              public void started(AiProperties.ModelTarget target, String reason) {}

              @Override
              public void delta(String text) {
                content.add(text);
              }

              @Override
              public void reasoningDelta(String text) {
                reasoning.add(text);
              }

              @Override
              public void completed(
                  AiProperties.ModelTarget target, String text, String finishReason) {}

              @Override
              public void failed(
                  AiProperties.ModelTarget target,
                  String partialContent,
                  com.hnu.backend.shared.error.ApiException error) {}
            },
            new ModelHttpClient.StreamControl());
    assertEquals("答案", result.content());
    assertEquals(List.of("思", "考"), reasoning);
    assertEquals(List.of("答案"), content);
    verify(http).stream(any(), payload.capture(), any(), any());
    assertEquals(enabledValue, payload.getValue().get(parameter));
  }

  @Test
  void refusesAThinkingCapableCandidateWithoutAnAdapter() {
    AiProperties config = config("other");
    assertThrows(
        IllegalArgumentException.class, () -> new ChatClient(mock(ModelHttpClient.class), config));
  }

  private AiProperties config(String providerId) {
    AiProperties config = new AiProperties();
    AiProperties.Provider provider = new AiProperties.Provider();
    provider.setUrl("http://localhost");
    provider.setApiKey("test");
    provider.getEndpoints().setChat("/chat/completions");
    config.getProviders().put(providerId, provider);
    AiProperties.Candidate candidate = new AiProperties.Candidate();
    candidate.setId("chat");
    candidate.setProvider(providerId);
    candidate.setModel("test-model");
    candidate.setSupportsThinking(true);
    config.getChat().getCandidates().add(candidate);
    AiProperties.Tier tier = new AiProperties.Tier();
    tier.setCandidates(List.of("chat"));
    config.getChat().getTiers().put("standard", tier);
    return config;
  }
}
