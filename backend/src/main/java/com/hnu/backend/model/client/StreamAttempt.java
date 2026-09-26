package com.hnu.backend.model.client;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 单个模型候选的流生命周期；超时只关闭本候选，用户取消由外层控制器传播。 */
final class StreamAttempt implements AutoCloseable {
  private static final long CANCELLATION_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(25);

  private enum State {
    ACTIVE,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    CLOSED
  }

  private final ScheduledExecutorService scheduler;
  private final long totalDeadline;
  private final long idleNanos;
  private long contentDeadline;
  private boolean receivedContent;
  private State state = State.ACTIVE;
  private String timeoutPhase;
  private ScheduledFuture<?> timer;
  private CompletableFuture<HttpResponse<InputStream>> request;
  private InputStream stream;

  /** 使用单调时钟设置首内容与总预算，内部 HTTP 重试共用此对象。 */
  StreamAttempt(ScheduledExecutorService scheduler, long totalDeadline, int firstMs, int idleMs) {
    this.scheduler = scheduler;
    this.totalDeadline = totalDeadline;
    this.idleNanos = TimeUnit.MILLISECONDS.toNanos(idleMs);
    this.contentDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(firstMs);
    schedule();
  }

  /** 在 deadline 到期时关闭网络资源，使阻塞读取退出。 */
  private synchronized void schedule() {
    if (timer != null) {
      timer.cancel(false);
    }
    if (state == State.ACTIVE) {
      timer = scheduler.schedule(this::expire, Math.max(0, remainingNanos()), TimeUnit.NANOSECONDS);
    }
  }

  private void expire() {
    synchronized (this) {
      if (state != State.ACTIVE) {
        return;
      }
      if (remainingNanos() > 0) {
        schedule();
        return;
      }
      timeoutPhase =
          totalDeadline - System.nanoTime() <= 0
              ? "TOTAL"
              : receivedContent ? "IDLE" : "FIRST_CONTENT";
      state = State.TIMED_OUT;
    }
    releaseNetwork();
  }

  synchronized long remainingNanos() {
    long now = System.nanoTime();
    return Math.min(totalDeadline - now, contentDeadline - now);
  }

  /** 在事件交付及网络异常映射前检查终态，保留超时和取消的区别。 */
  synchronized void check() throws HttpTimeoutException {
    if (state == State.ACTIVE && remainingNanos() <= 0) {
      timeoutPhase =
          totalDeadline - System.nanoTime() <= 0
              ? "TOTAL"
              : receivedContent ? "IDLE" : "FIRST_CONTENT";
      state = State.TIMED_OUT;
    }
    if (state == State.TIMED_OUT) {
      throw new HttpTimeoutException("Model stream timeout: " + timeoutPhase);
    }
    if (state != State.ACTIVE) {
      throw ApiException.cancelled();
    }
    if (Thread.currentThread().isInterrupted()) {
      throw ApiException.upstream(ErrorCode.REQUEST_INTERRUPTED, "请求已中断");
    }
  }

  synchronized String timeoutPhase() {
    return timeoutPhase == null ? "RESPONSE_HEADERS" : timeoutPhase;
  }

  /** 将 HTTP 自身报告的超时纳入相同终态仲裁；已生效的取消不能记为故障。 */
  synchronized void timeout() {
    if (state != State.ACTIVE && state != State.TIMED_OUT) {
      throw ApiException.cancelled();
    }
    if (state == State.ACTIVE) {
      state = State.TIMED_OUT;
      timeoutPhase = "RESPONSE_HEADERS";
    }
    timer.cancel(false);
  }

  void attach(CompletableFuture<HttpResponse<InputStream>> value) throws HttpTimeoutException {
    synchronized (this) {
      if (state == State.ACTIVE) {
        request = value;
        check();
        return;
      }
    }
    closeRequest(value);
    check();
  }

  void attach(InputStream value) throws HttpTimeoutException {
    synchronized (this) {
      if (state == State.ACTIVE) {
        stream = value;
        check();
        return;
      }
    }
    closeStream(value);
    check();
  }

  /** 检查事件许可并更新内容时限；回调只在生成线程执行，不能持锁阻塞超时调度器。 */
  void deliver(boolean content, Runnable action) throws HttpTimeoutException {
    synchronized (this) {
      check();
      if (content) {
        receivedContent = true;
        contentDeadline = System.nanoTime() + idleNanos;
        schedule();
      }
    }
    // 超时线程只关闭网络；当前回调返回前生成线程不会切换候选，旧事件不会进入新尝试。
    action.run();
    check();
  }

  synchronized void complete() throws HttpTimeoutException {
    check();
    state = State.COMPLETED;
    timer.cancel(false);
  }

  /** 协议或传输失败同样参与终态仲裁，避免取消或超时后再次记录供应商故障。 */
  synchronized void fail() throws HttpTimeoutException {
    check();
    state = State.FAILED;
    timer.cancel(false);
  }

  void cancel() {
    synchronized (this) {
      if (state == State.ACTIVE) {
        state = State.CANCELLED;
        timer.cancel(false);
      }
    }
    releaseNetwork();
  }

  /** 退避同样受候选和总预算限制，取消后最多等待一个短轮询周期。 */
  void pause(int attempt) throws InterruptedException, HttpTimeoutException {
    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250L << attempt);
    while (until - System.nanoTime() > 0) {
      check();
      long nanos = Math.min(until - System.nanoTime(), remainingNanos());
      if (nanos > 0) {
        TimeUnit.NANOSECONDS.sleep(Math.min(nanos, CANCELLATION_POLL_NANOS));
      }
    }
    check();
  }

  /** 关闭本次 HTTP 请求；后续重试保留候选的首内容 deadline。 */
  void releaseNetwork() {
    CompletableFuture<HttpResponse<InputStream>> pending;
    InputStream current;
    synchronized (this) {
      pending = request;
      current = stream;
      request = null;
      stream = null;
    }
    if (pending != null) {
      closeRequest(pending);
    }
    closeStream(current);
  }

  private static void closeRequest(CompletableFuture<HttpResponse<InputStream>> pending) {
    // 即使取消与响应完成竞争，迟到的响应体也必须关闭。
    pending.whenComplete(
        (response, error) -> {
          if (response != null) {
            closeStream(response.body());
          }
        });
    pending.cancel(true);
  }

  private static void closeStream(InputStream value) {
    if (value != null) {
      try {
        value.close();
      } catch (IOException ignored) {
        // 清理失败不覆盖原始超时、取消或供应商错误。
      }
    }
  }

  @Override
  public void close() {
    synchronized (this) {
      if (state == State.ACTIVE) {
        state = State.CLOSED;
      }
      if (timer != null) {
        timer.cancel(false);
      }
    }
    releaseNetwork();
  }
}
