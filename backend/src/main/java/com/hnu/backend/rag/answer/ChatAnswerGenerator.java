package com.hnu.backend.rag.answer;

import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.model.client.ChatGenerationRequest;
import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.model.http.ModelHttpClient;
import com.hnu.backend.shared.error.ApiException;
import org.springframework.stereotype.Component;

/** 将现有支持候选回退的 {@link ChatClient} 适配为 RAG 回答模型端口。 */
@Component
public class ChatAnswerGenerator implements AnswerGenerator {
  private final ChatClient chat;

  /**
   * 创建聊天模型回答适配器。
   *
   * @param chat 现有聊天模型客户端
   */
  public ChatAnswerGenerator(ChatClient chat) {
    this.chat = chat;
  }

  /** {@inheritDoc} */
  @Override
  public Control newControl() {
    return new ChatControl(new ModelHttpClient.StreamControl());
  }

  /** {@inheritDoc} */
  @Override
  public Generation generate(
      String systemPrompt,
      String userPrompt,
      AttemptReason reason,
      StreamObserver observer,
      Control control) {
    return generate(new Request(systemPrompt, userPrompt, false), reason, observer, control);
  }

  @Override
  public Generation generate(
      Request request, AttemptReason reason, StreamObserver observer, Control control) {
    if (!(control instanceof ChatControl chatControl)) {
      throw new IllegalArgumentException("Unsupported answer stream control");
    }
    control.throwIfCancelled();
    ChatClient.StreamObserver bridge =
        new ChatClient.StreamObserver() {
          /** {@inheritDoc} */
          @Override
          public void started(AiProperties.ModelTarget target, String providerReason) {
            observer.started(modelTarget(target), attemptReason(reason, providerReason));
          }

          /** {@inheritDoc} */
          @Override
          public void requesting(AiProperties.ModelTarget target) {
            observer.requesting(modelTarget(target));
          }

          /** {@inheritDoc} */
          @Override
          public void delta(String text) {
            observer.delta(text);
          }

          @Override
          public void reasoningDelta(String text) {
            observer.reasoningDelta(text);
          }

          /** {@inheritDoc} */
          @Override
          public void completed(
              AiProperties.ModelTarget target, String content, String finishReason) {
            observer.completed(modelTarget(target), content, finishReason);
          }

          /** {@inheritDoc} */
          @Override
          public void failed(
              AiProperties.ModelTarget target, String partialContent, ApiException error) {
            observer.failed(modelTarget(target), partialContent, error);
          }
        };
    ChatClient.Generation generation =
        request.thinkingEnabled()
            ? chat.stream(
                new ChatGenerationRequest(
                    request.systemPrompt(), request.userPrompt(), request.thinkingEnabled()),
                bridge,
                chatControl.delegate)
            : chat.stream(
                request.systemPrompt(), request.userPrompt(), bridge, chatControl.delegate);
    control.throwIfCancelled();
    return new Generation(
        generation.content(), generation.id(), generation.provider(), generation.model());
  }

  /**
   * 将模型模块配置转换为不暴露供应商配置细节的中立描述。
   *
   * @param target 模型模块的完整候选配置
   * @return RAG 回答端口使用的模型描述
   */
  private ModelTarget modelTarget(AiProperties.ModelTarget target) {
    return new ModelTarget(target.id(), target.provider(), target.model());
  }

  /**
   * 合并回答阶段原因与模型客户端报告的候选切换原因。
   *
   * <p>引用修复中的后备供应商仍属于同一次修复，因此统一记录为 {@code CITATION_REPAIR}。
   *
   * @param baseReason 回答阶段发起本次调用的原因
   * @param providerReason 模型客户端报告的候选原因
   * @return 写入生成尝试记录的稳定原因
   */
  private AttemptReason attemptReason(AttemptReason baseReason, String providerReason) {
    if (baseReason == AttemptReason.CITATION_REPAIR) return baseReason;
    return "PROVIDER_FALLBACK".equals(providerReason)
        ? AttemptReason.PROVIDER_FALLBACK
        : AttemptReason.PRIMARY;
  }

  /** 把 RAG 中立控制接口绑定到模型 HTTP 客户端的可关闭响应流。 */
  private static final class ChatControl implements Control {
    private final ModelHttpClient.StreamControl delegate;

    /**
     * 创建底层模型流控制包装器。
     *
     * @param delegate 模型 HTTP 流控制器
     */
    private ChatControl(ModelHttpClient.StreamControl delegate) {
      this.delegate = delegate;
    }

    /** {@inheritDoc} */
    @Override
    public boolean cancelled() {
      return delegate.cancelled();
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
      delegate.close();
    }
  }
}
