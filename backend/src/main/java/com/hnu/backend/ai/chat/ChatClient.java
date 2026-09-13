package com.hnu.backend.ai.chat;

import com.hnu.backend.ai.config.AiProperties;
import com.hnu.backend.ai.infrastructure.http.ModelHttpClient;
import com.hnu.backend.shared.error.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ChatClient {
  private final ModelHttpClient http;
  private final AiProperties config;

  public ChatClient(ModelHttpClient http, AiProperties config) {
    this.http = http;
    this.config = config;
  }

  public Generation generate(String system, String user) {
    ApiException last = null;
    for (AiProperties.ModelTarget target : config.chatModels()) {
      try {
        var response = http.post(target, payload(target, system, user, false));
        var choice = response.path("choices").path(0);
        var content = choice.path("message").path("content");
        if (!content.isString()
            || content.asString().isBlank()
            || !choice.path("finish_reason").isString()
            || !"stop".equals(choice.path("finish_reason").asString())) {
          throw ApiException.upstream("GENERATION_FAILED", "模型未完整生成有效回答，请重试");
        }
        return new Generation(content.asString(), target.id(), target.provider(), target.model());
      } catch (ApiException e) {
        last = e;
      }
    }
    throw last == null ? ApiException.upstream("MODEL_UNAVAILABLE", "没有可用的对话模型") : last;
  }

  public Generation stream(
      String system, String user, StreamObserver observer, ModelHttpClient.StreamControl control) {
    ApiException last = null;
    boolean anyContent = false;
    for (AiProperties.ModelTarget target : config.chatModels()) {
      observer.started(target, anyContent ? "PROVIDER_FALLBACK" : "PRIMARY");
      StringBuilder content = new StringBuilder();
      String[] finishReason = {null};
      try {
        http.stream(
            target,
            payload(target, system, user, true),
            event -> {
              var choice = event.path("choices").path(0);
              var delta = choice.path("delta").path("content");
              if (delta.isString() && !delta.asString().isEmpty()) {
                content.append(delta.asString());
                observer.delta(delta.asString());
              }
              var finish = choice.path("finish_reason");
              if (finish.isString()) finishReason[0] = finish.asString();
            },
            control);
        if (content.isEmpty() || !"stop".equals(finishReason[0])) {
          throw ApiException.upstream("GENERATION_FAILED", "模型未完整生成有效回答，请重试");
        }
        observer.completed(target, content.toString(), finishReason[0]);
        return new Generation(content.toString(), target.id(), target.provider(), target.model());
      } catch (ApiException e) {
        observer.failed(target, content.toString(), e);
        last = e;
        if (!content.isEmpty() || control.cancelled()) throw e;
      }
      anyContent = anyContent || !content.isEmpty();
    }
    throw last == null ? ApiException.upstream("MODEL_UNAVAILABLE", "没有可用的对话模型") : last;
  }

  private Map<String, Object> payload(
      AiProperties.ModelTarget target, String system, String user, boolean stream) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", target.model());
    payload.put("stream", stream);
    payload.put(
        "messages",
        List.of(
            Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
    return payload;
  }

  public interface StreamObserver {
    void started(AiProperties.ModelTarget target, String reason);

    void delta(String text);

    void completed(AiProperties.ModelTarget target, String content, String finishReason);

    void failed(AiProperties.ModelTarget target, String partialContent, ApiException error);
  }

  public record Generation(String content, String id, String provider, String model) {}
}
