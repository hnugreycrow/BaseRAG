package com.hnu.backend.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RagDecisionLogTest {
  @Test
  void ignoresLoggingFailureWithoutRetryingTheBusinessOperation() {
    AtomicInteger attempts = new AtomicInteger();

    assertDoesNotThrow(
        () ->
            RagDecisionLog.emit(
                () -> {
                  attempts.incrementAndGet();
                  throw new IllegalStateException("log sink failed");
                }));

    assertEquals(1, attempts.get());
  }
}
