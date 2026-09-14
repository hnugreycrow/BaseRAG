package com.hnu.backend.rag.mcp;

import java.util.Objects;

/** 安全执行器产生的工具观察结果，不包含原始参数或未掩码输出。 */
public record ToolObservation(
    String toolName,
    Status status,
    String reasonCode,
    String content,
    boolean truncated,
    long elapsedMs) {
  public ToolObservation {
    toolName = Objects.requireNonNull(toolName, "toolName");
    status = Objects.requireNonNull(status, "status");
    reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    content = content == null ? "" : content;
  }

  public enum Status {
    SUCCESS,
    REJECTED,
    TIMEOUT,
    FAILED
  }
}
