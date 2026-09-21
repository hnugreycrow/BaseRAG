package com.hnu.backend.shared.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {
  @Test
  void definesUniqueCompleteStableMetadata() {
    Set<String> codes =
        Arrays.stream(ErrorCode.values()).map(ErrorCode::code).collect(Collectors.toSet());

    assertEquals(ErrorCode.values().length, codes.size());
    for (ErrorCode code : ErrorCode.values()) {
      assertFalse(code.defaultMessage().isBlank());
      assertTrue(code.status().isError());
    }
  }

  @Test
  void resolvesPersistedCodesAndToleratesUnknownLegacyValues() {
    assertEquals("模型请求超时，请稍后重试", ErrorCode.messageFor("MODEL_TIMEOUT"));
    assertNull(ErrorCode.messageFor("LEGACY_UNKNOWN"));
    assertFalse(ErrorCode.retryable("INVALID_QUESTION"));
    assertTrue(ErrorCode.retryable("LEGACY_UNKNOWN"));
  }
}
