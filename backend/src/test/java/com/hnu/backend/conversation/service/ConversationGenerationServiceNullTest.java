package com.hnu.backend.conversation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.entity.MessageRole;
import com.hnu.backend.conversation.entity.MessageStatus;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.GenerationAttemptMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.observability.service.RagTraceManager;
import com.hnu.backend.rag.answer.AnswerStage;
import com.hnu.backend.shared.error.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class ConversationGenerationServiceNullTest {
  private final ConversationMapper conversations = mock(ConversationMapper.class);
  private final MessageMapper messages = mock(MessageMapper.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private final ConversationGenerationService service =
      new ConversationGenerationService(
          conversations,
          messages,
          mock(GenerationAttemptMapper.class),
          mock(AnswerStage.class),
          new RagProperties(),
          tx,
          mock(RagTraceManager.class),
          mock(ConversationTerminalWriter.class),
          mock(ConversationGenerationRunner.class));

  @AfterEach
  void close() {
    service.close();
  }

  @Test
  void rejectsMissingMessageCreationTransactionResult() {
    UUID ownerId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    when(conversations.find(ownerId, conversationId)).thenReturn(conversation(conversationId));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> service.ask(ownerId, conversationId, UUID.randomUUID(), "问题", "request"));

    assertEquals("创建会话消息事务未返回结果", error.getMessage());
  }

  @Test
  void rejectsRetryWhenOriginalUserMessageIsMissing() {
    UUID ownerId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID assistantId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(conversations.find(ownerId, conversationId)).thenReturn(conversation(conversationId));
    when(messages.find(ownerId, assistantId)).thenReturn(failedAssistant(conversationId, userId));

    ApiException error =
        assertThrows(
            ApiException.class,
            () ->
                service.retry(ownerId, conversationId, assistantId, UUID.randomUUID(), "request"));

    assertEquals("MESSAGE_NOT_FOUND", error.code());
    verify(tx, never()).execute(any());
  }

  @Test
  void rejectsMissingRetryTransactionResult() {
    UUID ownerId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID assistantId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(conversations.find(ownerId, conversationId)).thenReturn(conversation(conversationId));
    when(messages.find(ownerId, assistantId)).thenReturn(failedAssistant(conversationId, userId));
    Message user = new Message();
    user.setId(userId);
    user.setRole(MessageRole.USER);
    when(messages.find(ownerId, userId)).thenReturn(user);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                service.retry(ownerId, conversationId, assistantId, UUID.randomUUID(), "request"));

    assertEquals("创建回答版本事务未返回结果", error.getMessage());
  }

  private Conversation conversation(UUID id) {
    Conversation value = new Conversation();
    value.setId(id);
    return value;
  }

  private Message failedAssistant(UUID conversationId, UUID userId) {
    Message value = new Message();
    value.setConversationId(conversationId);
    value.setRole(MessageRole.ASSISTANT);
    value.setStatus(MessageStatus.FAILED);
    value.setReplyToId(userId);
    return value;
  }
}
