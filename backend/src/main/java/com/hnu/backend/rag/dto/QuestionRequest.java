package com.hnu.backend.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * RAG 问答请求。
 *
 * @param question 用户问题
 * @param knowledgeBaseIds 指定检索的知识库标识；为空时检索全部可用知识库
 */
public record QuestionRequest(
    @NotBlank String question, @Size(min = 1, max = 20) List<UUID> knowledgeBaseIds) {}
