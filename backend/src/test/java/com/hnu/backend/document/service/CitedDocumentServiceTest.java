package com.hnu.backend.document.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.hnu.backend.conversation.service.ConversationService;
import com.hnu.backend.conversation.vo.ConversationResponses;
import com.hnu.backend.knowledgebase.entity.KnowledgeBase;
import com.hnu.backend.knowledgebase.service.KnowledgeBaseService;
import com.hnu.backend.rag.vo.SourceResponse;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 引用原文件必须来自本人会话中已完成回答的实际引用。 */
class CitedDocumentServiceTest {
  private final ConversationService conversationService = mock(ConversationService.class);
  private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
  private final DocumentService documentService = mock(DocumentService.class);
  private final CitedDocumentService citedDocumentService =
      new CitedDocumentService(conversationService, knowledgeBaseService, documentService);
  private final UUID userId = UUID.randomUUID();
  private final UUID conversationId = UUID.randomUUID();
  private final UUID messageId = UUID.randomUUID();
  private final UUID knowledgeBaseId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();
  private final UUID creatorId = UUID.randomUUID();

  @Test
  void readsOnlyActuallyCitedVersionUsingLibraryCreator() {
    answer("COMPLETED", List.of("S1"), "S1");
    KnowledgeBase kb = new KnowledgeBase();
    kb.setOwnerId(creatorId);
    when(knowledgeBaseService.requireAdminOwned(knowledgeBaseId)).thenReturn(kb);
    var file = new DocumentService.OriginalFile("policy.pdf", "application/pdf", new byte[] {1});
    when(documentService.originalFile(creatorId, knowledgeBaseId, documentId, versionId))
        .thenReturn(file);

    assertEquals(file, citedDocumentService.originalFile(userId, conversationId, messageId, "S1"));
    verify(conversationService).get(userId, conversationId);
    verify(documentService).originalFile(creatorId, knowledgeBaseId, documentId, versionId);
  }

  @Test
  void rejectsUncitedAndIncompleteSourcesBeforeReadingStorage() {
    answer("COMPLETED", List.of(), "S1");
    assertEquals(
        "CITED_SOURCE_NOT_FOUND",
        assertThrows(
                ApiException.class,
                () -> citedDocumentService.originalFile(userId, conversationId, messageId, "S1"))
            .code());
    verifyNoInteractions(knowledgeBaseService, documentService);

    reset(conversationService);
    answer("PENDING", List.of("S1"), "S1");
    assertThrows(
        ApiException.class,
        () -> citedDocumentService.originalFile(userId, conversationId, messageId, "S1"));
    verifyNoInteractions(knowledgeBaseService, documentService);
  }

  @Test
  void rejectsAnotherMessageAndConversation() {
    answer("COMPLETED", List.of("S1"), "S1");
    assertThrows(
        ApiException.class,
        () -> citedDocumentService.originalFile(userId, conversationId, UUID.randomUUID(), "S1"));
    verifyNoInteractions(knowledgeBaseService, documentService);

    reset(conversationService);
    when(conversationService.get(userId, conversationId))
        .thenThrow(ApiException.notFound(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在"));
    assertThrows(
        ApiException.class,
        () -> citedDocumentService.originalFile(userId, conversationId, messageId, "S1"));
    verifyNoInteractions(knowledgeBaseService, documentService);
  }

  private void answer(String status, List<String> citations, String sourceCitation) {
    var detail = mock(ConversationResponses.Detail.class);
    var turn = mock(ConversationResponses.Turn.class);
    var message = mock(ConversationResponses.AssistantMessage.class);
    var source = mock(SourceResponse.class);
    when(conversationService.get(userId, conversationId)).thenReturn(detail);
    when(detail.turns()).thenReturn(List.of(turn));
    when(turn.assistantVersions()).thenReturn(List.of(message));
    when(message.id()).thenReturn(messageId);
    when(message.status()).thenReturn(status);
    when(message.citations()).thenReturn(citations);
    when(message.sources()).thenReturn(List.of(source));
    when(source.citationId()).thenReturn(sourceCitation);
    when(source.knowledgeBaseId()).thenReturn(knowledgeBaseId);
    when(source.documentId()).thenReturn(documentId);
    when(source.versionId()).thenReturn(versionId);
  }
}
