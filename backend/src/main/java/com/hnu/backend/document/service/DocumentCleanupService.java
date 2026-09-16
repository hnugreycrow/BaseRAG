package com.hnu.backend.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnu.backend.document.entity.Document;
import com.hnu.backend.document.entity.DocumentVersion;
import com.hnu.backend.document.mapper.DocumentChunkMapper;
import com.hnu.backend.document.mapper.DocumentMapper;
import com.hnu.backend.document.mapper.DocumentVersionMapper;
import com.hnu.backend.document.storage.FileStorage;
import com.hnu.backend.shared.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 封装知识库级联删除时的文档数据与对象存储清理。 */
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

  /** 查询知识库全部文档版本对应的对象存储键。 */
  public List<String> storageKeys(UUID knowledgeBaseId) {
    return versions
        .selectList(
            new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getKnowledgeBaseId, knowledgeBaseId))
        .stream()
        .map(DocumentVersion::getStorageKey)
        .toList();
  }

  /**
   * 按外键依赖顺序删除知识库下的文档记录。
   *
   * <p>调用方应在事务中执行该方法。
   */
  public void deleteRecords(UUID knowledgeBaseId) {
    if (versions.selectCount(
            new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getKnowledgeBaseId, knowledgeBaseId)
                .eq(DocumentVersion::getStatus, "PROCESSING"))
        > 0) throw ApiException.conflict("DOCUMENT_PROCESSING", "知识库中有文档正在分块，完成后才能删除");
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

  /** 逐个尽力删除对象存储文件，单个文件失败不影响其余清理。 */
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
