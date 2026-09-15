package com.hnu.backend.knowledgebase.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 知识库详情响应。
 *
 * @param id 知识库标识
 * @param name 知识库名称
 * @param embeddingModel 绑定的向量模型名称
 * @param embeddingModelId 稳定的模型配置标识
 * @param embeddingProvider 向量供应商标识
 * @param embeddingDimensions 向量维度
 * @param documentCount 当前文档数量
 * @param createdAt 创建时间
 */
public record KnowledgeBaseResponse(
    UUID id,
    String name,
    String embeddingModel,
    String embeddingModelId,
    String embeddingProvider,
    Integer embeddingDimensions,
    long documentCount,
    OffsetDateTime createdAt) {}
