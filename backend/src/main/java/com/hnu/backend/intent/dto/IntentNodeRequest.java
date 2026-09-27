package com.hnu.backend.intent.dto;

import com.hnu.backend.intent.model.IntentNode;
import java.util.List;
import java.util.UUID;

/**
 * 创建或更新意图节点的管理员输入；父节点和类型绑定由服务层校验。
 *
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
public record IntentNodeRequest(
    UUID parentId,
    String name,
    String description,
    List<String> examples,
    IntentNode.Kind kind,
    String toolName,
    List<UUID> knowledgeBaseIds,
    boolean enabled,
    int sortOrder) {}
