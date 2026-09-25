package com.hnu.backend.observability.trace;

import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.rag.answer.AnswerGenerator;
import com.hnu.backend.rag.answer.AnswerStage;
import com.hnu.backend.shared.error.ApiException;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** 将流式业务观察器与模型尝试、引用校验的追踪生命周期组合。 */
public final class AnswerTraceObserver implements AnswerStage.Observer {
  private final TraceContext context;
  private final AnswerStage.Observer delegate;
  private final Supplier<UUID> attemptId;
  private final IntSupplier attemptIndex;
  private final BooleanSupplier cancelled;
  private volatile Attempt current;
  private RagRunTrace.Span citation;

  /** 创建一次回答生成范围内的追踪适配器，尝试标识在业务 started 回调后读取。 */
  public AnswerTraceObserver(
      TraceContext context,
      AnswerStage.Observer delegate,
      Supplier<UUID> attemptId,
      IntSupplier attemptIndex,
      BooleanSupplier cancelled) {
    this.context = context;
    this.delegate = delegate;
    this.attemptId = attemptId;
    this.attemptIndex = attemptIndex;
    this.cancelled = cancelled;
  }

  /** 返回最后一次模型调用句柄，由引用校验成功后的运行终态选择为有效模型。 */
  public RagRunTrace.Span finalModelSpan() {
    return current == null ? null : current.span;
  }

  /** 业务成功发送 SSE 后调用；不把 checkpoint 成功与否作为首内容的条件。 */
  public void sent(boolean reasoning, String text) {
    context.trace().deltaSent(reasoning, text);
  }

  @Override
  public void started(AnswerGenerator.ModelTarget target, AnswerGenerator.AttemptReason reason) {
    delegate.started(target, reason);
    if (reason != AnswerGenerator.AttemptReason.PRIMARY) {
      context.markDegraded();
    }
    current = new Attempt(target, reason, attemptId.get(), attemptIndex.getAsInt());
  }

  @Override
  public AnswerGenerator.StreamObserver bindAttempt() {
    return current;
  }

  @Override
  public void requesting(AnswerGenerator.ModelTarget target) {
    current.requesting(target);
  }

  @Override
  public void delta(String text) {
    current.delta(text);
  }

  @Override
  public void reasoningDelta(String text) {
    current.reasoningDelta(text);
  }

  @Override
  public void completed(AnswerGenerator.ModelTarget target, String content, String finishReason) {
    current.completed(target, content, finishReason);
  }

  @Override
  public void failed(
      AnswerGenerator.ModelTarget target, String partialContent, ApiException error) {
    current.failed(target, partialContent, error);
  }

  @Override
  public void generationSkipped(String reasonCode) {
    context.skipped(RagStageName.ANSWER_MODEL, null, reasonCode);
    context.skipped(RagStageName.CITATION_VALIDATION, null, reasonCode);
    delegate.generationSkipped(reasonCode);
  }

  @Override
  public void validationStarted() {
    citation = context.start(RagStageName.CITATION_VALIDATION, null, 1);
    delegate.validationStarted();
  }

  @Override
  public void validationCompleted(int citationCount) {
    if (citation != null) {
      citation.success(citationCount);
    }
    delegate.validationCompleted(citationCount);
  }

  @Override
  public void invalidReferences(String reasonCode, boolean repairScheduled) {
    if (citation != null) {
      citation.degraded(0, reasonCode);
    }
    delegate.invalidReferences(reasonCode, repairScheduled);
  }

  @Override
  public void normalizedAnswer(String content) {
    delegate.normalizedAnswer(content);
  }

  /** HTTP 回调闭包持有此对象，已结束或被替换的尝试不再修改业务与追踪状态。 */
  private final class Attempt implements AnswerGenerator.StreamObserver {
    private final AnswerGenerator.ModelTarget target;
    private final AnswerGenerator.AttemptReason reason;
    private final UUID id;
    private final int index;
    private RagRunTrace.Span span;
    private boolean ended;

    private Attempt(
        AnswerGenerator.ModelTarget target,
        AnswerGenerator.AttemptReason reason,
        UUID id,
        int index) {
      this.target = target;
      this.reason = reason;
      this.id = id;
      this.index = index;
    }

    @Override
    public void started(AnswerGenerator.ModelTarget target, AnswerGenerator.AttemptReason reason) {
      throw new IllegalStateException("Attempt is already bound");
    }

    @Override
    public synchronized void requesting(AnswerGenerator.ModelTarget ignored) {
      if (ended || current != this || span != null) {
        return;
      }
      span =
          context
              .start(RagStageName.ANSWER_MODEL, null, 1)
              .model(target.id(), target.provider(), target.model())
              .attempt(id, index);
      delegate.requesting(target);
    }

    @Override
    public synchronized void delta(String text) {
      content(false, text);
    }

    @Override
    public synchronized void reasoningDelta(String text) {
      content(true, text);
    }

    private void content(boolean reasoning, String text) {
      if (ended || current != this || text == null || text.isEmpty()) {
        return;
      }
      if (span != null) {
        span.content(reasoning, text);
      }
      if (reasoning) {
        delegate.reasoningDelta(text);
      } else {
        delegate.delta(text);
      }
    }

    @Override
    public synchronized void completed(
        AnswerGenerator.ModelTarget ignored, String content, String finishReason) {
      if (ended || current != this) {
        return;
      }
      ended = true;
      if (span != null) {
        span.success(1, reason.name());
      }
      delegate.completed(target, content, finishReason);
    }

    @Override
    public synchronized void failed(
        AnswerGenerator.ModelTarget ignored, String partialContent, ApiException error) {
      if (ended || current != this) {
        return;
      }
      ended = true;
      if (span != null) {
        if (cancelled.getAsBoolean()) {
          span.cancelled("GENERATION_CANCELLED");
        } else {
          span.error(error);
        }
      }
      delegate.failed(target, partialContent, error);
    }
  }
}
