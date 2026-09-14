package com.hnu.backend.rag.routing;

/** 子问题经过安全策略归一化后的主意图。 */
public enum IntentType {
  KNOWLEDGE_RETRIEVAL,
  MCP_TOOL,
  SYSTEM_CHAT
}
