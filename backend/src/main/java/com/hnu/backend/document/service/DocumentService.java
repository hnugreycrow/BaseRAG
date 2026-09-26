package com.hnu.backend.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnu.backend.configuration.DocumentProcessingProperties;
import com.hnu.backend.document.entity.Document;
import com.hnu.backend.document.entity.DocumentChunk;
import com.hnu.backend.document.entity.DocumentVersion;
import com.hnu.backend.document.entity.DocumentVersionStatus;
import com.hnu.backend.document.mapper.DocumentChunkMapper;
import com.hnu.backend.document.mapper.DocumentMapper;
import com.hnu.backend.document.mapper.DocumentVersionMapper;
import com.hnu.backend.document.parser.DocumentParserRegistry;
import com.hnu.backend.document.parser.MarkdownChunker;
import com.hnu.backend.document.storage.FileStorage;
import com.hnu.backend.document.vo.DocumentBatchUploadResponse;
import com.hnu.backend.document.vo.DocumentChunkBatchResponse;
import com.hnu.backend.document.vo.DocumentChunkDetailResponse;
import com.hnu.backend.document.vo.DocumentChunkResponse;
import com.hnu.backend.document.vo.DocumentImportResponse;
import com.hnu.backend.document.vo.DocumentPreviewResponse;
import com.hnu.backend.document.vo.DocumentResponse;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseAccess;
import com.hnu.backend.model.client.EmbeddingClient;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import com.hnu.backend.shared.error.SafeExceptionLog;
import com.hnu.backend.shared.web.PageResponse;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** 处理多格式文档的上传、分块、向量化及版本数据维护。 */
@Service
public class DocumentService {
  private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
  private final DocumentMapper documentMapper;
  private final DocumentVersionMapper documentVersionMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final FileStorage storage;
  private final TransactionTemplate tx;
  private final DocumentImportService importer;
  private final DocumentReadService reader;
  private final DocumentAccess access;

  /**
   * 创建文档服务。
   *
   * @param knowledgeBaseService 知识库所有权与模型服务
   * @param documentMapper 文档持久化接口
   * @param documentVersionMapper 文档版本持久化接口
   * @param documentChunkMapper 文档分块持久化接口
   * @param chunker Markdown 解析和通用结构块打包入口
   * @param embedding 向量模型客户端
   * @param storage 对象存储接口
   * @param tx 事务模板
   * @param processing 分块并发与排队容量配置
   */
  public DocumentService(
      KnowledgeBaseAccess knowledgeBaseService,
      DocumentMapper documentMapper,
      DocumentVersionMapper documentVersionMapper,
      DocumentChunkMapper documentChunkMapper,
      MarkdownChunker chunker,
      EmbeddingClient embedding,
      FileStorage storage,
      TransactionTemplate tx,
      DocumentProcessingProperties processing) {
    this.documentMapper = documentMapper;
    this.documentVersionMapper = documentVersionMapper;
    this.documentChunkMapper = documentChunkMapper;
    this.storage = storage;
    this.tx = tx;
    this.access = new DocumentAccess(knowledgeBaseService, documentMapper, documentVersionMapper);
    this.importer =
        new DocumentImportService(
            knowledgeBaseService,
            documentMapper,
            documentVersionMapper,
            documentChunkMapper,
            chunker,
            embedding,
            storage,
            tx,
            processing,
            access);
    this.reader =
        new DocumentReadService(
            knowledgeBaseService,
            documentMapper,
            documentVersionMapper,
            documentChunkMapper,
            new DocumentParserRegistry(chunker),
            storage,
            access);
  }

  /**
   * 校验并保存原文件及待处理版本，不在上传请求中执行分块。
   *
   * @param ownerId 所属用户标识
   * @param knowledgeBaseId 知识库标识
   * @param file 待上传文件
   * @return 状态为 {@code UPLOADED} 的导入结果
   */
  public DocumentImportResponse upload(UUID ownerId, UUID knowledgeBaseId, MultipartFile file) {
    return importer.upload(ownerId, knowledgeBaseId, file);
  }

  /**
   * 批量上传 1 到 10 个文档，单项失败不影响其余文件。
   *
   * @param ownerId 所属用户标识
   * @param knowledgeBaseId 知识库标识
   * @param files 待上传文件，顺序与结果项一致
   * @return 每个文件的上传状态
   */
  public DocumentBatchUploadResponse uploadBatch(
      UUID ownerId, UUID knowledgeBaseId, List<MultipartFile> files) {
    return importer.uploadBatch(ownerId, knowledgeBaseId, files);
  }

  /**
   * 同步执行文档分块与向量化；并发数达到配置上限时返回忙碌错误。
   *
   * @param ownerId 所属用户标识
   * @param knowledgeBaseId 知识库标识
   * @param documentId 文档标识
   * @return 最新导入状态与分块数量
   */
  public DocumentImportResponse createChunks(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    return importer.createChunks(ownerId, knowledgeBaseId, documentId);
  }

  /**
   * 提交单篇文档的异步分块任务并立即返回。
   *
   * @param ownerId 所属用户标识
   * @param knowledgeBaseId 知识库标识
   * @param documentId 文档标识
   * @return 已提交的处理状态
   */
  public DocumentImportResponse enqueueChunks(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    return importer.enqueueChunks(ownerId, knowledgeBaseId, documentId);
  }

  /**
   * 提交 1 到 50 篇不同文档的异步分块任务。
   *
   * @param ownerId 所属用户标识
   * @param knowledgeBaseId 知识库标识
   * @param documentIds 待处理文档标识；不可重复
   * @param skipProcessing 是否跳过已经处于处理状态的文档
   * @return 已提交和跳过的文档标识
   */
  public DocumentChunkBatchResponse enqueueBatch(
      UUID ownerId, UUID knowledgeBaseId, List<UUID> documentIds, boolean skipProcessing) {
    return importer.enqueueBatch(ownerId, knowledgeBaseId, documentIds, skipProcessing);
  }

  /** 应用启动后恢复未完成的文档任务状态。 */
  @EventListener(ApplicationReadyEvent.class)
  public void markInterruptedTasks() {
    importer.markInterruptedTasks();
  }

  /** 关闭文档处理队列。 */
  @PreDestroy
  public void stopTaskExecutor() {
    importer.stopTaskExecutor();
  }

  /** 分页查询知识库内的文档。 */
  public PageResponse<DocumentResponse> list(
      UUID ownerId, UUID id, int page, int pageSize, String rawQuery) {
    return reader.list(ownerId, id, page, pageSize, rawQuery);
  }

  /** 查询单篇文档的最新处理状态。 */
  public DocumentResponse get(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    return reader.get(ownerId, knowledgeBaseId, documentId);
  }

  /**
   * 修改文档显示名称。
   *
   * @param ownerId 所属用户标识
   * @param knowledgeBaseId 知识库标识
   * @param documentId 文档标识
   * @param rawName 新名称
   * @return 更新后的文档
   */
  public DocumentResponse rename(
      UUID ownerId, UUID knowledgeBaseId, UUID documentId, String rawName) {
    Document document = access.requireDocument(ownerId, knowledgeBaseId, documentId);
    String name = normalizeDocumentName(rawName);
    document.setName(name);
    documentMapper.updateById(document);
    return reader.toDocumentResponse(document);
  }

  /**
   * 在事务内删除文档关系数据，提交后尽力移除各版本对应的对象存储文件。
   *
   * @param ownerId 所属用户标识
   * @param knowledgeBaseId 知识库标识
   * @param documentId 文档标识
   */
  public void delete(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    access.requireDocument(ownerId, knowledgeBaseId, documentId);
    if (access.latestVersion(documentId).getStatus() == DocumentVersionStatus.PROCESSING) {
      throw ApiException.conflict(ErrorCode.DOCUMENT_PROCESSING, "文档正在分块，完成后才能删除");
    }
    List<DocumentVersion> storedVersions =
        documentVersionMapper.selectList(
            new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getDocumentId, documentId));
    tx.executeWithoutResult(
        status -> {
          documentMapper.update(
              new LambdaUpdateWrapper<Document>()
                  .eq(Document::getId, documentId)
                  .set(Document::getActiveVersionId, null));
          documentChunkMapper.delete(
              new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, documentId));
          documentVersionMapper.delete(
              new LambdaQueryWrapper<DocumentVersion>()
                  .eq(DocumentVersion::getDocumentId, documentId));
          documentMapper.deleteById(documentId);
        });
    storedVersions.forEach(version -> removeStoredFile(version.getStorageKey()));
  }

  /** 分页查询当前生效版本的分块摘要。 */
  public PageResponse<DocumentChunkResponse> listChunks(
      UUID ownerId,
      UUID knowledgeBaseId,
      UUID documentId,
      int page,
      int pageSize,
      String rawQuery) {
    return reader.listChunks(ownerId, knowledgeBaseId, documentId, page, pageSize, rawQuery);
  }

  /** 查询当前生效版本的指定分块。 */
  public DocumentChunkDetailResponse chunk(
      UUID ownerId, UUID knowledgeBaseId, UUID documentId, UUID chunkId) {
    return reader.chunk(ownerId, knowledgeBaseId, documentId, chunkId);
  }

  /** 读取指定版本原文件。 */
  public OriginalFile originalFile(
      UUID ownerId, UUID knowledgeBaseId, UUID documentId, UUID versionId) {
    return reader.originalFile(ownerId, knowledgeBaseId, documentId, versionId);
  }

  /** 读取当前版本原文件。 */
  public OriginalFile originalFile(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    return reader.originalFile(ownerId, knowledgeBaseId, documentId);
  }

  /** 返回当前版本的在线预览数据。 */
  public DocumentPreviewResponse preview(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    return reader.preview(ownerId, knowledgeBaseId, documentId);
  }

  /** 原文件下载内容。 */
  public record OriginalFile(String name, String mediaType, byte[] bytes) {}

  private String normalizeDocumentName(String rawName) {
    String name = rawName == null ? "" : rawName.trim();
    if (name.isEmpty() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl)) {
      throw ApiException.bad(ErrorCode.INVALID_DOCUMENT_NAME, "文档名称应为 1 到 255 个有效字符");
    }
    return name;
  }

  private void removeStoredFile(String key) {
    try {
      storage.remove(key);
    } catch (RuntimeException e) {
      log.warn(
          "removeStoredDocument key={} exceptionType={} safeStack={}",
          key,
          e.getClass().getSimpleName(),
          SafeExceptionLog.render(e));
    }
  }
}
