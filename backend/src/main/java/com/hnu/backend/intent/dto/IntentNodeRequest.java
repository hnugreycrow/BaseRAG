package com.hnu.backend.intent.dto;

import com.hnu.backend.intent.model.IntentNode;
import java.util.List;
import java.util.UUID;

/** 创建或更新意图节点的管理员输入。 */
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
