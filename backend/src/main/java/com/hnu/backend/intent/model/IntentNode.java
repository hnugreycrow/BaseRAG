package com.hnu.backend.intent.model;

import java.util.List;
import java.util.UUID;

/** 管理员维护的全局意图节点；无子节点且带类型的节点可参与分类。 */
public record IntentNode(
    UUID id,
    UUID parentId,
    String name,
    String description,
    List<String> examples,
    Kind kind,
    String toolName,
    List<UUID> knowledgeBaseIds,
    boolean enabled,
    int sortOrder) {
  public enum Kind {
    KB,
    MCP,
    SYSTEM
  }
}
