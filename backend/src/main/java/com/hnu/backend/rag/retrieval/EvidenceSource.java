package com.hnu.backend.rag.retrieval;

import java.util.Objects;
import java.util.UUID;

/**
 * 一条证据候选对应的持久化来源位置。候选去重后可以同时关联多条来源。
 *
 * @param chunkId 来源分块 ID，也是检索归因与具体来源之间的关联键
 * @param knowledgeBaseId 来源知识库 ID
 * @param knowledgeBaseName 来源知识库名称
 * @param documentId 来源文档 ID
 * @param versionId 来源文档版本 ID
 * @param documentName 来源文档名称
 * @param chunkIndex 分块在当前文档版本内的零基顺序，用于判断相邻分块
 * @param heading 分块标题，可能为空
 * @param lineStart 原文起始行
 * @param lineEnd 原文结束行
 */
public record EvidenceSource(
    UUID chunkId,
    UUID knowledgeBaseId,
    String knowledgeBaseName,
    UUID documentId,
    UUID versionId,
    String documentName,
    int chunkIndex,
    String heading,
    int lineStart,
    int lineEnd) {
  public EvidenceSource {
    chunkId = Objects.requireNonNull(chunkId, "chunkId");
    documentId = Objects.requireNonNull(documentId, "documentId");
    versionId = Objects.requireNonNull(versionId, "versionId");
    if (chunkIndex < 0 || lineStart < 1 || lineEnd < lineStart) {
      throw new IllegalArgumentException("Invalid evidence source position");
    }
  }
}
