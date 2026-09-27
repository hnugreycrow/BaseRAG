package com.hnu.backend.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  @Test
  void escapesControlCharactersAndBoundsLongValues() {
    assertEquals("\"a\\n\\r\\tb\"", RagDecisionLog.value("a\n\r\tb"));
    assertEquals("null", RagDecisionLog.value(null));
    String bounded = RagDecisionLog.value("x".repeat(2100));
    assertTrue(bounded.contains("[truncated]"));
    assertFalse(bounded.contains("x".repeat(2001)));
  }
}
