package com.hnu.backend.model.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.shared.error.ApiException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StreamAttemptTest {
  private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);

  StreamAttemptTest() {
    scheduler.setRemoveOnCancelPolicy(true);
  }

  @AfterEach
  void close() {
    scheduler.shutdownNow();
  }

  @Test
  void cancellationClosesAStreamAttachedAfterCancellation() throws Exception {
    try (var attempt = attempt()) {
      attempt.cancel();
      InputStream late = mock(InputStream.class);
      assertThrows(ApiException.class, () -> attempt.attach(late));
      verify(late).close();
      assertThrows(ApiException.class, () -> attempt.deliver(true, () -> fail("late event")));
    }
    assertTrue(scheduler.getQueue().isEmpty());
  }

  @Test
  void completionRejectsLateEventsAndCancelsTimer() throws Exception {
    try (var attempt = attempt()) {
      attempt.complete();
      attempt.cancel();
      assertThrows(ApiException.class, () -> attempt.deliver(true, () -> fail("late event")));
    }
    assertTrue(scheduler.getQueue().isEmpty());
  }

  @Test
  void cancellationCannotBeConvertedToProviderTimeout() {
    try (var attempt = attempt()) {
      attempt.cancel();
      assertEquals(
          "GENERATION_CANCELLED", assertThrows(ApiException.class, attempt::timeout).code());
    }
  }

  @Test
  void timeoutCannotBeConvertedToCancellation() throws Exception {
    try (var attempt = attempt()) {
      attempt.timeout();
      attempt.cancel();
      assertThrows(HttpTimeoutException.class, attempt::check);
    }
  }

  @Test
  void interruptedReaderDoesNotBecomeProviderFailure() {
    try (var attempt = attempt()) {
      Thread.currentThread().interrupt();
      try {
        assertEquals(
            "REQUEST_INTERRUPTED", assertThrows(ApiException.class, attempt::check).code());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void cancellationClosesAlreadyCompletedResponseEvenBeforeBodyAttachment() throws Exception {
    try (var attempt = attempt()) {
      InputStream body = mock(InputStream.class);
      HttpResponse<InputStream> response = mock(HttpResponse.class);
      when(response.body()).thenReturn(body);
      attempt.attach(CompletableFuture.completedFuture(response));
      attempt.cancel();
      verify(body).close();
    }
  }

  @Test
  void cancellationAbortsPendingHeaders() throws Exception {
    try (var attempt = attempt()) {
      var response = new CompletableFuture<HttpResponse<InputStream>>();
      attempt.attach(response);
      attempt.cancel();
      assertTrue(response.isCancelled());
    }
  }

  @Test
  void slowConsumerDoesNotBlockTimeoutScheduler() throws Exception {
    var entered = new CountDownLatch(1);
    var leave = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor();
        var attempt =
            new StreamAttempt(
                scheduler, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), 2_000, 100)) {
      attempt.attach(
          new java.io.ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() {
              closed.countDown();
            }
          });
      Future<?> delivery =
          workers.submit(
              () ->
                  assertThrows(
                      HttpTimeoutException.class,
                      () ->
                          attempt.deliver(
                              true,
                              () -> {
                                entered.countDown();
                                try {
                                  leave.await(2, TimeUnit.SECONDS);
                                } catch (InterruptedException error) {
                                  Thread.currentThread().interrupt();
                                  throw new AssertionError(error);
                                }
                              })));
      try {
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(closed.await(1, TimeUnit.SECONDS));
      } finally {
        leave.countDown();
      }
      delivery.get(2, TimeUnit.SECONDS);
    }
    assertTrue(scheduler.getQueue().isEmpty());
  }

  private StreamAttempt attempt() {
    return new StreamAttempt(
        scheduler, System.nanoTime() + TimeUnit.SECONDS.toNanos(10), 5_000, 5_000);
  }
}
