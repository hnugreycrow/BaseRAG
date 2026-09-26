package com.hnu.backend.model.client;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 单个模型目标的进程内熔断器；所有许可与状态转换在同一把锁下仲裁。 */
final class ModelCircuitBreaker {
  private static final Logger log = LoggerFactory.getLogger(ModelCircuitBreaker.class);

  private enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final String modelId;
  private final int threshold;
  private final long openNanos;
  private final LongSupplier ticker;
  private State state = State.CLOSED;
  private long epoch;
  private int failures;
  private long openUntil;
  private long rateLimitedUntil;
  private boolean rateLimited;
  private boolean probing;

  /** 使用毫秒冷却配置与可注入的单调纳秒时钟，便于确定性验证恢复边界。 */
  ModelCircuitBreaker(String modelId, int threshold, long openMs, LongSupplier ticker) {
    this.modelId = modelId;
    this.threshold = threshold;
    this.openNanos = TimeUnit.MILLISECONDS.toNanos(openMs);
    this.ticker = ticker;
  }

  /** 冷却到期后只放行一个探测，其余调用立即拒绝；不持锁执行网络操作。 */
  synchronized Permit acquire() {
    if (rateLimited && rateLimitedUntil - ticker.getAsLong() > 0) {
      throw ApiException.upstream(ErrorCode.MODEL_RATE_LIMITED, "模型服务限流冷却中，请稍后重试");
    }
    rateLimited = false;
    if (state == State.OPEN && openUntil - ticker.getAsLong() <= 0) {
      transition(State.HALF_OPEN, "cooldown_elapsed");
    }
    if (state == State.OPEN || (state == State.HALF_OPEN && probing)) {
      throw rejected();
    }
    boolean probe = state == State.HALF_OPEN;
    if (probe) {
      probing = true;
    }
    return new Permit(epoch, probe);
  }

  private ApiException rejected() {
    return ApiException.upstream(ErrorCode.MODEL_CIRCUIT_OPEN, "模型服务暂时不可用，请稍后重试");
  }

  private void transition(State next, String reason) {
    log.info("model circuit modelId={} from={} to={} reason={}", modelId, state, next, reason);
    state = next;
    epoch++;
    probing = false;
  }

  /** 一次候选调用共用一个许可；关闭未结算许可只释放探测，不改变失败计数。 */
  final class Permit implements AutoCloseable {
    private final long generation;
    private final boolean probe;
    private boolean settled;

    private Permit(long generation, boolean probe) {
      this.generation = generation;
      this.probe = probe;
    }

    /** 半开只发一个物理请求，旧周期的请求不能继续内部重试。 */
    boolean canRetry() {
      synchronized (ModelCircuitBreaker.this) {
        return !probe
            && !settled
            && generation == epoch
            && state == State.CLOSED
            && (!rateLimited || rateLimitedUntil - ticker.getAsLong() <= 0);
      }
    }

    /** 退避结束后再次检查，避免在另一并发调用已熔断时继续访问上游。 */
    void checkRetry() {
      synchronized (ModelCircuitBreaker.this) {
        if (rateLimited && rateLimitedUntil - ticker.getAsLong() > 0) {
          throw ApiException.upstream(ErrorCode.MODEL_RATE_LIMITED, "模型服务限流冷却中，请稍后重试");
        }
        if (!canRetry()) {
          throw rejected();
        }
      }
    }

    void success() {
      settle(true, false, 0);
    }

    void failure() {
      settle(false, true, 0);
    }

    void rateLimited(long delayMs) {
      settle(false, false, Math.max(1, delayMs));
    }

    private void settle(boolean success, boolean failure, long cooldownMs) {
      synchronized (ModelCircuitBreaker.this) {
        if (settled) {
          return;
        }
        settled = true;
        if (generation != epoch) {
          return;
        }
        probing = false;
        if (cooldownMs > 0) {
          long delay = TimeUnit.MILLISECONDS.toNanos(cooldownMs);
          long now = ticker.getAsLong();
          if (!rateLimited || delay > rateLimitedUntil - now) {
            rateLimitedUntil = now + delay;
          }
          rateLimited = true;
          log.warn("model rate limited modelId={} cooldownMs={}", modelId, cooldownMs);
        } else if (success) {
          failures = 0;
          if (state == State.HALF_OPEN) {
            transition(State.CLOSED, "probe_succeeded");
          }
        } else if (failure && (probe || ++failures >= threshold)) {
          failures = 0;
          openUntil = ticker.getAsLong() + openNanos;
          transition(State.OPEN, probe ? "probe_failed" : "failure_threshold");
        }
      }
    }

    @Override
    public void close() {
      settle(false, false, 0);
    }
  }
}
