package com.hnu.backend.rag.mcp;

import java.util.List;

/** 外部 MCP 传输的中立端口；实现必须只登记自身能够执行的工具。 */
public interface McpToolGateway {
  List<McpToolDefinition> tools();

  Object invoke(McpToolCall call);
}
