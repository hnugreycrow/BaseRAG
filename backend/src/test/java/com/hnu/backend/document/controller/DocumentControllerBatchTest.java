package com.hnu.backend.document.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.service.CurrentUserService;
import com.hnu.backend.document.service.DocumentService;
import com.hnu.backend.document.vo.DocumentBatchUploadResponse;
import com.hnu.backend.document.vo.DocumentChunkBatchResponse;
import com.hnu.backend.knowledgebase.entity.KnowledgeBase;
import com.hnu.backend.knowledgebase.service.KnowledgeBaseService;
import com.hnu.backend.shared.error.GlobalExceptionHandler;
import com.hnu.backend.shared.web.ApiResponseAdvice;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DocumentControllerBatchTest {
  private final DocumentService documentService = mock(DocumentService.class);
  private final CurrentUserService currentUserService = mock(CurrentUserService.class);
  private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
  private final UUID ownerId = UUID.randomUUID();
  private final UUID knowledgeBaseId = UUID.randomUUID();
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    User user = new User();
    // 操作者与知识库创建者不同，异步任务仍须使用创建者标识。
    user.setId(UUID.randomUUID());
    when(currentUserService.requireAdmin()).thenReturn(user);
    KnowledgeBase managed = new KnowledgeBase();
    managed.setOwnerId(ownerId);
    when(knowledgeBaseService.requireAdminOwned(knowledgeBaseId)).thenReturn(managed);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new DocumentController(documentService, currentUserService, knowledgeBaseService))
            .setControllerAdvice(new ApiResponseAdvice(), new GlobalExceptionHandler())
            .build();
  }

  @Test
  void bindsRepeatedFilesAndReturnsOrderedResults() throws Exception {
    var first = file("first.md");
    var second = file("second.markdown");
    UUID documentId = UUID.randomUUID();
    when(documentService.uploadBatch(eq(ownerId), eq(knowledgeBaseId), anyList()))
        .thenReturn(
            new DocumentBatchUploadResponse(
                List.of(
                    new DocumentBatchUploadResponse.Item(
                        0, "first.md", "UPLOADED", documentId, null, null),
                    new DocumentBatchUploadResponse.Item(
                        1, "second.markdown", "FAILED", null, "INVALID_FILE", "无效文件"))));

    mvc.perform(
            multipart("/api/knowledge-bases/{knowledgeBaseId}/documents/batch", knowledgeBaseId)
                .file(first)
                .file(second))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results[0].documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.data.results[1].errorCode").value("INVALID_FILE"));

    verify(documentService)
        .uploadBatch(
            eq(ownerId),
            eq(knowledgeBaseId),
            argThat(
                files ->
                    files.size() == 2
                        && "first.md".equals(files.get(0).getOriginalFilename())
                        && "second.markdown".equals(files.get(1).getOriginalFilename())));
  }

  @Test
  void queuesChunksUnderLibraryCreatorAcrossAdministrators() throws Exception {
    UUID documentId = UUID.randomUUID();
    when(documentService.enqueueBatch(ownerId, knowledgeBaseId, List.of(documentId), true))
        .thenReturn(new DocumentChunkBatchResponse(List.of(documentId), List.of()));

    mvc.perform(
            post("/api/knowledge-bases/{knowledgeBaseId}/documents/chunk-jobs", knowledgeBaseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + documentId + "\"]}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.acceptedDocumentIds[0]").value(documentId.toString()));
    verify(documentService).enqueueBatch(ownerId, knowledgeBaseId, List.of(documentId), true);
  }

  @Test
  void missingFilesRejectsRequest() throws Exception {
    mvc.perform(
            multipart("/api/knowledge-bases/{knowledgeBaseId}/documents/batch", knowledgeBaseId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    verifyNoInteractions(documentService);
  }

  private MockMultipartFile file(String name) {
    return new MockMultipartFile(
        "files", name, "text/markdown", "# content".getBytes(StandardCharsets.UTF_8));
  }
}
