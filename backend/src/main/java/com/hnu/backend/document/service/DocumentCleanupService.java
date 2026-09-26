package com.hnu.backend.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnu.backend.document.api.DocumentCleanup;
import com.hnu.backend.document.entity.Document;
import com.hnu.backend.document.entity.DocumentVersion;
import com.hnu.backend.document.entity.DocumentVersionStatus;
import com.hnu.backend.document.mapper.DocumentChunkMapper;
import com.hnu.backend.document.mapper.DocumentMapper;
import com.hnu.backend.document.mapper.DocumentVersionMapper;
import com.hnu.backend.document.storage.FileStorage;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import com.hnu.backend.shared.error.SafeExceptionLog;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 封装知识库级联删除时的文档数据与对象存储清理。 */
@Service
public class DocumentCleanupService implements DocumentCleanup {
  private static final Logger log = LoggerFactory.getLogger(DocumentCleanupService.class);
  private final DocumentMapper documentMapper;
  private final DocumentVersionMapper documentVersionMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final FileStorage storage;

  /**
   * 创建文档清理服务。
   *
   * @param documentMapper 文档记录访问接口
   * @param documentVersionMapper 版本记录访问接口
   * @param documentChunkMapper 分块记录访问接口
   * @param storage 原文件存储接口
   */
  public DocumentCleanupService(
      DocumentMapper documentMapper,
      DocumentVersionMapper documentVersionMapper,
      DocumentChunkMapper documentChunkMapper,
      FileStorage storage) {
    this.documentMapper = documentMapper;
    this.documentVersionMapper = documentVersionMapper;
    this.documentChunkMapper = documentChunkMapper;
    this.storage = storage;
  }

  /** {@inheritDoc} */
  @Override
  public List<String> storageKeys(UUID knowledgeBaseId) {
    requireId(knowledgeBaseId);
    return documentVersionMapper
        .selectList(
            new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getKnowledgeBaseId, knowledgeBaseId))
        .stream()
        .map(DocumentVersion::getStorageKey)
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public void deleteRecords(UUID knowledgeBaseId) {
    requireId(knowledgeBaseId);
    if (documentVersionMapper.selectCount(
            new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getKnowledgeBaseId, knowledgeBaseId)
                .eq(DocumentVersion::getStatus, DocumentVersionStatus.PROCESSING))
        > 0) {
      throw ApiException.conflict(ErrorCode.DOCUMENT_PROCESSING, "知识库中有文档正在分块，完成后才能删除");
    }
    documentMapper.update(
        new LambdaUpdateWrapper<Document>()
            .eq(Document::getKnowledgeBaseId, knowledgeBaseId)
            .set(Document::getActiveVersionId, null));
    documentChunkMapper.deleteByKnowledgeBase(knowledgeBaseId);
    documentVersionMapper.delete(
        new LambdaQueryWrapper<DocumentVersion>()
            .eq(DocumentVersion::getKnowledgeBaseId, knowledgeBaseId));
    documentMapper.delete(
        new LambdaQueryWrapper<Document>().eq(Document::getKnowledgeBaseId, knowledgeBaseId));
  }

  /** {@inheritDoc} */
  @Override
  public void removeStoredFiles(List<String> storageKeys) {
    storageKeys.forEach(this::removeStoredFile);
  }

  /** 在构造范围条件前拒绝缺失标识，避免将误调用传入持久化层。 */
  private void requireId(UUID knowledgeBaseId) {
    if (knowledgeBaseId == null) {
      throw ApiException.bad(ErrorCode.INVALID_REQUEST, "知识库标识不能为空");
    }
  }

  private void removeStoredFile(String key) {
    try {
      storage.remove(key);
    } catch (RuntimeException e) {
      log.warn(
          "removeStoredKnowledgeFile key={} exceptionType={} safeStack={}",
          key,
          e.getClass().getSimpleName(),
          SafeExceptionLog.render(e));
    }
  }
}
