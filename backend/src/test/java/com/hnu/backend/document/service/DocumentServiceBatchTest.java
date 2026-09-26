package com.hnu.backend.document.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.document.config.DocumentProcessingProperties;
import com.hnu.backend.document.entity.Document;
import com.hnu.backend.document.entity.DocumentVersion;
import com.hnu.backend.document.entity.DocumentVersionStatus;
import com.hnu.backend.document.mapper.DocumentChunkMapper;
import com.hnu.backend.document.mapper.DocumentMapper;
import com.hnu.backend.document.mapper.DocumentVersionMapper;
import com.hnu.backend.document.parser.MarkdownChunker;
import com.hnu.backend.document.storage.FileStorage;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseAccess;
import com.hnu.backend.model.client.EmbeddingClient;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

class DocumentServiceBatchTest {
  private final UUID ownerId = UUID.randomUUID();
  private final UUID knowledgeBaseId = UUID.randomUUID();
  private final KnowledgeBaseAccess knowledgeBaseService = mock(KnowledgeBaseAccess.class);
  private final DocumentMapper documentMapper = mock(DocumentMapper.class);
  private final DocumentVersionMapper documentVersionMapper = mock(DocumentVersionMapper.class);
  private final FileStorage storage = mock(FileStorage.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private DocumentService documentService;

  @BeforeEach
  void setUp() {
    var kb = new KnowledgeBaseAccess.EmbeddingBinding("model-id", "local", "embedding", 2);
    when(knowledgeBaseService.ensureEmbedding(ownerId, knowledgeBaseId)).thenReturn(kb);
    doAnswer(
            invocation -> {
              Consumer<TransactionStatus> callback = invocation.getArgument(0);
              callback.accept(mock(TransactionStatus.class));
              return null;
            })
        .when(tx)
        .executeWithoutResult(any());
    documentService =
        new DocumentService(
            knowledgeBaseService,
            documentMapper,
            documentVersionMapper,
            mock(DocumentChunkMapper.class),
            mock(MarkdownChunker.class),
            mock(EmbeddingClient.class),
            storage,
            tx,
            new DocumentProcessingProperties());
  }

  @Test
  void oneFileReturnsOneUploadedResult() {
    var result = documentService.uploadBatch(ownerId, knowledgeBaseId, List.of(file("one.md")));
    assertEquals(1, result.results().size());
    assertEquals(0, result.results().getFirst().index());
    assertEquals("UPLOADED", result.results().getFirst().status());
    assertNotNull(result.results().getFirst().documentId());
    verify(documentMapper).insert(any(Document.class));
    verify(documentVersionMapper).insert(any(DocumentVersion.class));
  }

  @Test
  void tenSameNamedFilesCreateIndependentDocumentsAndVersions() {
    List<MultipartFile> files =
        IntStream.range(0, 10).mapToObj(index -> (MultipartFile) file("same.md")).toList();
    var result = documentService.uploadBatch(ownerId, knowledgeBaseId, files);

    assertEquals(10, result.results().size());
    assertEquals(
        IntStream.range(0, 10).boxed().toList(),
        result.results().stream().map(item -> item.index()).toList());
    assertTrue(result.results().stream().allMatch(item -> "UPLOADED".equals(item.status())));
    var documentsCaptor = ArgumentCaptor.forClass(Document.class);
    var versionsCaptor = ArgumentCaptor.forClass(DocumentVersion.class);
    verify(documentMapper, times(10)).insert(documentsCaptor.capture());
    verify(documentVersionMapper, times(10)).insert(versionsCaptor.capture());
    var created = documentsCaptor.getAllValues();
    var savedVersions = versionsCaptor.getAllValues();
    assertEquals(10, new HashSet<>(created.stream().map(Document::getId).toList()).size());
    assertEquals(
        10, new HashSet<>(savedVersions.stream().map(DocumentVersion::getId).toList()).size());
    assertEquals(
        10,
        new HashSet<>(savedVersions.stream().map(DocumentVersion::getStorageKey).toList()).size());
    for (int i = 0; i < 10; i++) {
      assertEquals(created.get(i).getId(), savedVersions.get(i).getDocumentId());
      assertEquals(created.get(i).getId(), result.results().get(i).documentId());
      assertEquals(DocumentVersionStatus.UPLOADED, savedVersions.get(i).getStatus());
    }
    verify(storage, times(10)).put(any(), any(), any());
  }

  @Test
  void tooManyFilesRejectsEntireRequestBeforeSaving() {
    List<MultipartFile> files =
        IntStream.range(0, 11).mapToObj(index -> (MultipartFile) file("same.md")).toList();
    ApiException error =
        assertThrows(
            ApiException.class, () -> documentService.uploadBatch(ownerId, knowledgeBaseId, files));
    assertEquals("INVALID_BATCH_SIZE", error.code());
    verifyNoInteractions(knowledgeBaseService, documentMapper, documentVersionMapper, storage);
  }

  @Test
  void invalidFileDoesNotBlockValidFile() {
    var invalid =
        new MockMultipartFile(
            "files", "wrong.txt", "text/plain", "bad".getBytes(StandardCharsets.UTF_8));
    var result =
        documentService.uploadBatch(
            ownerId, knowledgeBaseId, List.of(invalid, file("good.markdown")));
    assertEquals(
        List.of("FAILED", "UPLOADED"),
        result.results().stream().map(item -> item.status()).toList());
    assertEquals("INVALID_FILE", result.results().getFirst().errorCode());
    assertNull(result.results().getFirst().documentId());
    assertNotNull(result.results().get(1).documentId());
    verify(documentMapper).insert(any(Document.class));
  }

  @Test
  void oversizedFileFailsIndividually() {
    var oversized =
        new MockMultipartFile("files", "large.md", "text/markdown", new byte[5 * 1024 * 1024 + 1]);
    var result =
        documentService.uploadBatch(ownerId, knowledgeBaseId, List.of(oversized, file("good.md")));
    assertEquals("FILE_TOO_LARGE", result.results().getFirst().errorCode());
    assertEquals("UPLOADED", result.results().get(1).status());
    verify(documentMapper).insert(any(Document.class));
  }

  @Test
  void pdfAndDocxAboveTwentyMebibytesFailBeforeSaving() {
    var pdf =
        new MockMultipartFile(
            "files", "huge.pdf", "application/pdf", new byte[20 * 1024 * 1024 + 1]);
    var docx =
        new MockMultipartFile(
            "files", "huge.docx", "application/octet-stream", new byte[20 * 1024 * 1024 + 1]);
    var result = documentService.uploadBatch(ownerId, knowledgeBaseId, List.of(pdf, docx));
    assertEquals(
        List.of("FILE_TOO_LARGE", "FILE_TOO_LARGE"),
        result.results().stream().map(item -> item.errorCode()).toList());
    verifyNoInteractions(documentMapper, documentVersionMapper, storage);
  }

  @Test
  void allInvalidFilesReturnFailuresWithoutSaving() {
    var invalid = new MockMultipartFile("files", "wrong.txt", "text/plain", new byte[] {1});
    var empty = new MockMultipartFile("files", "empty.md", "text/markdown", new byte[0]);
    var result = documentService.uploadBatch(ownerId, knowledgeBaseId, List.of(invalid, empty));
    assertEquals(
        List.of("INVALID_FILE", "EMPTY_DOCUMENT"),
        result.results().stream().map(item -> item.errorCode()).toList());
    verifyNoInteractions(documentMapper, documentVersionMapper, storage);
  }

  private MockMultipartFile file(String name) {
    return new MockMultipartFile(
        "files", name, "text/markdown", "# content".getBytes(StandardCharsets.UTF_8));
  }
}
