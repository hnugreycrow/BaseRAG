package com.hnu.backend.common.exception;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SafeExceptionLogTest {
  @Test
  void rendersTypesAndFramesWithoutThrowableMessages() {
    IllegalStateException cause = new IllegalStateException("secret SQL and provider body");
    RuntimeException error = new RuntimeException("secret document text", cause);

    String rendered = SafeExceptionLog.render(error);

    assertTrue(rendered.contains(RuntimeException.class.getName()));
    assertTrue(rendered.contains(IllegalStateException.class.getName()));
    assertTrue(rendered.contains("SafeExceptionLogTest"));
    assertFalse(rendered.contains("secret SQL"));
    assertFalse(rendered.contains("secret document"));
  }
}
