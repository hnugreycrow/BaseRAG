package com.hnu.backend.rag.execution;

import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.observability.trace.TraceContext;
import com.hnu.backend.rag.mcp.ToolObservation;
import com.hnu.backend.rag.planning.SubQuestion;
import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import com.hnu.backend.rag.routing.IntentRoute;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 调度子问题任务，统一提交计时、超时、取消传播和工作线程观测。 */
final class SubQuestionScheduler {
  private static final long CANCELLATION_POLL_MS = 50;
  private final ExecutorService executor;

  /** 接管执行器生命周期；并发容量由调用方配置。 */
  SubQuestionScheduler(ExecutorService executor) {
    this.executor = executor;
  }

  /**
   * 并发执行任务并按提交顺序收集结果。
   *
   * <p>提交或等待发生运行时异常时，对本批次所有已提交任务请求取消；已完成任务不受影响，运行中任务通过中断请求停止。
   *
   * <p>单个任务返回 {@code FAILED} 或 {@code TIMEOUT} 结果时，不取消其他任务。
   *
   * @param tasks 待执行任务，不包含已跳过的系统聊天路由
   * @param cancellationToken 外部取消信号，等待期间最多间隔 50 毫秒检查一次
   * @param trace 子问题阶段的父观测上下文
   * @return 按输入顺序排列的结果，单个超时保留为 TIMEOUT 结果
   */
  List<SubQuestionExecution> execute(
      List<Task> tasks, CancellationToken cancellationToken, TraceContext trace) {
    List<TaskHandle> handles = new ArrayList<>();
    List<SubQuestionExecution> results = new ArrayList<>();
    try {
      for (Task task : tasks) {
        long submittedAt = System.nanoTime();
        TaskObservation observation = new TaskObservation(trace, task.question().id(), submittedAt);
        Future<SubQuestionExecution> future =
            executor.submit(
                () -> {
                  RagRunTrace.Span span = observation.begin();
                  if (span == null) {
                    throw ApiException.cancelled();
                  }
                  try {
                    SubQuestionExecution result =
                        task.operation().execute(span.context(), submittedAt);
                    // 业务预算包含排队时间；观测耗时只覆盖工作线程执行区间。
                    if (task.timeoutMs() > 0 && result.elapsedMs() > task.timeoutMs()) {
                      result =
                          result(
                              task.question(),
                              task.route(),
                              SubQuestionExecution.Status.TIMEOUT,
                              List.of(),
                              null,
                              ErrorCode.SUBQUESTION_TIMEOUT.code(),
                              submittedAt);
                    }
                    observation.completed(result);
                    return result;
                  } catch (RuntimeException | Error error) {
                    observation.failed(error);
                    throw error;
                  }
                });
        handles.add(
            new TaskHandle(
                task.question(), task.route(), future, submittedAt, task.timeoutMs(), observation));
      }
      for (TaskHandle handle : handles) {
        results.add(await(handle, cancellationToken));
      }
    } catch (RuntimeException error) {
      handles.forEach(
          handle -> {
            handle.observation().failed(error);
            handle.future().cancel(true);
          });
      throw error;
    }
    return results;
  }

  /** 中断运行中任务并停止接收新任务。 */
  void close() {
    executor.shutdownNow();
  }

  /** 执行一个子问题的业务逻辑；提交时钟用于计算包含排队的业务耗时。 */
  @FunctionalInterface
  interface Operation {
    /** 使用工作线程的观测上下文执行，submittedAt 的单位为单调时钟纳秒。 */
    SubQuestionExecution execute(TraceContext trace, long submittedAt);
  }

  /**
   * 调度所需的任务描述，业务依赖由 operation 捕获。
   *
   * @param question 子问题
   * @param route 已校验的路由
   * @param timeoutMs 含排队的总预算，单位毫秒；0 表示由检索通道自行计时
   * @param operation 具体检索或工具调用逻辑
   */
  record Task(SubQuestion question, IntentRoute route, long timeoutMs, Operation operation) {}

  /** 根据子问题终态结束其外层阶段。 */
  private void observed(RagRunTrace.Span span, SubQuestionExecution execution) {
    if (execution.status() == SubQuestionExecution.Status.FAILED
        || execution.status() == SubQuestionExecution.Status.TIMEOUT) {
      span.degraded(execution.candidates().size(), execution.reasonCode());
    } else if (execution.status() == SubQuestionExecution.Status.SKIPPED) {
      span.skipped(0, execution.reasonCode());
    } else {
      span.success(execution.candidates().size());
    }
  }

  private SubQuestionExecution await(TaskHandle handle, CancellationToken cancellationToken) {
    long deadline =
        handle.timeoutMs() > 0
            ? handle.startedAt() + TimeUnit.MILLISECONDS.toNanos(handle.timeoutMs())
            : Long.MAX_VALUE;
    while (true) {
      cancellationToken.throwIfCancelled();
      try {
        // 先读取已经完成的结果，避免等待前序任务后把早已结束的后序任务误判为超时。
        if (handle.future().isDone()) {
          return enforceTimeout(handle, handle.future().get());
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          handle.observation().timeout();
          handle.future().cancel(true);
          return result(
              handle.question(),
              handle.route(),
              SubQuestionExecution.Status.TIMEOUT,
              List.of(),
              null,
              ErrorCode.SUBQUESTION_TIMEOUT.code(),
              handle.startedAt());
        }
        long waitNanos = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(CANCELLATION_POLL_MS));
        return enforceTimeout(handle, handle.future().get(waitNanos, TimeUnit.NANOSECONDS));
      } catch (TimeoutException ignored) {
        // 短轮询让仅改变取消令牌、未中断父线程的调用方也能快速停止所有子任务。
      } catch (InterruptedException error) {
        handle.future().cancel(true);
        Thread.currentThread().interrupt();
        throw ApiException.cancelled();
      } catch (ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof RuntimeException runtime) {
          throw runtime;
        }
        throw new IllegalStateException(cause);
      }
    }
  }

  private SubQuestionExecution enforceTimeout(TaskHandle handle, SubQuestionExecution completed) {
    if (handle.timeoutMs() == 0 || completed.elapsedMs() <= handle.timeoutMs()) {
      return completed;
    }
    return result(
        handle.question(),
        handle.route(),
        SubQuestionExecution.Status.TIMEOUT,
        List.of(),
        null,
        ErrorCode.SUBQUESTION_TIMEOUT.code(),
        handle.startedAt());
  }

  private SubQuestionExecution result(
      SubQuestion question,
      IntentRoute route,
      SubQuestionExecution.Status status,
      List<EvidenceCandidate> candidates,
      ToolObservation observation,
      String reasonCode,
      long startedAt) {
    return new SubQuestionExecution(
        question.id(),
        route.intent(),
        status,
        candidates,
        observation,
        reasonCode,
        elapsedMillis(startedAt));
  }

  private long elapsedMillis(long startedAt) {
    return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
  }

  /** 保证排队取消、超时与工作线程完成只有一个终态获胜。 */
  private final class TaskObservation {
    private final TraceContext parent;
    private final String questionId;
    private final long submittedAt;
    private RagRunTrace.Span span;
    private boolean ended;

    private TaskObservation(TraceContext parent, String questionId, long submittedAt) {
      this.parent = parent;
      this.questionId = questionId;
      this.submittedAt = submittedAt;
    }

    private synchronized RagRunTrace.Span begin() {
      if (ended) {
        return null;
      }
      span = parent.start(RagStageName.SUBQUESTION_EXECUTION, questionId, 1).queued(submittedAt);
      return span;
    }

    private synchronized void completed(SubQuestionExecution result) {
      if (ended) {
        return;
      }
      ended = true;
      observed(span, result);
    }

    private synchronized void timeout() {
      if (ended) {
        return;
      }
      boolean queued = span == null;
      if (queued) {
        begin();
      }
      ended = true;
      span.stopChildren(false, ErrorCode.SUBQUESTION_TIMEOUT.code());
      if (queued) {
        span.cancelledBeforeStart(ErrorCode.SUBQUESTION_TIMEOUT.code());
      } else {
        span.degraded(0, ErrorCode.SUBQUESTION_TIMEOUT.code());
      }
      parent.markDegraded();
    }

    private synchronized void failed(Throwable error) {
      if (ended) {
        return;
      }
      boolean queued = span == null;
      if (queued) {
        begin();
      }
      ended = true;
      boolean cancelled =
          error instanceof ApiException api
              && ErrorCode.GENERATION_CANCELLED.code().equals(api.code());
      span.stopChildren(
          cancelled,
          cancelled ? ErrorCode.GENERATION_CANCELLED.code() : ErrorCode.EXECUTION_FAILED.code());
      if (queued) {
        span.cancelledBeforeStart(
            cancelled ? ErrorCode.GENERATION_CANCELLED.code() : ErrorCode.EXECUTION_FAILED.code());
      } else {
        span.error(error);
      }
    }
  }

  /**
   * 已提交子任务及其独立计时边界。
   *
   * @param question 子任务对应的规划问题
   * @param route 决定任务执行检索还是工具调用的安全路由
   * @param future 用于等待、超时中断和总取消传播的并发句柄
   * @param startedAt 提交任务时的单调时钟值，MCP 任务的排队时间也计入其预算
   * @param timeoutMs MCP 任务允许占用的最长时间；知识检索由检索服务单独计时，值为 0
   * @param observation 工作线程与超时线程共享的幂等观测句柄
   */
  private record TaskHandle(
      SubQuestion question,
      IntentRoute route,
      Future<SubQuestionExecution> future,
      long startedAt,
      long timeoutMs,
      TaskObservation observation) {}
}
