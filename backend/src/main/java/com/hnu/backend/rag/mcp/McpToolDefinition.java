package com.hnu.backend.rag.mcp;

import com.hnu.backend.rag.support.JsonValues;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** MCP 工具的服务端注册信息；模型提供的同名描述不能替代该定义。 */
public record McpToolDefinition(
    String name,
    String description,
    boolean readOnly,
    Map<String, Object> inputSchema,
    Set<String> sensitiveFields) {
  public McpToolDefinition {
    name = Objects.requireNonNull(name, "name").strip();
    description = Objects.requireNonNull(description, "description").strip();
    inputSchema = JsonValues.immutableObject(inputSchema);
    sensitiveFields = Set.copyOf(sensitiveFields == null ? Set.of() : sensitiveFields);
    if (name.isEmpty() || description.isEmpty()) {
      throw new IllegalArgumentException("MCP tool name and description must not be blank");
    }
  }
}
