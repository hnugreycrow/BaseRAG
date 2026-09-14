package com.hnu.backend.conversation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.hnu.backend.configuration.ConversationProperties;
import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.GenerationAttemptMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.rag.answer.ContextBuilder;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.rag.planning.SubQuestion;
import com.hnu.backend.rag.retrieval.RetrievalService;
import com.hnu.backend.rag.routing.IntentRoute;
import com.hnu.backend.rag.routing.IntentType;
import com.hnu.backend.rag.routing.RoutingPlan;
import com.hnu.backend.rag.routing.RoutingReasonCode;
import com.hnu.backend.shared.error.ApiException;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.support.TransactionCallback;
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

  @Test
  void retrievesAndPersistsTheStandaloneQuestionFromTheQueryPlan() {
    UUID conversationId = UUID.randomUUID();
    Conversation conversation = new Conversation();
    conversation.setId(conversationId);
    when(conversations.find(conversationId)).thenReturn(conversation);
    when(messages.nextTurn(conversationId)).thenReturn(1);
    when(rag.getMaxQuestionChars()).thenReturn(2000);
    when(conversationContext.prepare(any(Conversation.class), eq(1), eq("原问题")))
        .thenReturn(preparedMixed("改写后的独立问题"));
    when(retrieval.retrieve("改写后的独立问题")).thenReturn(List.of());
    when(contexts.build(List.of())).thenReturn(new ContextBuilder.Context("", List.of()));
    runTransactionsWithResultImmediately();
    runTransactionsImmediately();

    service.ask(conversationId, UUID.randomUUID(), "原问题", "request-id");

    verify(retrieval, timeout(2000)).retrieve("改写后的独立问题");
    verify(messages, timeout(2000)).prepare(any(UUID.class), eq("改写后的独立问题"), eq("[]"));
  }

  @Test
  void systemChatSkipsRetrievalAndPersistsEmptySources() {
    UUID conversationId = UUID.randomUUID();
    Conversation conversation = new Conversation();
    conversation.setId(conversationId);
    when(conversations.find(conversationId)).thenReturn(conversation);
    when(messages.nextTurn(conversationId)).thenReturn(1);
    when(rag.getMaxQuestionChars()).thenReturn(2000);
    when(conversationContext.prepare(any(Conversation.class), eq(1), eq("你好")))
        .thenReturn(preparedSystemChat("你好"));
    when(messages.markStreaming(any(UUID.class))).thenReturn(1);
    when(chat.stream(anyString(), anyString(), any(), any()))
        .thenReturn(new ChatClient.Generation("你好，有什么可以帮你？", "chat", "test", "model"));
    runTransactionsWithResultImmediately();

    service.ask(conversationId, UUID.randomUUID(), "你好", "request-id");

    verify(chat, timeout(2000)).stream(anyString(), contains("你好"), any(), any());
    verify(messages, timeout(2000)).prepare(any(UUID.class), isNull(), eq("[]"));
    verifyNoInteractions(retrieval);
  }

  private ConversationContextService.PreparedContext preparedMixed(String question) {
    QueryPlan plan =
        new QueryPlan(
            question, List.of(new SubQuestion("Q1", question), new SubQuestion("Q2", "你好")));
    RoutingPlan routing =
        new RoutingPlan(
            List.of(
                IntentRoute.knowledgeFallback("Q1", 1, RoutingReasonCode.KNOWLEDGE_SOURCE_REQUIRED),
                new IntentRoute(
                    "Q2",
                    IntentType.SYSTEM_CHAT,
                    1,
                    null,
                    Map.of(),
                    RoutingReasonCode.GENERAL_CHAT)));
    return new ConversationContextService.PreparedContext("历史", plan, routing);
  }

  private ConversationContextService.PreparedContext preparedSystemChat(String question) {
    QueryPlan plan = QueryPlan.fallback(question);
    RoutingPlan routing =
        new RoutingPlan(
            List.of(
                new IntentRoute(
                    "Q1",
                    IntentType.SYSTEM_CHAT,
                    1,
                    null,
                    Map.of(),
                    RoutingReasonCode.GENERAL_CHAT)));
    return new ConversationContextService.PreparedContext("历史", plan, routing);
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

  private void runTransactionsWithResultImmediately() {
    when(tx.execute(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallback<?> callback = invocation.getArgument(0);
              return callback.doInTransaction(mock(TransactionStatus.class));
            });
  }
}
