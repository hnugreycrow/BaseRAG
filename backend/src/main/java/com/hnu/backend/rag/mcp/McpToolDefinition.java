package com.hnu.backend.rag.mcp;

import com.hnu.backend.rag.JsonValues;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * MCP 工具的服务端注册信息；模型提供的同名描述不能替代该定义。
 *
 * @param name 工具的唯一名称，不能为空白
 * @param description 展示给分类模型的服务端描述，不能为空白
 * @param readOnly 是否允许作为自动调用的只读工具
 * @param inputSchema 服务端校验参数使用的结构快照
 * @param sensitiveFields 审计与输出中需要掩码的字段名
 */
public record McpToolDefinition(
    String name,
    String description,
    boolean readOnly,
    Map<String, Object> inputSchema,
    Set<String> sensitiveFields) {
  /** 规范化服务端名称并冻结 schema 与掩码字段，避免注册后定义被调用方改写。 */
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
