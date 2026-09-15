package com.hnu.backend.shared.web;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * HTTP 请求进入应用时捕获的追踪信息。
 *
 * @param requestId 可安全写入日志和 Trace 的请求标识
 * @param startedAt 请求进入过滤器时的墙钟时间
 * @param startedNanos 请求进入过滤器时的单调时钟读数
 */
public record RequestTiming(String requestId, OffsetDateTime startedAt, long startedNanos) {
  /** 校验请求标识和开始时间。 */
  public RequestTiming {
    requestId = Objects.requireNonNull(requestId, "requestId");
    startedAt = Objects.requireNonNull(startedAt, "startedAt");
  }
}
