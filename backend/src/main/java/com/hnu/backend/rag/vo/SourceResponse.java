package com.hnu.backend.rag.vo;

import java.util.UUID;

/**
 * 回答所引用的检索来源。
 *
 * @param citationId 回答中使用的引用标识
 * @param knowledgeBaseId 知识库标识
 * @param knowledgeBaseName 知识库名称
 * @param chunkId 文档分块标识
 * @param documentId 文档标识
 * @param versionId 文档版本标识
 * @param documentName 文档名称
 * @param heading 分块所属标题
 * @param lineStart 原文起始行号
 * @param lineEnd 原文结束行号
 * @param similarity 检索相似度
 * @param content 分块正文
 */
public record SourceResponse(
    String citationId,
    UUID knowledgeBaseId,
    String knowledgeBaseName,
    UUID chunkId,
    UUID documentId,
    UUID versionId,
    String documentName,
    String heading,
    int lineStart,
    int lineEnd,
    double similarity,
    String content) {}
