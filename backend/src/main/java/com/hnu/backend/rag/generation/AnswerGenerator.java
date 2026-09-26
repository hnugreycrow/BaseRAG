package com.hnu.backend.rag.generation;

import com.hnu.backend.common.exception.ApiException;

/** 面向 RAG 回答阶段的中立流式模型端口，隔离具体模型客户端及供应商类型。 */
public interface AnswerGenerator {
  /**
   * 创建一次回答请求独占的流控制器。
   *
   * @return 可检查并主动关闭的流控制器
   */
  Control newControl();

  /**
   * 使用指定提示词流式生成完整回答。
   *
   * @param systemPrompt 服务端可信系统提示词
   * @param userPrompt 结构化用户提示词
   * @param reason 本次生成的业务原因
   * @param observer 流式生命周期观察器
   * @param control 本次回答请求独占的流控制器
   * @return 完整回答及实际模型信息
   * @throws ApiException 模型失败、响应不完整或请求被取消时抛出
   */
  Generation generate(
      String systemPrompt,
      String userPrompt,
      AttemptReason reason,
      StreamObserver observer,
      Control control);

  /** 使用统一请求传入回答的思考选择。 */
  Generation generate(
      Request request, AttemptReason reason, StreamObserver observer, Control control);

  /** 回答阶段的中立生成请求。 */
  record Request(String systemPrompt, String userPrompt, boolean thinkingEnabled) {}

  /** 一次模型调用的业务原因，用于区分主生成、供应商回退和引用修复。 */
  enum AttemptReason {
    /** 首选模型的首次回答。 */
    PRIMARY,
    /** 首选模型未输出正文时切换到后续候选模型。 */
    PROVIDER_FALLBACK,
    /** 首次回答包含非法引用后进行的唯一一次完整重生成。 */
    CITATION_REPAIR
  }

  /**
   * RAG 层使用的中立模型描述。
   *
   * @param id 本地模型候选配置 ID
   * @param provider 模型供应商标识
   * @param model 供应商侧模型名称
   */
  record ModelTarget(String id, String provider, String model) {}

  /**
   * 一次成功模型生成的不可变结果。
   *
   * @param content 完整回答正文
   * @param modelId 本地模型候选配置 ID
   * @param provider 实际模型供应商
   * @param model 实际模型名称
   */
  record Generation(String content, String modelId, String provider, String model) {}

  /** 接收单次回答中每个供应商尝试的生命周期和增量正文。 */
  interface StreamObserver {
    /** 返回当前尝试独占的回调接收器，兼容原有同步观察器。 */
    default StreamObserver bindAttempt() {
      return this;
    }

    /**
     * 模型候选即将开始生成。
     *
     * @param target 当前模型候选
     * @param reason 当前尝试的业务原因
     */
    void started(ModelTarget target, AttemptReason reason);

    /**
     * 当前生成尝试的业务记录已经准备完成，即将发送模型 HTTP 请求。
     *
     * @param target 当前模型候选
     */
    default void requesting(ModelTarget target) {}

    /**
     * 接收一段新生成的正文。
     *
     * @param text 非空增量正文
     */
    void delta(String text);

    /** 接收一段思考内容，与回答正文分开传递。 */
    default void reasoningDelta(String text) {}

    /**
     * 当前模型候选完整结束。
     *
     * @param target 已完成的模型候选
     * @param content 完整回答正文
     * @param finishReason 供应商返回的结束原因
     */
    void completed(ModelTarget target, String content, String finishReason);

    /**
     * 当前模型候选生成失败。
     *
     * @param target 失败的模型候选
     * @param partialContent 已收到的部分正文
     * @param error 归一化模型异常
     */
    void failed(ModelTarget target, String partialContent, ApiException error);
  }

  /** 可被会话线程主动关闭的模型流控制器。 */
  interface Control extends AutoCloseable {
    /**
     * 判断模型流是否已被取消。
     *
     * @return 已取消时为 true
     */
    boolean cancelled();

    /**
     * 在已取消或当前工作线程被中断时抛出统一取消异常。
     *
     * @throws ApiException 请求已取消时抛出
     */
    default void throwIfCancelled() {
      if (cancelled() || Thread.currentThread().isInterrupted()) {
        throw ApiException.cancelled();
      }
    }

    /** 主动关闭底层响应流并把控制器标记为已取消。 */
    @Override
    void close();
  }
}
