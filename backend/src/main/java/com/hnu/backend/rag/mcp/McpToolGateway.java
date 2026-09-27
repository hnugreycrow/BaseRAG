package com.hnu.backend.rag.mcp;

import java.util.List;

/** 外部 MCP 传输的中立端口；实现必须只登记自身能够执行的工具。 */
public interface McpToolGateway {
  /**
   * 列出当前网关可执行的服务端工具定义。
   *
   * @return 工具定义列表；没有工具时返回空列表
   */
  List<McpToolDefinition> tools();

  /**
   * 调用当前网关登记的工具。
   *
   * @param call 已选定工具及校验后的输入
   * @return 工具原始结果，后续阶段负责统一序列化和脱敏
   */
  Object invoke(McpToolCall call);
}
