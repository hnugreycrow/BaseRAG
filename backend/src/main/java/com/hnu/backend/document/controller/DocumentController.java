package com.hnu.backend.document.controller;

import com.hnu.backend.auth.service.CurrentUserService;
import com.hnu.backend.document.dto.DocumentChunkBatchRequest;
import com.hnu.backend.document.dto.DocumentRequest;
import com.hnu.backend.document.service.DocumentService;
import com.hnu.backend.document.vo.DocumentBatchUploadResponse;
import com.hnu.backend.document.vo.DocumentChunkBatchResponse;
import com.hnu.backend.document.vo.DocumentChunkDetailResponse;
import com.hnu.backend.document.vo.DocumentChunkResponse;
import com.hnu.backend.document.vo.DocumentImportResponse;
import com.hnu.backend.document.vo.DocumentResponse;
import com.hnu.backend.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 提供知识库文档上传、管理和分块查询接口。 */
@RestController
@Profile("local")
@Validated
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}/documents")
public class DocumentController {
  private final DocumentService documents;
  private final CurrentUserService currentUsers;

  /**
   * 创建文档控制器。
   *
   * @param documents 文档服务
   * @param currentUsers 当前用户解析服务
   */
  public DocumentController(DocumentService documents, CurrentUserService currentUsers) {
    this.documents = documents;
    this.currentUsers = currentUsers;
  }

  /** 上传原文件；分块和向量化由独立接口显式触发。 */
  @PostMapping
  public DocumentImportResponse upload(
      @PathVariable UUID knowledgeBaseId, @RequestPart("file") MultipartFile file) {
    return documents.upload(currentUsers.require().getId(), knowledgeBaseId, file);
  }

  /** 批量上传原文件，每个文件独立返回处理结果。 */
  @PostMapping("/batch")
  public DocumentBatchUploadResponse uploadBatch(
      @PathVariable UUID knowledgeBaseId, @RequestPart("files") List<MultipartFile> files) {
    return documents.uploadBatch(currentUsers.require().getId(), knowledgeBaseId, files);
  }

  /**
   * 按版本返回原文件；PDF 支持浏览器使用单段 HTTP Range 请求预览。
   *
   * @param knowledgeBaseId 知识库标识
   * @param documentId 文档标识
   * @param versionId 原文件版本标识
   * @param range 可选的单段字节范围请求头
   * @return 原文件内容，范围有效时返回部分内容
   */
  @GetMapping("/{documentId}/versions/{versionId}/content")
  public ResponseEntity<byte[]> content(
      @PathVariable UUID knowledgeBaseId,
      @PathVariable UUID documentId,
      @PathVariable UUID versionId,
      @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
    var file =
        documents.originalFile(
            currentUsers.require().getId(), knowledgeBaseId, documentId, versionId);
    byte[] bytes = file.bytes();
    boolean pdf = "application/pdf".equals(file.mediaType());
    var disposition = pdf ? ContentDisposition.inline() : ContentDisposition.attachment();
    var response =
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.mediaType()))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                disposition.filename(file.name(), StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.ACCEPT_RANGES, "bytes");
    if (range == null) return response.contentLength(bytes.length).body(bytes);
    // 仅 PDF 支持单段范围；无效范围统一返回 416 和文件总长度。
    if (!pdf || !range.startsWith("bytes=") || range.indexOf(',') >= 0)
      return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
          .header(HttpHeaders.CONTENT_RANGE, "bytes */" + bytes.length)
          .build();
    try {
      String spec = range.substring(6);
      int separator = spec.indexOf('-');
      if (separator < 0 || spec.indexOf('-', separator + 1) >= 0) throw new NumberFormatException();
      String first = spec.substring(0, separator);
      String last = spec.substring(separator + 1);
      long start;
      long end;
      if (first.isEmpty()) {
        long suffix = Long.parseLong(last);
        if (suffix <= 0) throw new NumberFormatException();
        start = Math.max(0, bytes.length - suffix);
        end = bytes.length - 1L;
      } else {
        start = Long.parseLong(first);
        end = last.isEmpty() ? bytes.length - 1L : Long.parseLong(last);
      }
      if (start < 0 || start >= bytes.length || end < start) throw new NumberFormatException();
      end = Math.min(end, bytes.length - 1L);
      return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
          .contentType(MediaType.APPLICATION_PDF)
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              disposition.filename(file.name(), StandardCharsets.UTF_8).build().toString())
          .header(HttpHeaders.ACCEPT_RANGES, "bytes")
          .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + bytes.length)
          .contentLength(end - start + 1)
          .body(Arrays.copyOfRange(bytes, (int) start, (int) end + 1));
    } catch (NumberFormatException e) {
      return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
          .header(HttpHeaders.CONTENT_RANGE, "bytes */" + bytes.length)
          .build();
    }
  }

  /** 分页查询知识库中的文档及其最新处理状态。 */
  @GetMapping
  public PageResponse<DocumentResponse> list(
      @PathVariable UUID knowledgeBaseId,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize,
      @RequestParam(required = false) @Size(max = 200) String query) {
    return documents.list(currentUsers.require().getId(), knowledgeBaseId, page, pageSize, query);
  }

  /** 查询单篇文档的最新处理状态。 */
  @GetMapping("/{documentId}")
  public DocumentResponse get(@PathVariable UUID knowledgeBaseId, @PathVariable UUID documentId) {
    return documents.get(currentUsers.require().getId(), knowledgeBaseId, documentId);
  }

  /** 修改文档显示名称，不改变存储文件与已有版本。 */
  @PatchMapping("/{documentId}")
  public DocumentResponse rename(
      @PathVariable UUID knowledgeBaseId,
      @PathVariable UUID documentId,
      @Valid @RequestBody DocumentRequest request) {
    return documents.rename(
        currentUsers.require().getId(), knowledgeBaseId, documentId, request.name());
  }

  /** 删除文档、版本、分块及对应的存储文件。 */
  @DeleteMapping("/{documentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID knowledgeBaseId, @PathVariable UUID documentId) {
    documents.delete(currentUsers.require().getId(), knowledgeBaseId, documentId);
  }

  /** 分页查询文档当前生效版本的分块摘要。 */
  @GetMapping("/{documentId}/chunks")
  public PageResponse<DocumentChunkResponse> chunks(
      @PathVariable UUID knowledgeBaseId,
      @PathVariable UUID documentId,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize,
      @RequestParam(required = false) @Size(max = 200) String query) {
    return documents.listChunks(
        currentUsers.require().getId(), knowledgeBaseId, documentId, page, pageSize, query);
  }

  /** 对文档最新版本执行分块和向量化，已就绪版本可通过该接口重建。 */
  @PostMapping("/{documentId}/chunks")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public DocumentImportResponse createChunks(
      @PathVariable UUID knowledgeBaseId, @PathVariable UUID documentId) {
    return documents.enqueueChunks(currentUsers.require().getId(), knowledgeBaseId, documentId);
  }

  /** 一次提交当前页多篇文档，处理中项跳过。 */
  @PostMapping("/chunk-jobs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public DocumentChunkBatchResponse createChunkBatch(
      @PathVariable UUID knowledgeBaseId, @Valid @RequestBody DocumentChunkBatchRequest request) {
    return documents.enqueueBatch(
        currentUsers.require().getId(), knowledgeBaseId, request.documentIds(), true);
  }

  /** 获取当前生效版本中的指定分块全文。 */
  @GetMapping("/{documentId}/chunks/{chunkId}")
  public DocumentChunkDetailResponse chunk(
      @PathVariable UUID knowledgeBaseId,
      @PathVariable UUID documentId,
      @PathVariable UUID chunkId) {
    return documents.chunk(currentUsers.require().getId(), knowledgeBaseId, documentId, chunkId);
  }
}
