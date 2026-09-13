package com.hnu.backend.conversation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.hnu.backend.configuration.ConversationProperties;
import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.GenerationAttemptMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.rag.service.RetrievalService;
import com.hnu.backend.rag.support.ContextBuilder;
import com.hnu.backend.shared.error.ApiException;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class ConversationServiceCancellationTest {
  @Mock private ConversationMapper conversations;
  @Mock private MessageMapper messages;
  @Mock private GenerationAttemptMapper attempts;
  @Mock private ConversationContextService conversationContext;
  @Mock private RetrievalService retrieval;
  @Mock private ContextBuilder contexts;
  @Mock private ChatClient chat;
  @Mock private RagProperties rag;
  @Mock private ConversationProperties config;
  @Mock private TransactionTemplate tx;

  private ConversationService service;

  @BeforeEach
  void setUp() {
    service =
        new ConversationService(
            conversations,
            messages,
            attempts,
            conversationContext,
            retrieval,
            contexts,
            chat,
            rag,
            config,
            tx);
  }

  @AfterEach
  void tearDown() {
    service.close();
  }

  @Test
  void cancelsPersistedRunningGenerationWhenInMemoryTaskIsMissing() {
    UUID conversationId = UUID.randomUUID();
    UUID generationId = UUID.randomUUID();
    Message pending = assistant(conversationId, generationId, "PENDING");
    when(messages.find(generationId)).thenReturn(pending);
    when(messages.cancelRunning(generationId, conversationId, "partial")).thenReturn(1);
    runTransactionsImmediately();

    service.cancel(conversationId, generationId);

    InOrder order = inOrder(messages, attempts, conversations);
    order.verify(messages).cancelRunning(generationId, conversationId, "partial");
    order.verify(attempts).cancelRunning(generationId);
    order.verify(conversations).touch(conversationId);
  }

  @Test
  void repeatedCancellationOfTerminalGenerationIsIdempotent() {
    UUID conversationId = UUID.randomUUID();
    UUID generationId = UUID.randomUUID();
    when(messages.find(generationId))
        .thenReturn(assistant(conversationId, generationId, "CANCELLED"));

    service.cancel(conversationId, generationId);

    verify(tx, never()).executeWithoutResult(any());
  }

  @Test
  void rejectsGenerationFromAnotherConversation() {
    UUID conversationId = UUID.randomUUID();
    UUID generationId = UUID.randomUUID();
    when(messages.find(generationId))
        .thenReturn(assistant(UUID.randomUUID(), generationId, "PENDING"));

    ApiException error =
        assertThrows(ApiException.class, () -> service.cancel(conversationId, generationId));

    assertEquals("GENERATION_NOT_FOUND", error.code());
    verify(tx, never()).executeWithoutResult(any());
  }

  private Message assistant(UUID conversationId, UUID generationId, String status) {
    Message message = new Message();
    message.setId(generationId);
    message.setConversationId(conversationId);
    message.setRole("ASSISTANT");
    message.setStatus(status);
    message.setContent("partial");
    return message;
  }

  private void runTransactionsImmediately() {
    doAnswer(
            invocation -> {
              Consumer<TransactionStatus> callback = invocation.getArgument(0);
              callback.accept(mock(TransactionStatus.class));
              return null;
            })
        .when(tx)
        .executeWithoutResult(any());
  }
}
