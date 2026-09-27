package com.hnu.backend.rag.mcp;

import com.hnu.backend.rag.JsonValues;
import java.util.Map;
import java.util.Objects;

/**
 * 经过路由阶段生成、仍须由执行器再次校验的工具调用请求。
 *
 * @param toolName 待调用的工具名称，不能为空白
 * @param arguments 参数快照；空值归一化为空映射
 */
public record McpToolCall(String toolName, Map<String, Object> arguments) {
  /** 规范化名称并冻结参数，防止路由复核与实际调用读取不同值。 */
  public McpToolCall {
    toolName = Objects.requireNonNull(toolName, "toolName").strip();
    arguments = JsonValues.immutableObject(arguments == null ? Map.of() : arguments);
    if (toolName.isEmpty()) {
      throw new IllegalArgumentException("MCP tool name must not be blank");
    }
  }
}
