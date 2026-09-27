package com.hnu.backend.intent.model;

import java.util.List;
import java.util.UUID;

/**
 * 管理员维护的全局意图节点；无子节点且带类型的节点可参与分类。
 *
 * @param id 节点标识
 * @param parentId 父节点标识；根节点为空
 * @param name 展示名称
 * @param description 供分类模型理解节点用途的描述
 * @param examples 分类示例文本
 * @param kind 叶子节点的路由类型；非叶子节点可为空
 * @param toolName MCP 路由绑定的工具名称；其他类型可为空
 * @param knowledgeBaseIds 知识检索路由绑定的知识库标识
 * @param enabled 是否启用该节点；叶子仍受父节点启用状态约束
 * @param sortOrder 节点排序值，较小的值优先展示
 */
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
  /** 叶子节点能够执行的知识检索、只读工具或系统闲聊路由。 */
  public enum Kind {
    KB,
    MCP,
    SYSTEM
  }
}
