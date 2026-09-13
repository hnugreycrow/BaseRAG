package com.hnu.backend.document.controller;

import com.hnu.backend.document.dto.DocumentRequest;
import com.hnu.backend.document.service.DocumentService;
import com.hnu.backend.document.vo.DocumentChunkDetailResponse;
import com.hnu.backend.document.vo.DocumentChunkResponse;
import com.hnu.backend.document.vo.DocumentImportResponse;
import com.hnu.backend.document.vo.DocumentResponse;
import com.hnu.backend.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

  public DocumentController(DocumentService documents) {
    this.documents = documents;
  }

  /** 上传 Markdown 原文件；分块和向量化由独立接口显式触发。 */
  @PostMapping
  public DocumentImportResponse upload(
      @PathVariable UUID knowledgeBaseId, @RequestPart("file") MultipartFile file) {
    return documents.upload(knowledgeBaseId, file);
  }

  /** 分页查询知识库中的文档及其最新处理状态。 */
  @GetMapping
  public PageResponse<DocumentResponse> list(
      @PathVariable UUID knowledgeBaseId,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize,
      @RequestParam(required = false) @Size(max = 200) String query) {
    return documents.list(knowledgeBaseId, page, pageSize, query);
  }

  /** 修改文档显示名称，不改变存储文件与已有版本。 */
  @PatchMapping("/{documentId}")
  public DocumentResponse rename(
      @PathVariable UUID knowledgeBaseId,
      @PathVariable UUID documentId,
      @Valid @RequestBody DocumentRequest request) {
    return documents.rename(knowledgeBaseId, documentId, request.name());
  }

  /** 删除文档、版本、分块及对应的存储文件。 */
  @DeleteMapping("/{documentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID knowledgeBaseId, @PathVariable UUID documentId) {
    documents.delete(knowledgeBaseId, documentId);
  }

  /** 分页查询文档当前生效版本的分块摘要。 */
  @GetMapping("/{documentId}/chunks")
  public PageResponse<DocumentChunkResponse> chunks(
      @PathVariable UUID knowledgeBaseId,
      @PathVariable UUID documentId,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize,
      @RequestParam(required = false) @Size(max = 200) String query) {
    return documents.listChunks(knowledgeBaseId, documentId, page, pageSize, query);
  }

  /** 对文档最新版本执行分块和向量化，已就绪版本可通过该接口重建。 */
  @PostMapping("/{documentId}/chunks")
  public DocumentImportResponse createChunks(
      @PathVariable UUID knowledgeBaseId, @PathVariable UUID documentId) {
    return documents.createChunks(knowledgeBaseId, documentId);
  }

  /** 获取当前生效版本中的指定分块全文。 */
  @GetMapping("/{documentId}/chunks/{chunkId}")
  public DocumentChunkDetailResponse chunk(
      @PathVariable UUID knowledgeBaseId,
      @PathVariable UUID documentId,
      @PathVariable UUID chunkId) {
    return documents.chunk(knowledgeBaseId, documentId, chunkId);
  }
}
