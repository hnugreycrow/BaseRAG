package com.hnu.backend.model.client;

import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.model.http.ModelHttpClient;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 对话模型客户端，支持模型故障转移和流式生成回调。 */
@Component
public class ChatClient {
  private static final Logger log = LoggerFactory.getLogger(ChatClient.class);
  private final ModelHttpClient http;
  private final AiProperties config;
  private final Map<String, ThinkingParameterAdapter> thinkingAdapters;

  /**
   * 创建对话模型客户端。
   *
   * @param http 模型 HTTP 客户端
   * @param config AI 模型配置
   * @param adapters 按供应商匹配的思考参数适配器
   */
  @Autowired
  public ChatClient(
      ModelHttpClient http, AiProperties config, List<ThinkingParameterAdapter> adapters) {
    this.http = http;
    this.config = config;
    this.thinkingAdapters = new LinkedHashMap<>();
    for (ThinkingParameterAdapter adapter : adapters) {
      if (thinkingAdapters.putIfAbsent(adapter.provider(), adapter) != null) {
        throw new IllegalArgumentException("Duplicate thinking adapter: " + adapter.provider());
      }
    }
    for (AiProperties.ModelTarget target : config.chatModels()) {
      if (target.supportsThinking() && !thinkingAdapters.containsKey(target.provider())) {
        throw new IllegalArgumentException("Missing thinking adapter: " + target.provider());
      }
    }
  }

  /** 供不启动 Spring 容器的模型 HTTP 测试使用。 */
  public ChatClient(ModelHttpClient http, AiProperties config) {
    this(http, config, List.of(new DeepSeekThinkingAdapter(), new BailianThinkingAdapter()));
  }

  /**
   * 非流式生成回答，并在模型调用失败时依次尝试备用模型。
   *
   * @param system 系统提示词
   * @param user 用户提示词
   * @return 生成文本及实际使用的模型信息
   */
  public Generation generate(String system, String user) {
    return generate(new ChatGenerationRequest(system, user, false));
  }

  /** 按候选顺序生成非流式回答；超时由下一候选接管，中断直接传播。 */
  public Generation generate(ChatGenerationRequest request) {
    ApiException last = null;
    // 仅当当前目标没有产出可用结果时，才按配置顺序切换到下一个模型。
    for (AiProperties.ModelTarget target : config.chatModels()) {
      try {
        return http.post(
            target,
            payload(target, request, false),
            response -> {
              var choice = response.path("choices").path(0);
              var content = choice.path("message").path("content");
              var finish = choice.path("finish_reason");
              String text = content.isString() ? content.asString() : "";
              validateCompletion(text, finish.isString() ? finish.asString() : null);
              return new Generation(text, target.id(), target.provider(), target.model());
            });
      } catch (ApiException e) {
        if (interrupted(e)) {
          throw e;
        }
        last = e;
      }
    }
    throw last == null ? ApiException.upstream(ErrorCode.MODEL_UNAVAILABLE, "没有可用的对话模型") : last;
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
    return stream(new ChatGenerationRequest(system, user, false), observer, control);
  }

  /**
   * 在共享总预算内流式生成；转发首段正文或思考内容后不再切换候选。
   *
   * @param request 提示词与思考开关
   * @param observer 同步接收模型尝试及内容事件的观察者
   * @param control 跨候选共享的用户取消控制器
   * @return 完整回答和实际模型身份；部分结果只经观察者转发
   * @throws ApiException 全部候选失败、超时、取消或生成结果不完整
   */
  public Generation stream(
      ChatGenerationRequest request,
      StreamObserver observer,
      ModelHttpClient.StreamControl control) {
    ApiException last = null;
    int attemptIndex = 0;
    long deadline =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getStream().getTotalTimeoutMs());
    for (AiProperties.ModelTarget target : config.chatModels()) {
      if (control.cancelled()) {
        throw ApiException.cancelled();
      }
      if (Thread.currentThread().isInterrupted()) {
        throw ApiException.upstream(ErrorCode.REQUEST_INTERRUPTED, "请求已中断");
      }
      if (deadline - System.nanoTime() <= 0) {
        throw ApiException.upstream(ErrorCode.MODEL_TIMEOUT, "模型生成总时限已到，请稍后重试");
      }
      if (last != null) {
        log.info("model fallback nextModelId={} previousCode={}", target.id(), last.code());
      }
      observer.started(target, attemptIndex++ == 0 ? "PRIMARY" : "PROVIDER_FALLBACK");
      StreamObserver bound = observer.bindAttempt();
      StreamObserver attemptObserver = bound == null ? observer : bound;
      StringBuilder content = new StringBuilder();
      String[] finishReason = {null};
      boolean[] outputCommitted = {false};
      try {
        attemptObserver.requesting(target);
        http.stream(
            target,
            payload(target, request, true),
            event -> {
              var choice = event.path("choices").path(0);
              var reasoning = choice.path("delta").path("reasoning_content");
              if (request.thinkingEnabled()
                  && target.supportsThinking()
                  && reasoning.isString()
                  && !reasoning.asString().isEmpty()) {
                outputCommitted[0] = true;
                attemptObserver.reasoningDelta(reasoning.asString());
              }
              var delta = choice.path("delta").path("content");
              if (delta.isString() && !delta.asString().isEmpty()) {
                outputCommitted[0] = true;
                content.append(delta.asString());
                attemptObserver.delta(delta.asString());
              }
              var finish = choice.path("finish_reason");
              if (finish.isString()) {
                finishReason[0] = finish.asString();
              }
            },
            control,
            deadline,
            event -> {
              var delta = event.path("choices").path(0).path("delta");
              var contentNode = delta.path("content");
              var reasoningNode = delta.path("reasoning_content");
              return (contentNode.isString() && !contentNode.asString().isEmpty())
                  || (request.thinkingEnabled()
                      && target.supportsThinking()
                      && reasoningNode.isString()
                      && !reasoningNode.asString().isEmpty());
            },
            () -> validateCompletion(content.toString(), finishReason[0]));
        if (control.cancelled()) {
          throw ApiException.cancelled();
        }
        attemptObserver.completed(target, content.toString(), finishReason[0]);
        return new Generation(content.toString(), target.id(), target.provider(), target.model());
      } catch (ApiException e) {
        attemptObserver.failed(target, content.toString(), e);
        last = e;
        // 正文和可见思考内容都属于已提交输出，不能清空后透明切换供应商。
        if (outputCommitted[0] || control.cancelled() || interrupted(e)) {
          throw e;
        }
      }
    }
    throw last == null ? ApiException.upstream(ErrorCode.MODEL_UNAVAILABLE, "没有可用的对话模型") : last;
  }

  /** 缺失内容或终止标记属于协议错误；长度限制与内容过滤不表示服务不可用。 */
  private void validateCompletion(String content, String finishReason) {
    if (finishReason == null || finishReason.isBlank()) {
      throw ApiException.upstream(ErrorCode.MODEL_INVALID_RESPONSE, "模型响应缺少结束原因");
    }
    if (!"stop".equals(finishReason)) {
      throw ApiException.upstream(ErrorCode.GENERATION_FAILED, "模型未完整生成有效回答，请重试");
    }
    if (content.isBlank()) {
      throw ApiException.upstream(ErrorCode.MODEL_INVALID_RESPONSE, "模型响应缺少有效回答");
    }
  }

  /** 中断和用户取消必须终止候选遍历，不能作为供应商故障继续调用。 */
  private boolean interrupted(ApiException error) {
    return Thread.currentThread().isInterrupted()
        || ErrorCode.REQUEST_INTERRUPTED.code().equals(error.code())
        || ErrorCode.GENERATION_CANCELLED.code().equals(error.code());
  }

  /**
   * 构造兼容 OpenAI Chat Completions 协议的请求体。
   *
   * @param target 模型目标
   * @param request 提示词与思考开关
   * @param stream 是否启用流式响应
   * @return 模型请求体
   */
  private Map<String, Object> payload(
      AiProperties.ModelTarget target, ChatGenerationRequest request, boolean stream) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", target.model());
    payload.put("stream", stream);
    payload.put(
        "messages",
        List.of(
            Map.of("role", "system", "content", request.system()),
            Map.of("role", "user", "content", request.user())));
    ThinkingParameterAdapter adapter = thinkingAdapters.get(target.provider());
    if (adapter != null && target.supportsThinking()) {
      adapter.apply(payload, request.thinkingEnabled());
    }
    return payload;
  }

  /** 接收流式模型调用生命周期事件。 */
  public interface StreamObserver {
    /** 返回当前尝试独占的回调接收器，避免后续尝试覆盖其状态。 */
    default StreamObserver bindAttempt() {
      return this;
    }

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

    /** 收到一段思考内容；与最终回答正文分开传递。 */
    default void reasoningDelta(String text) {}

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
