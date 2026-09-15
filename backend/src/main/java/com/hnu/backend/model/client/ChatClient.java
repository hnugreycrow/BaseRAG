package com.hnu.backend.model.client;

import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.model.http.ModelHttpClient;
import com.hnu.backend.shared.error.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 对话模型客户端，支持模型故障转移和流式生成回调。 */
@Component
public class ChatClient {
  private final ModelHttpClient http;
  private final AiProperties config;

  /**
   * 创建对话模型客户端。
   *
   * @param http 模型 HTTP 客户端
   * @param config AI 模型配置
   */
  public ChatClient(ModelHttpClient http, AiProperties config) {
    this.http = http;
    this.config = config;
  }

  /**
   * 非流式生成回答，并在模型调用失败时依次尝试备用模型。
   *
   * @param system 系统提示词
   * @param user 用户提示词
   * @return 生成文本及实际使用的模型信息
   */
  public Generation generate(String system, String user) {
    ApiException last = null;
    // 仅当当前目标没有产出可用结果时，才按配置顺序切换到下一个模型。
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

  /**
   * 流式生成回答，并通过观察者报告生命周期事件。
   *
   * @param system 系统提示词
   * @param user 用户提示词
   * @param observer 流式事件观察者
   * @param control 取消控制器
   * @return 完整生成文本及实际使用的模型信息
   */
  public Generation stream(
      String system, String user, StreamObserver observer, ModelHttpClient.StreamControl control) {
    ApiException last = null;
    int attemptIndex = 0;
    for (AiProperties.ModelTarget target : config.chatModels()) {
      if (control.cancelled()) throw ApiException.cancelled();
      observer.started(target, attemptIndex++ == 0 ? "PRIMARY" : "PROVIDER_FALLBACK");
      StringBuilder content = new StringBuilder();
      String[] finishReason = {null};
      try {
        observer.requesting(target);
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
        if (control.cancelled()) throw ApiException.cancelled();
        if (content.isEmpty() || !"stop".equals(finishReason[0])) {
          throw ApiException.upstream("GENERATION_FAILED", "模型未完整生成有效回答，请重试");
        }
        observer.completed(target, content.toString(), finishReason[0]);
        return new Generation(content.toString(), target.id(), target.provider(), target.model());
      } catch (ApiException e) {
        observer.failed(target, content.toString(), e);
        last = e;
        // 已向客户端发送过内容后切换模型会拼接两份回答，因此只能直接失败。
        if (!content.isEmpty() || control.cancelled()) throw e;
      }
    }
    throw last == null ? ApiException.upstream("MODEL_UNAVAILABLE", "没有可用的对话模型") : last;
  }

  /**
   * 构造兼容 OpenAI Chat Completions 协议的请求体。
   *
   * @param target 模型目标
   * @param system 系统提示词
   * @param user 用户提示词
   * @param stream 是否启用流式响应
   * @return 模型请求体
   */
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

  /** 接收流式模型调用生命周期事件。 */
  public interface StreamObserver {
    /**
     * 模型尝试开始。
     *
     * @param target 当前模型目标
     * @param reason 选择该目标的原因
     */
    void started(AiProperties.ModelTarget target, String reason);

    /**
     * 即将向当前模型发送 HTTP 请求。
     *
     * @param target 当前模型目标
     */
    default void requesting(AiProperties.ModelTarget target) {}

    /**
     * 收到一段增量文本。
     *
     * @param text 增量文本
     */
    void delta(String text);

    /**
     * 当前模型完整生成成功。
     *
     * @param target 当前模型目标
     * @param content 完整内容
     * @param finishReason 模型结束原因
     */
    void completed(AiProperties.ModelTarget target, String content, String finishReason);

    /**
     * 当前模型尝试失败。
     *
     * @param target 当前模型目标
     * @param partialContent 失败前已生成的内容
     * @param error 失败原因
     */
    void failed(AiProperties.ModelTarget target, String partialContent, ApiException error);
  }

  /**
   * 一次对话生成结果。
   *
   * @param content 完整回答内容
   * @param id 模型配置标识
   * @param provider 模型供应商
   * @param model 模型名称
   */
  public record Generation(String content, String id, String provider, String model) {}
}
