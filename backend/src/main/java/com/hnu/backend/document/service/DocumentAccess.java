package com.hnu.backend.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hnu.backend.document.entity.Document;
import com.hnu.backend.document.entity.DocumentVersion;
import com.hnu.backend.document.mapper.DocumentMapper;
import com.hnu.backend.document.mapper.DocumentVersionMapper;
import com.hnu.backend.knowledgebase.service.KnowledgeBaseService;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import java.util.UUID;

/** 统一文档归属校验与最新版本查找。 */
final class DocumentAccess {
  private final KnowledgeBaseService knowledgeBases;
  private final DocumentMapper documents;
  private final DocumentVersionMapper versions;

  DocumentAccess(
      KnowledgeBaseService knowledgeBases,
      DocumentMapper documents,
      DocumentVersionMapper versions) {
    this.knowledgeBases = knowledgeBases;
    this.documents = documents;
    this.versions = versions;
  }

  Document requireDocument(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    knowledgeBases.requireEntity(ownerId, knowledgeBaseId);
    Document document = documents.selectById(documentId);
    if (document == null || !document.getKnowledgeBaseId().equals(knowledgeBaseId)) {
      throw new ApiException(ErrorCode.DOCUMENT_NOT_FOUND, "文档不存在");
    }
    return document;
  }

  DocumentVersion latestVersion(UUID documentId) {
    DocumentVersion version =
        versions.selectOne(
            new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getDocumentId, documentId)
                .orderByDesc(DocumentVersion::getCreatedAt)
                .last("LIMIT 1"));
    if (version == null) {
      throw new ApiException(ErrorCode.DOCUMENT_VERSION_NOT_FOUND, "文档版本不存在");
    }
    return version;
  }
}
