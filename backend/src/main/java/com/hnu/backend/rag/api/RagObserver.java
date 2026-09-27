package com.hnu.backend.rag.api;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.observability.trace.AnswerTrace;
import com.hnu.backend.rag.vo.SourceResponse;

/** 回答增量、来源和引用生命周期契约；回调失败不得忽略。 */
public interface RagObserver extends AnswerGenerator.StreamObserver {
  /** 回答引用位置改变时通知流式调用方重置并发送规范化后的完整正文。 */
  default void normalizedAnswer(String content) {}

  /** 通知调用方本轮没有证据且未调用最终回答模型。 */
  default void generationSkipped(String reasonCode) {}

  /** 通知调用方开始校验当前完整回答的引用。 */
  default void validationStarted() {}

  /**
   * 通知调用方当前回答的引用校验成功。
   *
   * @param citationCount 合法知识引用数量
   */
  default void validationCompleted(int citationCount) {}

  /**
   * 通知调用方当前已完成尝试包含非法引用。
   *
   * @param reasonCode 稳定失败码
   * @param repairScheduled 是否即将清空正文并进行引用修复
   */
  void invalidReferences(String reasonCode, boolean repairScheduled);

  /** 来源已准备好；持久化失败应原样抛出并终止执行。 */
  default void prepared(String question, java.util.List<SourceResponse> sources) {}

  /** 检查调用方是否仍允许接收结果。 */
  default void ensureActive() {}

  /** 返回当前模型尝试标识；无持久化适配器时为空。 */
  default java.util.UUID attemptId() {
    return null;
  }

  /** 返回当前模型尝试序号。 */
  default int attemptIndex() {
    return 0;
  }

  /** 记录当前回答观测句柄，便于终态选择模型。 */
  default void traceReady(AnswerTrace trace) {}

  /** 返回不持久化、不传输的观察器，供同步调用与测试使用。 */
  static RagObserver noop() {
    return new RagObserver() {
      @Override
      public void started(
          AnswerGenerator.ModelTarget target, AnswerGenerator.AttemptReason reason) {}

      @Override
      public void delta(String text) {}

      @Override
      public void completed(AnswerGenerator.ModelTarget target, String content, String reason) {}

      @Override
      public void failed(AnswerGenerator.ModelTarget target, String content, ApiException error) {}

      @Override
      public void invalidReferences(String reason, boolean repair) {}
    };
  }
}
