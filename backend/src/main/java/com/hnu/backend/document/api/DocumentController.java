package com.hnu.backend.document.api;

import com.hnu.backend.document.application.DocumentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("local")
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}/documents")
public class DocumentController {
  private final DocumentService documents;

  public DocumentController(DocumentService documents) {
    this.documents = documents;
  }

  @PostMapping
  public DocumentImportResponse upload(
      @PathVariable UUID knowledgeBaseId, @RequestPart("file") MultipartFile file) {
    return documents.upload(knowledgeBaseId, file);
  }

  @GetMapping
  public List<DocumentResponse> list(@PathVariable UUID knowledgeBaseId) {
    return documents.list(knowledgeBaseId);
  }

  @PatchMapping("/{documentId}")
  public DocumentResponse rename(
      @PathVariable UUID knowledgeBaseId,
      @PathVariable UUID documentId,
      @Valid @RequestBody DocumentRequest request) {
    return documents.rename(knowledgeBaseId, documentId, request.name());
  }

  @DeleteMapping("/{documentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID knowledgeBaseId, @PathVariable UUID documentId) {
    documents.delete(knowledgeBaseId, documentId);
  }

  @GetMapping("/{documentId}/chunks")
  public List<DocumentChunkResponse> chunks(
      @PathVariable UUID knowledgeBaseId, @PathVariable UUID documentId) {
    return documents.listChunks(knowledgeBaseId, documentId);
  }

  @PostMapping("/{documentId}/chunks")
  public DocumentImportResponse createChunks(
      @PathVariable UUID knowledgeBaseId, @PathVariable UUID documentId) {
    return documents.createChunks(knowledgeBaseId, documentId);
  }

  @GetMapping("/{documentId}/chunks/{chunkId}")
  public DocumentChunkDetailResponse chunk(
      @PathVariable UUID knowledgeBaseId,
      @PathVariable UUID documentId,
      @PathVariable UUID chunkId) {
    return documents.chunk(knowledgeBaseId, documentId, chunkId);
  }
}
