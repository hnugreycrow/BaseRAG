package com.hnu.backend.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import com.hnu.backend.common.exception.SafeExceptionLog;
import com.hnu.backend.document.entity.Document;
import com.hnu.backend.document.entity.DocumentChunk;
import com.hnu.backend.document.entity.DocumentVersion;
import com.hnu.backend.document.entity.DocumentVersionStatus;
import com.hnu.backend.document.mapper.DocumentChunkMapper;
import com.hnu.backend.document.mapper.DocumentMapper;
import com.hnu.backend.document.mapper.DocumentVersionMapper;
import com.hnu.backend.document.parser.DocumentFormat;
import com.hnu.backend.document.parser.DocumentParserRegistry;
import com.hnu.backend.document.parser.MarkdownChunker;
import com.hnu.backend.document.parser.StructuredChunkPacker;
import com.hnu.backend.document.storage.FileStorage;
import com.hnu.backend.document.vo.DocumentImportResponse;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseAccess;
import com.hnu.backend.model.client.EmbeddingClient;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/** 执行文档解析、分块、向量化和索引事务切换，供同步请求与后台任务共用。 */
final class DocumentIndexService {
  private static final Logger log = LoggerFactory.getLogger(DocumentIndexService.class);
  private final KnowledgeBaseAccess knowledgeBaseService;
  private final DocumentMapper documentMapper;
  private final DocumentVersionMapper documentVersionMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final MarkdownChunker chunker;
  private final DocumentParserRegistry parsers;
  private final EmbeddingClient embedding;
  private final FileStorage storage;
  private final TransactionTemplate tx;
  private final DocumentAccess access;

  /** 组装索引处理依赖；模型调用在索引切换事务之外执行。 */
  DocumentIndexService(
      KnowledgeBaseAccess knowledgeBaseService,
      DocumentMapper documentMapper,
      DocumentVersionMapper documentVersionMapper,
      DocumentChunkMapper documentChunkMapper,
      MarkdownChunker chunker,
      DocumentParserRegistry parsers,
      EmbeddingClient embedding,
      FileStorage storage,
      TransactionTemplate tx,
      DocumentAccess access) {
    this.knowledgeBaseService = knowledgeBaseService;
    this.documentMapper = documentMapper;
    this.documentVersionMapper = documentVersionMapper;
    this.documentChunkMapper = documentChunkMapper;
    this.chunker = chunker;
    this.parsers = parsers;
    this.embedding = embedding;
    this.storage = storage;
    this.tx = tx;
    this.access = access;
  }

  /** 原子抢占可处理版本；返回 1 表示成功，0 表示状态已变化。 */
  int claimVersion(DocumentVersion version) {
    LambdaUpdateWrapper<DocumentVersion> update =
        new LambdaUpdateWrapper<DocumentVersion>()
            .eq(DocumentVersion::getId, version.getId())
            .in(
                DocumentVersion::getStatus,
                List.of(
                    DocumentVersionStatus.UPLOADED,
                    DocumentVersionStatus.FAILED,
                    DocumentVersionStatus.READY))
            .set(DocumentVersion::getStatus, DocumentVersionStatus.PROCESSING)
            .set(DocumentVersion::getErrorCode, null);
    return documentVersionMapper.update(update);
  }

  /**
   * 在所有权校验后执行分块、向量化和原子版本切换。
   *
   * @param ownerId 所属用户标识；异步链路不得从请求线程隐式读取
   * @param knowledgeBaseId 知识库标识
   * @param documentId 文档标识
   * @param alreadyClaimed 是否已由入队事务抢占为 PROCESSING；同步调用传 false
   * @return 处理结果
   */
  DocumentImportResponse process(
      UUID ownerId, UUID knowledgeBaseId, UUID documentId, boolean alreadyClaimed) {
    Document document = access.requireDocument(ownerId, knowledgeBaseId, documentId);
    DocumentVersion version = access.latestVersion(documentId);
    boolean rebuilding = Objects.equals(document.getActiveVersionId(), version.getId());
    if (!alreadyClaimed && version.getStatus() == DocumentVersionStatus.PROCESSING) {
      throw new ApiException(ErrorCode.DOCUMENT_PROCESSING, "文档正在分块，请稍后刷新");
    }
    if (alreadyClaimed) {
      if (version.getStatus() != DocumentVersionStatus.PROCESSING) {
        throw new ApiException(ErrorCode.DOCUMENT_PROCESSING, "文档状态已变化，请稍后刷新");
      }
    } else {
      int claimed = claimVersion(version);
      if (claimed != 1) {
        throw new ApiException(ErrorCode.DOCUMENT_PROCESSING, "文档正在分块，请稍后刷新");
      }
    }
    version.setStatus(DocumentVersionStatus.PROCESSING);
    version.setErrorCode(null);
    try {
      // 迁移前的版本没有格式字段，按原有 Markdown 格式处理。
      DocumentFormat format =
          DocumentFormat.valueOf(version.getFormat() == null ? "MARKDOWN" : version.getFormat());
      byte[] original = storage.get(version.getStorageKey());
      // 解析器只负责结构和来源；所有格式共用同一分块预算与策略。
      List<StructuredChunkPacker.Chunk> pieces =
          chunker.pack(parsers.parser(format).parse(original));
      if (pieces.isEmpty()) {
        throw ApiException.bad(ErrorCode.EMPTY_DOCUMENT, "文档没有可用文本");
      }
      if (pieces.size() > 1000) {
        throw ApiException.bad(ErrorCode.TOO_MANY_CHUNKS, "单份文档最多处理 1000 个片段，请拆分文档");
      }
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
              pieces.stream().map(StructuredChunkPacker.Chunk::embeddingText).toList());
      tx.executeWithoutResult(
          status -> {
            // 新分块和激活版本在同一事务内切换，查询端不会观察到半成品版本。
            knowledgeBaseService.lockAndBind(
                ownerId,
                knowledgeBaseId,
                version.getEmbeddingModelId(),
                version.getEmbeddingProvider(),
                version.getEmbeddingModel(),
                version.getEmbeddingDimensions());
            documentChunkMapper.delete(
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
              chunk.setSourceUnit(piece.source().unit().name());
              chunk.setSourceStart(piece.source().start());
              chunk.setSourceEnd(piece.source().end());
              if (piece.source().unit()
                  == com.hnu.backend.document.parser.StructuredBlock.SourceSpan.Unit.LINE) {
                chunk.setLineStart(piece.source().start());
                chunk.setLineEnd(piece.source().end());
              }
              chunk.setEmbeddingDimensions(version.getEmbeddingDimensions());
              chunk.setVector(EmbeddingClient.literal(vectors.get(i)));
              documentChunkMapper.insertVector(chunk);
            }
            version.setStatus(DocumentVersionStatus.READY);
            version.setChunkerVersion("structured-block-v6");
            documentVersionMapper.updateById(version);
            document.setActiveVersionId(version.getId());
            documentMapper.updateById(document);
          });
      log.info("chunk documentId={} chunks={} status=READY", document.getId(), pieces.size());
      return new DocumentImportResponse(
          document.getId(), DocumentVersionStatus.READY.name(), pieces.size());
    } catch (RuntimeException e) {
      String code = e instanceof ApiException api ? api.code() : ErrorCode.IMPORT_FAILED.code();
      try {
        tx.executeWithoutResult(
            status -> {
              // 重建失败时保留原 READY 状态和旧分块；首次处理失败则标记为 FAILED。
              version.setStatus(
                  rebuilding ? DocumentVersionStatus.READY : DocumentVersionStatus.FAILED);
              version.setErrorCode(code);
              documentVersionMapper.updateById(version);
            });
      } catch (RuntimeException markingFailure) {
        log.error(
            "markFailedChunking documentId={} code={} exceptionType={} safeStack={}",
            document.getId(),
            code,
            markingFailure.getClass().getSimpleName(),
            SafeExceptionLog.render(markingFailure));
      }
      if (e instanceof ApiException api) {
        throw api;
      }
      throw ApiException.upstream(ErrorCode.IMPORT_FAILED, "文档分块失败，请检查服务状态后重试", e);
    }
  }
}
