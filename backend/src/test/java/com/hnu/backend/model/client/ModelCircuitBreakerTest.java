package com.hnu.backend.model.client;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.common.exception.ApiException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ModelCircuitBreakerTest {
  private final AtomicLong clock = new AtomicLong();
  private final ModelCircuitBreaker breaker =
      new ModelCircuitBreaker("test", 2, 30_000, clock::get);

  @Test
  void consecutiveFailuresOpenAndSuccessResetsCounter() {
    breaker.acquire().failure();
    breaker.acquire().success();
    breaker.acquire().failure();
    var last = breaker.acquire();
    last.failure();
    assertRejected();
    last.success();
    last.close();
    assertRejected();
  }

  @Test
  void concurrentRecoveryAllowsExactlyOneProbe() throws Exception {
    open();
    advance(30_000);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      var attempts = new ArrayList<Future<ModelCircuitBreaker.Permit>>();
      for (int i = 0; i < 32; i++) {
        attempts.add(
            workers.submit(
                () -> {
                  start.await();
                  try {
                    return breaker.acquire();
                  } catch (ApiException rejected) {
                    assertEquals("MODEL_CIRCUIT_OPEN", rejected.code());
                    return null;
                  }
                }));
      }
      start.countDown();
      var admitted = new ArrayList<ModelCircuitBreaker.Permit>();
      for (var attempt : attempts) {
        var permit = attempt.get(2, TimeUnit.SECONDS);
        if (permit != null) {
          admitted.add(permit);
        }
      }
      assertEquals(1, admitted.size());
      assertFalse(admitted.getFirst().canRetry());
      admitted.getFirst().success();
      try (var normal = breaker.acquire()) {
        assertTrue(normal.canRetry());
      }
    }
  }

  @Test
  void cancelledProbeReleasesSlotAndFailedProbeReopens() {
    open();
    advance(30_000);
    breaker.acquire().close();
    var probe = breaker.acquire();
    assertRejected();
    probe.failure();
    advance(29_999);
    assertRejected();
    advance(1);
    breaker.acquire().success();
    breaker.acquire().close();
  }

  @Test
  void staleSuccessCannotClearOpenAndStaleFailureCannotPoisonRecovery() {
    var oldSuccess = breaker.acquire();
    var oldFailure = breaker.acquire();
    open();
    oldSuccess.success();
    assertRejected();
    assertFalse(oldFailure.canRetry());
    advance(30_000);
    breaker.acquire().success();
    oldFailure.failure();
    breaker.acquire().failure();
    breaker.acquire().close();
  }

  @Test
  void ignoredOutcomeDoesNotResetFailures() {
    breaker.acquire().failure();
    breaker.acquire().close();
    breaker.acquire().failure();
    assertRejected();
  }

  @Test
  void rateLimitDoesNotCountFailureOrGetClearedByConcurrentSuccess() {
    breaker.acquire().failure();
    var success = breaker.acquire();
    var retrying = breaker.acquire();
    var limited = breaker.acquire();
    limited.rateLimited(60_000);
    assertEquals(
        "MODEL_RATE_LIMITED", assertThrows(ApiException.class, retrying::checkRetry).code());
    retrying.close();
    success.success();
    assertEquals("MODEL_RATE_LIMITED", assertThrows(ApiException.class, breaker::acquire).code());
    advance(60_000);
    breaker.acquire().failure();
    breaker.acquire().close();
  }

  @Test
  void rateLimitedProbeCanBeRetriedAfterCooldown() {
    open();
    advance(30_000);
    breaker.acquire().rateLimited(1_000);
    advance(1_000);
    var probe = breaker.acquire();
    assertFalse(probe.canRetry());
    assertRejected();
    probe.success();
  }

  private void open() {
    breaker.acquire().failure();
    breaker.acquire().failure();
  }

  private void advance(long millis) {
    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
  }

  private void assertRejected() {
    assertEquals("MODEL_CIRCUIT_OPEN", assertThrows(ApiException.class, breaker::acquire).code());
  }
}
