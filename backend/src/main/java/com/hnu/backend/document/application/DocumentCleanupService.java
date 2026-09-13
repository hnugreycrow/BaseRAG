package com.hnu.backend.document.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnu.backend.document.domain.Document;
import com.hnu.backend.document.domain.DocumentVersion;
import com.hnu.backend.document.infrastructure.persistence.DocumentChunkMapper;
import com.hnu.backend.document.infrastructure.persistence.DocumentMapper;
import com.hnu.backend.document.infrastructure.persistence.DocumentVersionMapper;
import com.hnu.backend.document.infrastructure.storage.FileStorage;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DocumentCleanupService {
  private static final Logger log = LoggerFactory.getLogger(DocumentCleanupService.class);
  private final DocumentMapper documents;
  private final DocumentVersionMapper versions;
  private final DocumentChunkMapper chunks;
  private final FileStorage storage;

  public DocumentCleanupService(
      DocumentMapper documents,
      DocumentVersionMapper versions,
      DocumentChunkMapper chunks,
      FileStorage storage) {
    this.documents = documents;
    this.versions = versions;
    this.chunks = chunks;
    this.storage = storage;
  }

  public List<String> storageKeys(UUID knowledgeBaseId) {
    return versions
        .selectList(
            new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getKnowledgeBaseId, knowledgeBaseId))
        .stream()
        .map(DocumentVersion::getStorageKey)
        .toList();
  }

  public void deleteRecords(UUID knowledgeBaseId) {
    documents.update(
        new LambdaUpdateWrapper<Document>()
            .eq(Document::getKnowledgeBaseId, knowledgeBaseId)
            .set(Document::getActiveVersionId, null));
    chunks.deleteByKnowledgeBase(knowledgeBaseId);
    versions.delete(
        new LambdaQueryWrapper<DocumentVersion>()
            .eq(DocumentVersion::getKnowledgeBaseId, knowledgeBaseId));
    documents.delete(
        new LambdaQueryWrapper<Document>().eq(Document::getKnowledgeBaseId, knowledgeBaseId));
  }

  public void removeStoredFiles(List<String> storageKeys) {
    storageKeys.forEach(this::removeStoredFile);
  }

  private void removeStoredFile(String key) {
    try {
      storage.remove(key);
    } catch (RuntimeException e) {
      log.warn("Could not remove stored knowledge file key={}", key, e);
    }
  }
}
