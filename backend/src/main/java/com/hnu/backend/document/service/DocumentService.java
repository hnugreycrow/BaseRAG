package com.hnu.backend.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnu.backend.document.entity.Document;
import com.hnu.backend.document.entity.DocumentChunk;
import com.hnu.backend.document.entity.DocumentVersion;
import com.hnu.backend.document.mapper.DocumentChunkMapper;
import com.hnu.backend.document.mapper.DocumentMapper;
import com.hnu.backend.document.mapper.DocumentVersionMapper;
import com.hnu.backend.document.parser.MarkdownChunker;
import com.hnu.backend.document.storage.FileStorage;
import com.hnu.backend.document.vo.DocumentChunkBatchResponse;
import com.hnu.backend.document.vo.DocumentChunkDetailResponse;
import com.hnu.backend.document.vo.DocumentChunkResponse;
import com.hnu.backend.document.vo.DocumentImportResponse;
import com.hnu.backend.document.vo.DocumentResponse;
import com.hnu.backend.knowledgebase.service.KnowledgeBaseService;
import com.hnu.backend.model.client.EmbeddingClient;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.web.PageResponse;
import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** 处理 Markdown 文档的上传、分块、向量化及版本数据维护。 */
@Service
public class DocumentService {
  private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
  private final KnowledgeBaseService knowledgeBases;
  private final DocumentMapper documents;
  private final DocumentVersionMapper versions;
  private final DocumentChunkMapper chunks;
  private final MarkdownChunker chunker;
  private final EmbeddingClient embedding;
  private final FileStorage storage;
  private final TransactionTemplate tx;
  private final Semaphore imports = new Semaphore(2);
  private final Semaphore taskSlots = new Semaphore(52);
  private final ThreadPoolExecutor taskExecutor =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(50),
          Thread.ofVirtual().name("document-chunk-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  /**
   * 创建文档服务。
   *
   * @param knowledgeBases 知识库所有权与模型服务
   * @param documents 文档持久化接口
   * @param versions 文档版本持久化接口
   * @param chunks 文档分块持久化接口
   * @param chunker Markdown 分块器
   * @param embedding 向量模型客户端
   * @param storage 对象存储接口
   * @param tx 事务模板
   */
  public DocumentService(
      KnowledgeBaseService knowledgeBases,
      DocumentMapper documents,
      DocumentVersionMapper versions,
      DocumentChunkMapper chunks,
      MarkdownChunker chunker,
      EmbeddingClient embedding,
      FileStorage storage,
      TransactionTemplate tx) {
    this.knowledgeBases = knowledgeBases;
    this.documents = documents;
    this.versions = versions;
    this.chunks = chunks;
    this.chunker = chunker;
    this.embedding = embedding;
    this.storage = storage;
    this.tx = tx;
  }

  /**
   * 校验并保存 Markdown 原文件，同时创建待处理的文档版本。
   *
   * <p>该方法不执行耗时的分块与向量化；若数据库写入失败，会补偿删除已上传的对象。
   *
   * @param ownerId 所属用户
   * @param knowledgeBaseId 所属知识库
   * @param file 上传文件
   * @return 状态为 {@code UPLOADED} 的导入结果
   */
  public DocumentImportResponse upload(UUID ownerId, UUID knowledgeBaseId, MultipartFile file) {
    var knowledgeBase = knowledgeBases.ensureModel(ownerId, knowledgeBaseId);
    String name = Optional.ofNullable(file.getOriginalFilename()).orElse("");
    name = name.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1);
    if (name.isBlank()
        || name.length() > 255
        || name.chars().anyMatch(Character::isISOControl)
        || !(name.toLowerCase(Locale.ROOT).endsWith(".md")
            || name.toLowerCase(Locale.ROOT).endsWith(".markdown"))) {
      throw ApiException.bad("INVALID_FILE", "请上传文件名不超过 255 字符的 Markdown 文件");
    }
    if (file.isEmpty()) throw ApiException.bad("EMPTY_DOCUMENT", "文档不能为空");
    if (file.getSize() > 5L * 1024 * 1024)
      throw new ApiException("FILE_TOO_LARGE", "文件不能超过 5 MiB", HttpStatus.PAYLOAD_TOO_LARGE);
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (java.io.IOException e) {
      throw ApiException.bad("INVALID_UTF8", "无法读取文件，请使用 UTF-8 编码");
    }
    decode(bytes);

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
            + ".md");
    version.setStatus("UPLOADED");
    version.setParserVersion("markdown-v1");
    version.setChunkerVersion("markdown-block-v4");
    version.setEmbeddingModelId(knowledgeBase.getEmbeddingModelId());
    version.setEmbeddingProvider(knowledgeBase.getEmbeddingProvider());
    version.setEmbeddingModel(knowledgeBase.getEmbeddingModel());
    version.setEmbeddingDimensions(knowledgeBase.getEmbeddingDimensions());

    try {
      storage.put(version.getStorageKey(), bytes);
      tx.executeWithoutResult(
          status -> {
            knowledgeBases.lockAndBindModel(
                ownerId,
                knowledgeBaseId,
                version.getEmbeddingModelId(),
                version.getEmbeddingProvider(),
                version.getEmbeddingModel(),
                version.getEmbeddingDimensions());
            documents.insert(document);
            versions.insert(version);
          });
      log.info("upload documentId={} status=UPLOADED", document.getId());
      return new DocumentImportResponse(document.getId(), "UPLOADED", 0);
    } catch (RuntimeException e) {
      removeStoredFile(version.getStorageKey());
      if (e instanceof ApiException api) throw api;
      throw ApiException.upstream("IMPORT_FAILED", "文档入库失败，请检查服务状态后重新上传");
    }
  }

  /**
   * 对文档最新版本执行分块和向量化。
   *
   * <p>进程内最多允许两个导入任务并发，防止模型和数据库连接被批量任务耗尽。
   *
   * @param ownerId 所属用户标识
   * @param knowledgeBaseId 知识库标识
   * @param documentId 文档标识
   * @return 最新导入状态
   */
  public DocumentImportResponse createChunks(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    if (!imports.tryAcquire())
      throw new ApiException("IMPORT_BUSY", "正在处理其他文档，请稍后重试", HttpStatus.TOO_MANY_REQUESTS);
    try {
      return processChunks(ownerId, knowledgeBaseId, documentId, false);
    } finally {
      imports.release();
    }
  }

  /** 提交单篇文档处理任务，并立即返回处理状态。 */
  public DocumentImportResponse enqueueChunks(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    enqueueBatch(ownerId, knowledgeBaseId, List.of(documentId), false);
    Document document = requireDocument(ownerId, knowledgeBaseId, documentId);
    long count =
        document.getActiveVersionId() == null
            ? 0
            : chunks.selectCount(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getVersionId, document.getActiveVersionId()));
    return new DocumentImportResponse(documentId, "PROCESSING", (int) count);
  }

  /** 校验所有权，跳过已有任务，原子抢占其余文档后提交进程内队列。 */
  public DocumentChunkBatchResponse enqueueBatch(
      UUID ownerId, UUID knowledgeBaseId, List<UUID> documentIds, boolean skipProcessing) {
    if (documentIds == null
        || documentIds.isEmpty()
        || documentIds.size() > 50
        || new HashSet<>(documentIds).size() != documentIds.size()
        || documentIds.stream().anyMatch(Objects::isNull))
      throw ApiException.bad("INVALID_BATCH", "请选择 1 到 50 篇不同的文档");
    AtomicInteger reserved = new AtomicInteger();
    DocumentChunkBatchResponse result;
    try {
      result =
          tx.execute(
              status -> {
                knowledgeBases.requireEntity(ownerId, knowledgeBaseId);
                List<DocumentVersion> selectedVersions = new ArrayList<>(documentIds.size());
                for (UUID documentId : documentIds) {
                  requireDocument(ownerId, knowledgeBaseId, documentId);
                  selectedVersions.add(latestVersion(documentId));
                }
                List<UUID> accepted = new ArrayList<>();
                List<UUID> skipped = new ArrayList<>();
                for (int i = 0; i < documentIds.size(); i++) {
                  UUID documentId = documentIds.get(i);
                  DocumentVersion version = selectedVersions.get(i);
                  if ("PROCESSING".equals(version.getStatus())) {
                    if (!skipProcessing)
                      throw ApiException.conflict("DOCUMENT_PROCESSING", "文档正在分块，请稍后刷新");
                    skipped.add(documentId);
                    continue;
                  }
                  if (claimVersion(version, true) == 1) {
                    accepted.add(documentId);
                    continue;
                  }
                  DocumentVersion current = latestVersion(documentId);
                  if (skipProcessing
                      && current.getId().equals(version.getId())
                      && "PROCESSING".equals(current.getStatus())) {
                    skipped.add(documentId);
                    continue;
                  }
                  throw ApiException.conflict("DOCUMENT_PROCESSING", "所选文档状态已变化，请刷新后重试");
                }
                if (!accepted.isEmpty()) {
                  if (!taskSlots.tryAcquire(accepted.size()))
                    throw new ApiException(
                        "IMPORT_BUSY", "分块队列已满，请稍后重试", HttpStatus.TOO_MANY_REQUESTS);
                  reserved.set(accepted.size());
                }
                return new DocumentChunkBatchResponse(List.copyOf(accepted), List.copyOf(skipped));
              });
    } catch (RuntimeException e) {
      if (reserved.get() > 0) taskSlots.release(reserved.get());
      throw e;
    }
    List<UUID> accepted = result.acceptedDocumentIds();
    int submitted = 0;
    try {
      for (UUID documentId : accepted) {
        taskExecutor.execute(
            () -> {
              try {
                processChunks(ownerId, knowledgeBaseId, documentId, true);
              } catch (RuntimeException e) {
                log.error("Background chunking failed documentId={}", documentId, e);
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
      throw new ApiException("IMPORT_BUSY", "分块队列已停止，请稍后重试", HttpStatus.TOO_MANY_REQUESTS);
    }
    return result;
  }

  private int claimVersion(DocumentVersion version, boolean allowReady) {
    LambdaUpdateWrapper<DocumentVersion> update =
        new LambdaUpdateWrapper<DocumentVersion>()
            .eq(DocumentVersion::getId, version.getId())
            .in(
                DocumentVersion::getStatus,
                allowReady ? List.of("UPLOADED", "FAILED", "READY") : List.of("UPLOADED", "FAILED"))
            .set(DocumentVersion::getStatus, "PROCESSING")
            .set(DocumentVersion::getErrorCode, null);
    return versions.update(update);
  }

  /** 未完成任务不在重启后续跑；恢复旧索引或标记首次分块失败。 */
  @EventListener(ApplicationReadyEvent.class)
  public void markInterruptedTasks() {
    List<DocumentVersion> interrupted =
        versions.selectList(
            new LambdaQueryWrapper<DocumentVersion>().eq(DocumentVersion::getStatus, "PROCESSING"));
    interrupted.forEach(version -> markInterrupted(version.getDocumentId()));
  }

  private void markInterrupted(UUID documentId) {
    Document document = documents.selectById(documentId);
    if (document == null) return;
    DocumentVersion version = latestVersion(documentId);
    if (!"PROCESSING".equals(version.getStatus())) return;
    boolean rebuilding = Objects.equals(document.getActiveVersionId(), version.getId());
    versions.update(
        new LambdaUpdateWrapper<DocumentVersion>()
            .eq(DocumentVersion::getId, version.getId())
            .eq(DocumentVersion::getStatus, "PROCESSING")
            .set(DocumentVersion::getStatus, rebuilding ? "READY" : "FAILED")
            .set(DocumentVersion::getErrorCode, "IMPORT_INTERRUPTED"));
  }

  @PreDestroy
  public void stopTaskExecutor() {
    taskExecutor.shutdownNow();
  }

  /**
   * 在所有权校验后执行分块、向量化和原子版本切换。
   *
   * @param ownerId 所属用户标识；异步链路不得从请求线程隐式读取
   * @param knowledgeBaseId 知识库标识
   * @param documentId 文档标识
   * @return 处理结果
   */
  private DocumentImportResponse processChunks(
      UUID ownerId, UUID knowledgeBaseId, UUID documentId, boolean alreadyClaimed) {
    Document document = requireDocument(ownerId, knowledgeBaseId, documentId);
    DocumentVersion version = latestVersion(documentId);
    boolean rebuilding = Objects.equals(document.getActiveVersionId(), version.getId());
    if (!alreadyClaimed && "PROCESSING".equals(version.getStatus())) {
      throw new ApiException("DOCUMENT_PROCESSING", "文档正在分块，请稍后刷新", HttpStatus.CONFLICT);
    }
    if (alreadyClaimed) {
      if (!"PROCESSING".equals(version.getStatus()))
        throw new ApiException("DOCUMENT_PROCESSING", "文档状态已变化，请稍后刷新", HttpStatus.CONFLICT);
    } else {
      int claimed = claimVersion(version, true);
      if (claimed != 1)
        throw new ApiException("DOCUMENT_PROCESSING", "文档正在分块，请稍后刷新", HttpStatus.CONFLICT);
    }
    version.setStatus("PROCESSING");
    version.setErrorCode(null);
    try {
      String text = decode(storage.get(version.getStorageKey()));
      List<MarkdownChunker.Piece> pieces = chunker.split(text);
      if (pieces.isEmpty()) throw ApiException.bad("EMPTY_DOCUMENT", "文档没有可用文本");
      if (pieces.size() > 1000)
        throw ApiException.bad("TOO_MANY_CHUNKS", "单份文档最多处理 1000 个片段，请拆分文档");
      embedding.requireConfigured(
          version.getEmbeddingModelId(),
          version.getEmbeddingProvider(),
          version.getEmbeddingModel(),
          version.getEmbeddingDimensions());
      List<float[]> vectors =
          embedding.embed(
              version.getEmbeddingModelId(),
              version.getEmbeddingProvider(),
              version.getEmbeddingModel(),
              version.getEmbeddingDimensions(),
              pieces.stream().map(MarkdownChunker.Piece::embeddingText).toList());
      tx.executeWithoutResult(
          status -> {
            // 新分块和激活版本在同一事务内切换，查询端不会观察到半成品版本。
            knowledgeBases.lockAndBindModel(
                ownerId,
                knowledgeBaseId,
                version.getEmbeddingModelId(),
                version.getEmbeddingProvider(),
                version.getEmbeddingModel(),
                version.getEmbeddingDimensions());
            chunks.delete(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, document.getId()));
            for (int i = 0; i < pieces.size(); i++) {
              var piece = pieces.get(i);
              DocumentChunk chunk = new DocumentChunk();
              chunk.setId(UUID.randomUUID());
              chunk.setDocumentId(document.getId());
              chunk.setVersionId(version.getId());
              chunk.setChunkIndex(i);
              chunk.setContent(piece.content());
              chunk.setEmbeddingText(piece.embeddingText());
              chunk.setHeading(piece.heading());
              chunk.setLineStart(piece.lineStart());
              chunk.setLineEnd(piece.lineEnd());
              chunk.setEmbeddingDimensions(version.getEmbeddingDimensions());
              chunk.setVector(EmbeddingClient.literal(vectors.get(i)));
              chunks.insertVector(chunk);
            }
            version.setStatus("READY");
            version.setChunkerVersion("structured-block-v6");
            versions.updateById(version);
            document.setActiveVersionId(version.getId());
            documents.updateById(document);
          });
      log.info("chunk documentId={} chunks={} status=READY", document.getId(), pieces.size());
      return new DocumentImportResponse(document.getId(), "READY", pieces.size());
    } catch (RuntimeException e) {
      String code = e instanceof ApiException api ? api.code() : "IMPORT_FAILED";
      try {
        tx.executeWithoutResult(
            status -> {
              // 重建失败时保留原 READY 状态和旧分块；首次处理失败则标记为 FAILED。
              version.setStatus(rebuilding ? "READY" : "FAILED");
              version.setErrorCode(code);
              versions.updateById(version);
            });
      } catch (RuntimeException markingFailure) {
        log.error("Could not mark failed chunking documentId={} code={}", document.getId(), code);
      }
      if (e instanceof ApiException api) throw api;
      throw ApiException.upstream("IMPORT_FAILED", "文档分块失败，请检查服务状态后重试");
    }
  }

  /**
   * 分页查询知识库内文档及其最新版本状态和生效分块数。
   *
   * @param ownerId 所属用户标识
   * @param id 知识库标识
   * @param page 页码
   * @param pageSize 每页数量
   * @param rawQuery 可选搜索词
   * @return 文档分页
   */
  public PageResponse<DocumentResponse> list(
      UUID ownerId, UUID id, int page, int pageSize, String rawQuery) {
    knowledgeBases.requireEntity(ownerId, id);
    String query = normalizeQuery(rawQuery);
    long rowOffset = offset(page, pageSize);
    long total = documents.selectCount(documentQuery(id, query));
    List<DocumentResponse> items =
        documents
            .selectList(
                documentQuery(id, query)
                    .orderByDesc(Document::getCreatedAt)
                    .orderByAsc(Document::getId)
                    .last("LIMIT " + pageSize + " OFFSET " + rowOffset))
            .stream()
            .map(this::toDocumentResponse)
            .toList();
    return PageResponse.of(items, total, page, pageSize);
  }

  /** 查询单篇文档的最新处理状态。 */
  public DocumentResponse get(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    return toDocumentResponse(requireDocument(ownerId, knowledgeBaseId, documentId));
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
    Document document = requireDocument(ownerId, knowledgeBaseId, documentId);
    String name = normalizeDocumentName(rawName);
    document.setName(name);
    documents.updateById(document);
    return toDocumentResponse(document);
  }

  /**
   * 在事务内删除文档关系数据，提交后尽力移除各版本对应的对象存储文件。
   *
   * @param ownerId 所属用户标识
   * @param knowledgeBaseId 知识库标识
   * @param documentId 文档标识
   */
  public void delete(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    requireDocument(ownerId, knowledgeBaseId, documentId);
    if ("PROCESSING".equals(latestVersion(documentId).getStatus()))
      throw ApiException.conflict("DOCUMENT_PROCESSING", "文档正在分块，完成后才能删除");
    List<DocumentVersion> storedVersions =
        versions.selectList(
            new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getDocumentId, documentId));
    tx.executeWithoutResult(
        status -> {
          documents.update(
              new LambdaUpdateWrapper<Document>()
                  .eq(Document::getId, documentId)
                  .set(Document::getActiveVersionId, null));
          chunks.delete(
              new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, documentId));
          versions.delete(
              new LambdaQueryWrapper<DocumentVersion>()
                  .eq(DocumentVersion::getDocumentId, documentId));
          documents.deleteById(documentId);
        });
    storedVersions.forEach(version -> removeStoredFile(version.getStorageKey()));
  }

  /**
   * 分页查询文档当前生效版本的分块摘要；尚无生效版本时返回空页。
   *
   * @param ownerId 所属用户标识
   * @param knowledgeBaseId 知识库标识
   * @param documentId 文档标识
   * @param page 页码
   * @param pageSize 每页数量
   * @param rawQuery 可选搜索词
   * @return 分块分页
   */
  public PageResponse<DocumentChunkResponse> listChunks(
      UUID ownerId,
      UUID knowledgeBaseId,
      UUID documentId,
      int page,
      int pageSize,
      String rawQuery) {
    Document document = requireDocument(ownerId, knowledgeBaseId, documentId);
    long rowOffset = offset(page, pageSize);
    if (document.getActiveVersionId() == null) return PageResponse.empty(page, pageSize);
    String query = normalizeQuery(rawQuery);
    long total = chunks.selectCount(chunkQuery(documentId, document.getActiveVersionId(), query));
    List<DocumentChunkResponse> items =
        chunks
            .selectList(
                chunkQuery(documentId, document.getActiveVersionId(), query)
                    .orderByAsc(DocumentChunk::getChunkIndex)
                    .last("LIMIT " + pageSize + " OFFSET " + rowOffset))
            .stream()
            .map(this::toChunkResponse)
            .toList();
    return PageResponse.of(items, total, page, pageSize);
  }

  /**
   * 获取文档当前生效版本中的指定分块。
   *
   * @param ownerId 所属用户标识
   * @param knowledgeBaseId 知识库标识
   * @param documentId 文档标识
   * @param chunkId 分块标识
   * @return 分块详情
   */
  public DocumentChunkDetailResponse chunk(
      UUID ownerId, UUID knowledgeBaseId, UUID documentId, UUID chunkId) {
    Document document = requireDocument(ownerId, knowledgeBaseId, documentId);
    DocumentChunk chunk = chunks.selectById(chunkId);
    if (chunk == null
        || !chunk.getDocumentId().equals(documentId)
        || !Objects.equals(chunk.getVersionId(), document.getActiveVersionId()))
      throw new ApiException("CHUNK_NOT_FOUND", "分块不存在", HttpStatus.NOT_FOUND);
    return new DocumentChunkDetailResponse(
        chunk.getId(),
        chunk.getDocumentId(),
        chunk.getVersionId(),
        chunk.getChunkIndex(),
        chunk.getHeading(),
        chunk.getLineStart(),
        chunk.getLineEnd(),
        chunk.getContent().length(),
        chunk.getContent());
  }

  private DocumentResponse toDocumentResponse(Document document) {
    DocumentVersion version = latestVersion(document.getId());
    long chunkCount =
        document.getActiveVersionId() == null
            ? 0
            : chunks.selectCount(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getVersionId, document.getActiveVersionId()));
    return new DocumentResponse(
        document.getId(),
        document.getName(),
        version.getStatus(),
        version.getErrorCode(),
        chunkCount,
        document.getCreatedAt());
  }

  private DocumentChunkResponse toChunkResponse(DocumentChunk chunk) {
    String content = chunk.getContent();
    String preview = content.length() > 160 ? content.substring(0, 160) + "…" : content;
    return new DocumentChunkResponse(
        chunk.getId(),
        chunk.getChunkIndex(),
        chunk.getHeading(),
        chunk.getLineStart(),
        chunk.getLineEnd(),
        content.length(),
        preview);
  }

  private LambdaQueryWrapper<Document> documentQuery(UUID knowledgeBaseId, String query) {
    LambdaQueryWrapper<Document> wrapper =
        new LambdaQueryWrapper<Document>().eq(Document::getKnowledgeBaseId, knowledgeBaseId);
    if (query != null) wrapper.like(Document::getName, query);
    return wrapper;
  }

  private LambdaQueryWrapper<DocumentChunk> chunkQuery(
      UUID documentId, UUID versionId, String query) {
    LambdaQueryWrapper<DocumentChunk> wrapper =
        new LambdaQueryWrapper<DocumentChunk>()
            .eq(DocumentChunk::getDocumentId, documentId)
            .eq(DocumentChunk::getVersionId, versionId);
    if (query != null)
      wrapper.and(
          nested ->
              nested
                  .like(DocumentChunk::getHeading, query)
                  .or()
                  .like(DocumentChunk::getContent, query));
    return wrapper;
  }

  private String normalizeQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isBlank()) return null;
    return rawQuery.trim();
  }

  private long offset(int page, int pageSize) {
    if (page < 1 || pageSize < 1 || pageSize > 100)
      throw ApiException.bad("INVALID_PAGE", "页码应大于 0，每页数量应为 1 到 100");
    return (long) (page - 1) * pageSize;
  }

  private DocumentVersion latestVersion(UUID documentId) {
    DocumentVersion version =
        versions.selectOne(
            new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getDocumentId, documentId)
                .orderByDesc(DocumentVersion::getCreatedAt)
                .last("LIMIT 1"));
    if (version == null)
      throw new ApiException("DOCUMENT_VERSION_NOT_FOUND", "文档版本不存在", HttpStatus.NOT_FOUND);
    return version;
  }

  /** 严格按 UTF-8 解码并拒绝空文本、非法字节和二进制空字符。 */
  private String decode(byte[] bytes) {
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (java.nio.charset.CharacterCodingException e) {
      throw ApiException.bad("INVALID_UTF8", "无法读取文件，请使用 UTF-8 编码");
    }
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    if (text.indexOf('\0') >= 0) throw ApiException.bad("INVALID_FILE", "Markdown 不能包含二进制空字符");
    if (text.isBlank()) throw ApiException.bad("EMPTY_DOCUMENT", "文档没有可用文本");
    return text;
  }

  /**
   * 在知识库所有权校验后加载文档，跨用户访问与不存在统一返回 404。
   *
   * @param ownerId 所属用户标识
   * @param knowledgeBaseId 知识库标识
   * @param documentId 文档标识
   * @return 文档实体
   */
  private Document requireDocument(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    knowledgeBases.requireEntity(ownerId, knowledgeBaseId);
    Document document = documents.selectById(documentId);
    if (document == null || !document.getKnowledgeBaseId().equals(knowledgeBaseId))
      throw new ApiException("DOCUMENT_NOT_FOUND", "文档不存在", HttpStatus.NOT_FOUND);
    return document;
  }

  private String normalizeDocumentName(String rawName) {
    String name = rawName == null ? "" : rawName.trim();
    if (name.isEmpty() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl))
      throw ApiException.bad("INVALID_DOCUMENT_NAME", "文档名称应为 1 到 255 个有效字符");
    return name;
  }

  private void removeStoredFile(String key) {
    try {
      storage.remove(key);
    } catch (RuntimeException e) {
      log.warn("Could not remove stored document file key={}", key, e);
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
