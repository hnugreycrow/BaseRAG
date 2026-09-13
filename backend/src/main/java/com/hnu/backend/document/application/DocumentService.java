package com.hnu.backend.document.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnu.backend.ai.embedding.EmbeddingClient;
import com.hnu.backend.document.api.DocumentChunkDetailResponse;
import com.hnu.backend.document.api.DocumentChunkResponse;
import com.hnu.backend.document.api.DocumentImportResponse;
import com.hnu.backend.document.api.DocumentResponse;
import com.hnu.backend.document.domain.Document;
import com.hnu.backend.document.domain.DocumentChunk;
import com.hnu.backend.document.domain.DocumentVersion;
import com.hnu.backend.document.infrastructure.persistence.DocumentChunkMapper;
import com.hnu.backend.document.infrastructure.persistence.DocumentMapper;
import com.hnu.backend.document.infrastructure.persistence.DocumentVersionMapper;
import com.hnu.backend.document.infrastructure.storage.FileStorage;
import com.hnu.backend.document.parser.MarkdownChunker;
import com.hnu.backend.knowledgebase.application.KnowledgeBaseService;
import com.hnu.backend.shared.error.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

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

  public DocumentImportResponse upload(UUID knowledgeBaseId, MultipartFile file) {
    var knowledgeBase = knowledgeBases.ensureModel(knowledgeBaseId);
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
    version.setStorageKey(knowledgeBaseId + "/" + document.getId() + "/" + version.getId() + ".md");
    version.setStatus("UPLOADED");
    version.setParserVersion("markdown-v1");
    version.setChunkerVersion("semantic-pack-v2");
    version.setEmbeddingModel(knowledgeBase.getEmbeddingModel());
    version.setEmbeddingDimensions(knowledgeBase.getEmbeddingDimensions());

    try {
      storage.put(version.getStorageKey(), bytes);
      tx.executeWithoutResult(
          status -> {
            knowledgeBases.lockAndBindModel(
                knowledgeBaseId, version.getEmbeddingModel(), version.getEmbeddingDimensions());
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

  public DocumentImportResponse createChunks(UUID knowledgeBaseId, UUID documentId) {
    if (!imports.tryAcquire())
      throw new ApiException("IMPORT_BUSY", "正在处理其他文档，请稍后重试", HttpStatus.TOO_MANY_REQUESTS);
    try {
      return processChunks(knowledgeBaseId, documentId);
    } finally {
      imports.release();
    }
  }

  private DocumentImportResponse processChunks(UUID knowledgeBaseId, UUID documentId) {
    Document document = requireDocument(knowledgeBaseId, documentId);
    DocumentVersion version = latestVersion(documentId);
    boolean rebuilding = "READY".equals(version.getStatus());
    if ("PROCESSING".equals(version.getStatus())) {
      throw new ApiException("DOCUMENT_PROCESSING", "文档正在分块，请稍后刷新", HttpStatus.CONFLICT);
    }
    int claimed =
        versions.update(
            new LambdaUpdateWrapper<DocumentVersion>()
                .eq(DocumentVersion::getId, version.getId())
                .in(DocumentVersion::getStatus, "UPLOADED", "FAILED", "READY")
                .set(DocumentVersion::getStatus, "PROCESSING")
                .set(DocumentVersion::getErrorCode, null));
    if (claimed != 1)
      throw new ApiException("DOCUMENT_PROCESSING", "文档正在分块，请稍后刷新", HttpStatus.CONFLICT);
    version.setStatus("PROCESSING");
    version.setErrorCode(null);
    try {
      String text = decode(storage.get(version.getStorageKey()));
      List<MarkdownChunker.Piece> pieces = chunker.split(text);
      if (pieces.isEmpty()) throw ApiException.bad("EMPTY_DOCUMENT", "文档没有可用文本");
      if (pieces.size() > 1000)
        throw ApiException.bad("TOO_MANY_CHUNKS", "单份文档最多处理 1000 个片段，请拆分文档");
      embedding.requireConfigured(version.getEmbeddingModel(), version.getEmbeddingDimensions());
      List<float[]> vectors =
          embedding.embed(
              version.getEmbeddingModel(),
              version.getEmbeddingDimensions(),
              pieces.stream().map(MarkdownChunker.Piece::content).toList());
      tx.executeWithoutResult(
          status -> {
            knowledgeBases.lockAndBindModel(
                knowledgeBaseId, version.getEmbeddingModel(), version.getEmbeddingDimensions());
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
              chunk.setHeading(piece.heading());
              chunk.setLineStart(piece.lineStart());
              chunk.setLineEnd(piece.lineEnd());
              chunk.setEmbeddingDimensions(version.getEmbeddingDimensions());
              chunk.setVector(EmbeddingClient.literal(vectors.get(i)));
              chunks.insertVector(chunk);
            }
            version.setStatus("READY");
            version.setChunkerVersion("semantic-pack-v2");
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

  public List<DocumentResponse> list(UUID id) {
    knowledgeBases.requireEntity(id);
    return documents
        .selectList(
            new LambdaQueryWrapper<Document>()
                .eq(Document::getKnowledgeBaseId, id)
                .orderByDesc(Document::getCreatedAt))
        .stream()
        .map(
            document -> {
              DocumentVersion version =
                  versions.selectOne(
                      new LambdaQueryWrapper<DocumentVersion>()
                          .eq(DocumentVersion::getDocumentId, document.getId())
                          .orderByDesc(DocumentVersion::getCreatedAt)
                          .last("LIMIT 1"));
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
            })
        .toList();
  }

  public DocumentResponse rename(UUID knowledgeBaseId, UUID documentId, String rawName) {
    Document document = requireDocument(knowledgeBaseId, documentId);
    String name = normalizeDocumentName(rawName);
    document.setName(name);
    documents.updateById(document);
    return list(knowledgeBaseId).stream()
        .filter(item -> item.id().equals(documentId))
        .findFirst()
        .orElseThrow();
  }

  public void delete(UUID knowledgeBaseId, UUID documentId) {
    requireDocument(knowledgeBaseId, documentId);
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

  public List<DocumentChunkResponse> listChunks(UUID knowledgeBaseId, UUID documentId) {
    Document document = requireDocument(knowledgeBaseId, documentId);
    if (document.getActiveVersionId() == null) return List.of();
    return chunks
        .selectList(
            new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId)
                .eq(DocumentChunk::getVersionId, document.getActiveVersionId())
                .orderByAsc(DocumentChunk::getChunkIndex))
        .stream()
        .map(
            chunk -> {
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
            })
        .toList();
  }

  public DocumentChunkDetailResponse chunk(UUID knowledgeBaseId, UUID documentId, UUID chunkId) {
    Document document = requireDocument(knowledgeBaseId, documentId);
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

  private Document requireDocument(UUID knowledgeBaseId, UUID documentId) {
    knowledgeBases.requireEntity(knowledgeBaseId);
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
