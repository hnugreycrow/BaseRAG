package com.hnu.backend.knowledgebase.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建或更新知识库的请求。
 *
 * @param name 非空且不超过 200 个字符的知识库名称
 * @param embeddingModelId 需要绑定的向量模型配置标识
 */
public record KnowledgeBaseRequest(
    @NotBlank @Size(max = 200) String name, String embeddingModelId) {}
