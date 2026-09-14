package com.hnu.backend.rag.mcp;

import java.util.Objects;

/**
 * 安全执行器产生的工具观察结果，不包含原始参数或未掩码输出。
 *
 * @param toolName 服务端注册的工具名称
 * @param argumentsSummary 经过敏感字段掩码和长度限制的参数摘要
 * @param auditSource 可审计的网关实现与工具组合标识，不包含远端返回正文
 * @param status 工具调用终态
 * @param reasonCode 稳定结果码
 * @param content 已脱敏并按配置截断的工具输出
 * @param truncated 工具输出是否因长度预算被截断
 * @param elapsedMs 从安全复核开始计算的调用耗时
 */
public record ToolObservation(
    String toolName,
    String argumentsSummary,
    String auditSource,
    Status status,
    String reasonCode,
    String content,
    boolean truncated,
    long elapsedMs) {
  public ToolObservation {
    toolName = Objects.requireNonNull(toolName, "toolName");
    argumentsSummary = argumentsSummary == null ? "" : argumentsSummary;
    auditSource = Objects.requireNonNull(auditSource, "auditSource");
    status = Objects.requireNonNull(status, "status");
    reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    content = content == null ? "" : content;
    if (toolName.isBlank() || auditSource.isBlank() || elapsedMs < 0) {
      throw new IllegalArgumentException("Invalid tool observation");
    }
  }

  public enum Status {
    SUCCESS,
    REJECTED,
    TIMEOUT,
    FAILED
  }
}
