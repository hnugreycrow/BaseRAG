package com.hnu.backend.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import com.hnu.backend.common.web.PageResponse;
import com.hnu.backend.document.entity.Document;
import com.hnu.backend.document.entity.DocumentChunk;
import com.hnu.backend.document.entity.DocumentVersion;
import com.hnu.backend.document.mapper.DocumentChunkMapper;
import com.hnu.backend.document.mapper.DocumentMapper;
import com.hnu.backend.document.mapper.DocumentVersionMapper;
import com.hnu.backend.document.parser.DocumentFormat;
import com.hnu.backend.document.parser.DocumentParserRegistry;
import com.hnu.backend.document.parser.StructuredBlock;
import com.hnu.backend.document.service.DocumentService.OriginalFile;
import com.hnu.backend.document.storage.FileStorage;
import com.hnu.backend.document.vo.DocumentChunkDetailResponse;
import com.hnu.backend.document.vo.DocumentChunkResponse;
import com.hnu.backend.document.vo.DocumentPreviewResponse;
import com.hnu.backend.document.vo.DocumentResponse;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseAccess;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 查询文档、分块、预览和原始文件。 */
final class DocumentReadService {
  private final KnowledgeBaseAccess knowledgeBaseService;
  private final DocumentMapper documentMapper;
  private final DocumentVersionMapper documentVersionMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final DocumentParserRegistry parsers;
  private final FileStorage storage;
  private final DocumentAccess access;

  DocumentReadService(
      KnowledgeBaseAccess knowledgeBaseService,
      DocumentMapper documentMapper,
      DocumentVersionMapper documentVersionMapper,
      DocumentChunkMapper documentChunkMapper,
      DocumentParserRegistry parsers,
      FileStorage storage,
      DocumentAccess access) {
    this.knowledgeBaseService = knowledgeBaseService;
    this.documentMapper = documentMapper;
    this.documentVersionMapper = documentVersionMapper;
    this.documentChunkMapper = documentChunkMapper;
    this.parsers = parsers;
    this.storage = storage;
    this.access = access;
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
    knowledgeBaseService.requireOwned(ownerId, id);
    String query = normalizeQuery(rawQuery);
    long rowOffset = offset(page, pageSize);
    long total = documentMapper.selectCount(documentQuery(id, query));
    List<DocumentResponse> items =
        documentMapper
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
    return toDocumentResponse(access.requireDocument(ownerId, knowledgeBaseId, documentId));
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
    Document document = access.requireDocument(ownerId, knowledgeBaseId, documentId);
    long rowOffset = offset(page, pageSize);
    if (document.getActiveVersionId() == null) {
      return PageResponse.empty(page, pageSize);
    }
    String query = normalizeQuery(rawQuery);
    long total =
        documentChunkMapper.selectCount(
            chunkQuery(documentId, document.getActiveVersionId(), query));
    List<DocumentChunkResponse> items =
        documentChunkMapper
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
    Document document = access.requireDocument(ownerId, knowledgeBaseId, documentId);
    DocumentChunk chunk = documentChunkMapper.selectById(chunkId);
    if (chunk == null
        || !chunk.getDocumentId().equals(documentId)
        || !Objects.equals(chunk.getVersionId(), document.getActiveVersionId())) {
      throw new ApiException(ErrorCode.CHUNK_NOT_FOUND, "分块不存在");
    }
    return new DocumentChunkDetailResponse(
        chunk.getId(),
        chunk.getDocumentId(),
        chunk.getVersionId(),
        chunk.getChunkIndex(),
        chunk.getHeading(),
        chunk.getLineStart(),
        chunk.getLineEnd(),
        chunk.getContent().length(),
        chunk.getContent(),
        chunk.getSourceUnit(),
        chunk.getSourceStart(),
        chunk.getSourceEnd());
  }

  /**
   * 读取指定版本原文件，并严格校验用户、知识库和文档归属。
   *
   * @param ownerId 所属用户标识
   * @param knowledgeBaseId 知识库标识
   * @param documentId 文档标识
   * @param versionId 原文件版本标识
   * @return 带文件名和 MIME 类型的原文件内容
   */
  public OriginalFile originalFile(
      UUID ownerId, UUID knowledgeBaseId, UUID documentId, UUID versionId) {
    Document document = access.requireDocument(ownerId, knowledgeBaseId, documentId);
    DocumentVersion version = documentVersionMapper.selectById(versionId);
    if (version == null
        || !version.getDocumentId().equals(document.getId())
        || !version.getKnowledgeBaseId().equals(knowledgeBaseId)) {
      throw new ApiException(ErrorCode.DOCUMENT_VERSION_NOT_FOUND, "文档版本不存在");
    }
    return originalFile(document, version);
  }

  /** 返回文档当前版本的原文件。 */
  public OriginalFile originalFile(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    Document document = access.requireDocument(ownerId, knowledgeBaseId, documentId);
    return originalFile(document, access.latestVersion(documentId));
  }

  /** 返回文档当前版本的在线预览数据。 */
  public DocumentPreviewResponse preview(UUID ownerId, UUID knowledgeBaseId, UUID documentId) {
    Document document = access.requireDocument(ownerId, knowledgeBaseId, documentId);
    DocumentVersion version = access.latestVersion(documentId);
    DocumentFormat format =
        DocumentFormat.valueOf(version.getFormat() == null ? "MARKDOWN" : version.getFormat());
    String content = null;
    List<DocumentPreviewResponse.Block> blocks = List.of();
    if (format == DocumentFormat.MARKDOWN) {
      byte[] bytes = storage.get(version.getStorageKey());
      content = new String(bytes, StandardCharsets.UTF_8);
      if (content.startsWith("\uFEFF")) {
        content = content.substring(1);
      }
    } else if (format == DocumentFormat.DOCX) {
      byte[] bytes = storage.get(version.getStorageKey());
      blocks =
          parsers.parser(format).parse(bytes).stream()
              .map(DocumentReadService::toPreviewBlock)
              .toList();
    }
    return new DocumentPreviewResponse(
        document.getName(),
        format.name(),
        version.getMediaType(),
        version.getFileSizeBytes() == null ? 0 : version.getFileSizeBytes(),
        content,
        blocks);
  }

  private static DocumentPreviewResponse.Block toPreviewBlock(StructuredBlock block) {
    Integer level =
        block.kind() == StructuredBlock.Kind.HEADING
            ? Math.max(1, Math.min(6, block.outlinePath().size()))
            : null;
    return new DocumentPreviewResponse.Block(
        block.kind().name(),
        block.content(),
        level,
        block.source().unit().name(),
        block.source().start(),
        block.source().end());
  }

  private OriginalFile originalFile(Document document, DocumentVersion version) {
    return new OriginalFile(
        document.getName(), version.getMediaType(), storage.get(version.getStorageKey()));
  }

  /**
   * 将已验证归属的文档及其最新版本转换为响应。
   *
   * @param document 已验证归属的文档
   * @return 包含最新版本状态和当前分块数的响应
   */
  DocumentResponse toDocumentResponse(Document document) {
    DocumentVersion version = access.latestVersion(document.getId());
    long chunkCount =
        document.getActiveVersionId() == null
            ? 0
            : documentChunkMapper.selectCount(
                new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getVersionId, document.getActiveVersionId()));
    return new DocumentResponse(
        document.getId(),
        document.getName(),
        version.getStatus().name(),
        version.getErrorCode(),
        ErrorCode.messageFor(version.getErrorCode()),
        chunkCount,
        document.getCreatedAt(),
        version.getFormat(),
        version.getMediaType(),
        version.getFileSizeBytes() == null ? 0 : version.getFileSizeBytes(),
        true);
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
        preview,
        chunk.getSourceUnit(),
        chunk.getSourceStart(),
        chunk.getSourceEnd());
  }

  private LambdaQueryWrapper<Document> documentQuery(UUID knowledgeBaseId, String query) {
    LambdaQueryWrapper<Document> wrapper =
        new LambdaQueryWrapper<Document>().eq(Document::getKnowledgeBaseId, knowledgeBaseId);
    if (query != null) {
      wrapper.like(Document::getName, query);
    }
    return wrapper;
  }

  private LambdaQueryWrapper<DocumentChunk> chunkQuery(
      UUID documentId, UUID versionId, String query) {
    LambdaQueryWrapper<DocumentChunk> wrapper =
        new LambdaQueryWrapper<DocumentChunk>()
            .eq(DocumentChunk::getDocumentId, documentId)
            .eq(DocumentChunk::getVersionId, versionId);
    if (query != null) {
      wrapper.and(
          nested ->
              nested
                  .like(DocumentChunk::getHeading, query)
                  .or()
                  .like(DocumentChunk::getContent, query));
    }
    return wrapper;
  }

  private String normalizeQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isBlank()) {
      return null;
    }
    return rawQuery.trim();
  }

  private long offset(int page, int pageSize) {
    if (page < 1 || pageSize < 1 || pageSize > 100) {
      throw ApiException.bad(ErrorCode.INVALID_PAGE, "页码应大于 0，每页数量应为 1 到 100");
    }
    return (long) (page - 1) * pageSize;
  }
}
