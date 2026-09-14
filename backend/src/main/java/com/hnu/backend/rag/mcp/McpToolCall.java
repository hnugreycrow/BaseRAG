package com.hnu.backend.rag.mcp;

import com.hnu.backend.rag.support.JsonValues;
import java.util.Map;
import java.util.Objects;

/** 经过路由阶段生成、仍须由执行器再次校验的工具调用请求。 */
public record McpToolCall(String toolName, Map<String, Object> arguments) {
  public McpToolCall {
    toolName = Objects.requireNonNull(toolName, "toolName").strip();
    arguments = JsonValues.immutableObject(arguments == null ? Map.of() : arguments);
    if (toolName.isEmpty()) throw new IllegalArgumentException("MCP tool name must not be blank");
  }
}
