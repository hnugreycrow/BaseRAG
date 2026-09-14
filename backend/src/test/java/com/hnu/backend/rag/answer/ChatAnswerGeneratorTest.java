package com.hnu.backend.rag.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.model.http.ModelHttpClient;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ChatAnswerGeneratorTest {
  private final ChatClient chat = mock(ChatClient.class);
  private final ChatAnswerGenerator generator = new ChatAnswerGenerator(chat);

  @Test
  void mapsPrimaryAndProviderFallbackEventsToNeutralTypes() {
    AnswerGenerator.StreamObserver observer = mock(AnswerGenerator.StreamObserver.class);
    AnswerGenerator.Control control = generator.newControl();
    AiProperties.ModelTarget primary = target("primary");
    AiProperties.ModelTarget fallback = target("fallback");
    when(chat.stream(eq("system"), eq("user"), any(), any(ModelHttpClient.StreamControl.class)))
        .thenAnswer(
            invocation -> {
              ChatClient.StreamObserver delegate = invocation.getArgument(2);
              delegate.started(primary, "PRIMARY");
              delegate.failed(
                  primary,
                  "",
                  com.hnu.backend.shared.error.ApiException.upstream(
                      "MODEL_UNAVAILABLE", "failed"));
              delegate.started(fallback, "PROVIDER_FALLBACK");
              delegate.delta("answer");
              delegate.completed(fallback, "answer", "stop");
              return new ChatClient.Generation(
                  "answer", fallback.id(), fallback.provider(), fallback.model());
            });

    AnswerGenerator.Generation result =
        generator.generate(
            "system", "user", AnswerGenerator.AttemptReason.PRIMARY, observer, control);

    assertEquals("fallback", result.modelId());
    InOrder order = inOrder(observer);
    order
        .verify(observer)
        .started(
            new AnswerGenerator.ModelTarget("primary", "test", "primary-model"),
            AnswerGenerator.AttemptReason.PRIMARY);
    order
        .verify(observer)
        .failed(
            eq(new AnswerGenerator.ModelTarget("primary", "test", "primary-model")), eq(""), any());
    order
        .verify(observer)
        .started(
            new AnswerGenerator.ModelTarget("fallback", "test", "fallback-model"),
            AnswerGenerator.AttemptReason.PROVIDER_FALLBACK);
    order.verify(observer).delta("answer");
    order
        .verify(observer)
        .completed(
            new AnswerGenerator.ModelTarget("fallback", "test", "fallback-model"),
            "answer",
            "stop");
  }

  @Test
  void keepsFallbackInsideCitationRepairAndClosesUnderlyingControl() {
    AnswerGenerator.StreamObserver observer = mock(AnswerGenerator.StreamObserver.class);
    AnswerGenerator.Control control = generator.newControl();
    AiProperties.ModelTarget fallback = target("fallback");
    when(chat.stream(any(), any(), any(), any(ModelHttpClient.StreamControl.class)))
        .thenAnswer(
            invocation -> {
              ChatClient.StreamObserver delegate = invocation.getArgument(2);
              delegate.started(fallback, "PROVIDER_FALLBACK");
              return new ChatClient.Generation(
                  "answer", fallback.id(), fallback.provider(), fallback.model());
            });

    generator.generate(
        "system", "user", AnswerGenerator.AttemptReason.CITATION_REPAIR, observer, control);

    verify(observer)
        .started(
            new AnswerGenerator.ModelTarget("fallback", "test", "fallback-model"),
            AnswerGenerator.AttemptReason.CITATION_REPAIR);
    assertFalse(control.cancelled());
    control.close();
    assertTrue(control.cancelled());
  }

  private AiProperties.ModelTarget target(String id) {
    return new AiProperties.ModelTarget(
        id, "test", id + "-model", "http://localhost", "/chat", "", 1000, 0, false);
  }
}
