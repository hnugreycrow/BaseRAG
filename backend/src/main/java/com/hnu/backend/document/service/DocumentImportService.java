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
import com.hnu.backend.document.parser.DocumentFormat;
import com.hnu.backend.document.parser.DocumentParserRegistry;
import com.hnu.backend.document.storage.FileStorage;
import com.hnu.backend.document.vo.DocumentBatchUploadResponse;
import com.hnu.backend.document.vo.DocumentChunkBatchResponse;
import com.hnu.backend.document.vo.DocumentImportResponse;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseAccess;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import com.hnu.backend.shared.error.SafeExceptionLog;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** 管理原文件上传、索引任务准入、异步排队和中断恢复。 */
final class DocumentImportService {
  private static final Logger log = LoggerFactory.getLogger(DocumentImportService.class);
  private final KnowledgeBaseAccess knowledgeBaseService;
  private final DocumentMapper documentMapper;
  private final DocumentVersionMapper documentVersionMapper;
  private final DocumentChunkMapper documentChunkMapper;

  /** 按版本格式选择解析器，并在上传时验证原文件内容。 */
  private final DocumentParserRegistry parsers;

  private final DocumentIndexService indexer;
  private final FileStorage storage;
  private final TransactionTemplate tx;
  private final DocumentAccess access;
  private final Semaphore imports;
  private final Semaphore taskSlots;
  private final ThreadPoolExecutor taskExecutor;

  /**
   * 创建文档服务。
   *
   * @param knowledgeBaseService 知识库所有权与模型服务
   * @param documentMapper 文档持久化接口
   * @param documentVersionMapper 文档版本持久化接口
   * @param documentChunkMapper 文档分块持久化接口
   * @param parsers 上传内容校验器
   * @param indexer 同步与异步共用的索引处理服务
   * @param storage 对象存储接口
   * @param tx 事务模板
   * @param processing 分块并发与排队容量配置
   * @param access 文档归属与版本查找
   */
  DocumentImportService(
      KnowledgeBaseAccess knowledgeBaseService,
      DocumentMapper documentMapper,
      DocumentVersionMapper documentVersionMapper,
      DocumentChunkMapper documentChunkMapper,
      DocumentParserRegistry parsers,
      DocumentIndexService indexer,
      FileStorage storage,
      TransactionTemplate tx,
      DocumentProcessingProperties processing,
      DocumentAccess access) {
    this.knowledgeBaseService = knowledgeBaseService;
    this.documentMapper = documentMapper;
    this.documentVersionMapper = documentVersionMapper;
    this.documentChunkMapper = documentChunkMapper;
    this.parsers = parsers;
    this.indexer = indexer;
    this.storage = storage;
    this.tx = tx;
    this.access = access;
    this.imports = new Semaphore(processing.getSyncConcurrency());
    this.taskSlots =
        new Semaphore(Math.addExact(processing.getWorkers(), processing.getQueueCapacity()));
    this.taskExecutor =
        new ThreadPoolExecutor(
            processing.getWorkers(),
            processing.getWorkers(),
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(processing.getQueueCapacity()),
            Thread.ofVirtual().name("document-chunk-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * 校验并保存受支持格式的原文件，同时创建待处理的文档版本。
   *
   * <p>该方法不执行耗时的分块与向量化；若数据库写入失败，会补偿删除已上传的对象。
   *
   * @param ownerId 所属用户
   * @param knowledgeBaseId 所属知识库
   * @param file 上传文件
   * @return 状态为 {@code UPLOADED} 的导入结果
   */
  public DocumentImportResponse upload(UUID ownerId, UUID knowledgeBaseId, MultipartFile file) {
    var knowledgeBase = knowledgeBaseService.ensureEmbedding(ownerId, knowledgeBaseId);
    String name = Optional.ofNullable(file.getOriginalFilename()).orElse("");
    name = name.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1);
    if (name.isBlank() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl)) {
      throw ApiException.bad(ErrorCode.INVALID_FILE, "文件名不能超过 255 字符");
    }
    DocumentFormat format = DocumentFormat.fromName(name);
    if (file.isEmpty()) {
      throw ApiException.bad(ErrorCode.EMPTY_DOCUMENT, "文档不能为空");
    }
    // Markdown 限 5 MiB；PDF 和 DOCX 限 20 MiB，读取前后均检查大小。
    long limit = (format == DocumentFormat.MARKDOWN ? 5L : 20L) * 1024 * 1024;
    if (file.getSize() > limit) {
      throw new ApiException(ErrorCode.FILE_TOO_LARGE, "文件超过格式大小限制");
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (java.io.IOException e) {
      throw ApiException.bad(ErrorCode.INVALID_FILE, "无法读取上传文件");
    }
    if (bytes.length > limit) {
      throw new ApiException(ErrorCode.FILE_TOO_LARGE, "文件超过格式大小限制");
    }
    parsers.verify(name, bytes);

    Document document = new Document();
    document.setId(UUID.randomUUID());
    document.setKnowledgeBaseId(knowledgeBaseId);
    document.setName(name);
    DocumentVersion version = new DocumentVersion();
    version.setId(UUID.randomUUID());
    version.setDocumentId(document.getId());
    version.setKnowledgeBaseId(knowledgeBaseId);
    version.setFileHash(hash(bytes));
    version.setStorageKey(
        "users/"
            + ownerId
            + "/knowledge-bases/"
            + knowledgeBaseId
            + "/documents/"
            + document.getId()
            + "/"
            + version.getId()
            + format.extension());
    version.setFormat(format.name());
    version.setMediaType(format.mediaType());
    version.setFileSizeBytes((long) bytes.length);
    version.setStatus(DocumentVersionStatus.UPLOADED);
    version.setParserVersion(format.parserVersion());
    version.setChunkerVersion("structured-block-v6");
    version.setEmbeddingModelId(knowledgeBase.modelId());
    version.setEmbeddingProvider(knowledgeBase.provider());
    version.setEmbeddingModel(knowledgeBase.model());
    version.setEmbeddingDimensions(knowledgeBase.dimensions());

    try {
      storage.put(version.getStorageKey(), bytes, version.getMediaType());
      tx.executeWithoutResult(
          status -> {
            knowledgeBaseService.lockAndBind(
                ownerId,
                knowledgeBaseId,
                version.getEmbeddingModelId(),
                version.getEmbeddingProvider(),
                version.getEmbeddingModel(),
                version.getEmbeddingDimensions());
            documentMapper.insert(document);
            documentVersionMapper.insert(version);
          });
      log.info("upload documentId={} status=UPLOADED", document.getId());
      return new DocumentImportResponse(document.getId(), DocumentVersionStatus.UPLOADED.name(), 0);
    } catch (RuntimeException e) {
      removeStoredFile(version.getStorageKey());
      if (e instanceof ApiException api) {
        throw api;
      }
      throw ApiException.upstream(ErrorCode.IMPORT_FAILED, "文档入库失败，请检查服务状态后重新上传", e);
    }
  }

  /** 校验批次后逐个上传，单个文件失败不影响其他文件。 */
  public DocumentBatchUploadResponse uploadBatch(
      UUID ownerId, UUID knowledgeBaseId, List<MultipartFile> files) {
    if (files == null || files.isEmpty() || files.size() > 10) {
      throw ApiException.bad(ErrorCode.INVALID_BATCH_SIZE, "每次请选择 1 至 10 个文件");
    }
    knowledgeBaseService.ensureEmbedding(ownerId, knowledgeBaseId);
    List<DocumentBatchUploadResponse.Item> results = new ArrayList<>(files.size());
    for (int index = 0; index < files.size(); index++) {
      MultipartFile file = files.get(index);
      String fileName = Optional.ofNullable(file.getOriginalFilename()).orElse("");
      fileName = fileName.replace('\\', '/');
      fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
      try {
        DocumentImportResponse uploaded = upload(ownerId, knowledgeBaseId, file);
        results.add(
            new DocumentBatchUploadResponse.Item(
                index, fileName, uploaded.status(), uploaded.documentId(), null, null));
      } catch (ApiException e) {
        results.add(
            new DocumentBatchUploadResponse.Item(
                index,
                fileName,
                DocumentVersionStatus.FAILED.name(),
                null,
                e.code(),
                e.getMessage()));
      }
    }
    return new DocumentBatchUploadResponse(results);
  }

  /**
   * 对文档最新版本执行分块和向量化。
   *
   * <p>同步导入并发由文档处理配置限制，防止模型和数据库连接被批量任务耗尽。
   *
   * @param ownerId 所属用户标识
   * @param knowledgeBaseId 知识库标识
   * @param documentId 文档标识
   * @return 最新导入状态
   */
  public DocumentImportResponse createChunks(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    if (!imports.tryAcquire()) {
      throw new ApiException(ErrorCode.IMPORT_BUSY, "正在处理其他文档，请稍后重试");
    }
    try {
      return indexer.process(ownerId, knowledgeBaseId, documentId, false);
    } finally {
      imports.release();
    }
  }

  /** 提交单篇文档处理任务，并立即返回处理状态。 */
  public DocumentImportResponse enqueueChunks(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    enqueueBatch(ownerId, knowledgeBaseId, List.of(documentId), false);
    Document document = access.requireDocument(ownerId, knowledgeBaseId, documentId);
    long count =
        document.getActiveVersionId() == null
            ? 0
            : documentChunkMapper.selectCount(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getVersionId, document.getActiveVersionId()));
    return new DocumentImportResponse(
        documentId, DocumentVersionStatus.PROCESSING.name(), (int) count);
  }

  /** 校验所有权，跳过已有任务，原子抢占其余文档后提交进程内队列。 */
  public DocumentChunkBatchResponse enqueueBatch(
      UUID ownerId, UUID knowledgeBaseId, List<UUID> documentIds, boolean skipProcessing) {
    if (documentIds == null
        || documentIds.isEmpty()
        || documentIds.size() > 50
        || new HashSet<>(documentIds).size() != documentIds.size()
        || documentIds.stream().anyMatch(Objects::isNull)) {
      throw ApiException.bad(ErrorCode.INVALID_BATCH, "请选择 1 到 50 篇不同的文档");
    }
    AtomicInteger reserved = new AtomicInteger();
    DocumentChunkBatchResponse result;
    try {
      result =
          tx.execute(
              status -> {
                knowledgeBaseService.requireOwned(ownerId, knowledgeBaseId);
                List<DocumentVersion> selectedVersions = new ArrayList<>(documentIds.size());
                for (UUID documentId : documentIds) {
                  access.requireDocument(ownerId, knowledgeBaseId, documentId);
                  selectedVersions.add(access.latestVersion(documentId));
                }
                List<UUID> accepted = new ArrayList<>();
                List<UUID> skipped = new ArrayList<>();
                for (int i = 0; i < documentIds.size(); i++) {
                  UUID documentId = documentIds.get(i);
                  DocumentVersion version = selectedVersions.get(i);
                  if (version.getStatus() == DocumentVersionStatus.PROCESSING) {
                    if (!skipProcessing) {
                      throw ApiException.conflict(ErrorCode.DOCUMENT_PROCESSING, "文档正在分块，请稍后刷新");
                    }
                    skipped.add(documentId);
                    continue;
                  }
                  if (indexer.claimVersion(version) == 1) {
                    accepted.add(documentId);
                    continue;
                  }
                  DocumentVersion current = access.latestVersion(documentId);
                  if (skipProcessing
                      && current.getId().equals(version.getId())
                      && current.getStatus() == DocumentVersionStatus.PROCESSING) {
                    skipped.add(documentId);
                    continue;
                  }
                  throw ApiException.conflict(ErrorCode.DOCUMENT_PROCESSING, "所选文档状态已变化，请刷新后重试");
                }
                if (!accepted.isEmpty()) {
                  if (!taskSlots.tryAcquire(accepted.size())) {
                    throw new ApiException(ErrorCode.IMPORT_BUSY, "分块队列已满，请稍后重试");
                  }
                  reserved.set(accepted.size());
                }
                return new DocumentChunkBatchResponse(List.copyOf(accepted), List.copyOf(skipped));
              });
      if (result == null) {
        throw new IllegalStateException("文档入队事务未返回结果");
      }
    } catch (RuntimeException e) {
      if (reserved.get() > 0) {
        taskSlots.release(reserved.get());
      }
      throw e;
    }
    List<UUID> accepted = result.acceptedDocumentIds();
    int submitted = 0;
    try {
      for (UUID documentId : accepted) {
        taskExecutor.execute(
            () -> {
              try {
                indexer.process(ownerId, knowledgeBaseId, documentId, true);
              } catch (RuntimeException e) {
                log.error(
                    "backgroundChunking documentId={} exceptionType={} safeStack={}",
                    documentId,
                    e.getClass().getSimpleName(),
                    SafeExceptionLog.render(e));
              } finally {
                taskSlots.release();
              }
            });
        submitted++;
      }
    } catch (RejectedExecutionException e) {
      for (int i = submitted; i < accepted.size(); i++) {
        markInterrupted(accepted.get(i));
      }
      taskSlots.release(accepted.size() - submitted);
      throw new ApiException(ErrorCode.IMPORT_BUSY, "分块队列已停止，请稍后重试");
    }
    return result;
  }

  /** 未完成任务不在重启后续跑；恢复旧索引或标记首次分块失败。 */
  public void markInterruptedTasks() {
    List<DocumentVersion> interrupted =
        documentVersionMapper.selectList(
            new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getStatus, DocumentVersionStatus.PROCESSING));
    interrupted.forEach(version -> markInterrupted(version.getDocumentId()));
  }

  private void markInterrupted(UUID documentId) {
    Document document = documentMapper.selectById(documentId);
    if (document == null) {
      return;
    }
    DocumentVersion version = access.latestVersion(documentId);
    if (version.getStatus() != DocumentVersionStatus.PROCESSING) {
      return;
    }
    boolean rebuilding = Objects.equals(document.getActiveVersionId(), version.getId());
    documentVersionMapper.update(
        new LambdaUpdateWrapper<DocumentVersion>()
            .eq(DocumentVersion::getId, version.getId())
            .eq(DocumentVersion::getStatus, DocumentVersionStatus.PROCESSING)
            .set(
                DocumentVersion::getStatus,
                rebuilding ? DocumentVersionStatus.READY : DocumentVersionStatus.FAILED)
            .set(DocumentVersion::getErrorCode, ErrorCode.IMPORT_INTERRUPTED.code()));
  }

  public void stopTaskExecutor() {
    taskExecutor.shutdownNow();
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

  private String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
