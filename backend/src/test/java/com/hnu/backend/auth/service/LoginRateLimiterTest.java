package com.hnu.backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.common.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class LoginRateLimiterTest {
  @Test
  void rejectsTheSixthAttemptAfterFiveFailures() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(any())).thenReturn("5");

    ApiException error =
        assertThrows(
            ApiException.class,
            () -> new LoginRateLimiter(redis).requireAllowed("alice", "127.0.0.1"));

    org.junit.jupiter.api.Assertions.assertEquals("LOGIN_RATE_LIMITED", error.code());
  }

  @Test
  void recordsFailuresWithHashedKeyAndFixedWindowScript() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    LoginRateLimiter limiter = new LoginRateLimiter(redis);

    limiter.recordFailure("alice", "127.0.0.1");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(redis).execute(any(), keys.capture(), eq("900"));
    String key = keys.getValue().getFirst();
    org.junit.jupiter.api.Assertions.assertTrue(key.startsWith("baserag:auth:login-fail:"));
    org.junit.jupiter.api.Assertions.assertFalse(key.contains("alice"));
    org.junit.jupiter.api.Assertions.assertFalse(key.contains("127.0.0.1"));
  }
}
