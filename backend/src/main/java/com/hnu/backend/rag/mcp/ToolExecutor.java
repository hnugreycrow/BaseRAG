package com.hnu.backend.rag.mcp;

/** 可替换的 MCP 执行边界。 */
public interface ToolExecutor {
  /** 执行工具调用，保持超时起点及错误归一化语义。 */
  ToolObservation execute(McpToolCall call);
}
